#!/usr/bin/env python3
"""A stand-in for MoMo's sandbox, for developing the MoMo payment flow on your own machine (no merchant account needed).

    python3 scripts/momo_sim.py        # listens on 0.0.0.0:8098, reads the merchant keys from application-local.properties

Point the backend at it (application-local.properties is git-ignored):

    momo.partner-code=MOMOTEST
    momo.access-key=local-access-key
    momo.secret-key=local-secret-key
    momo.endpoint=http://localhost:8098/v2/gateway/api/create        # the backend calls this from your machine
    momo.redirect-url=http://10.0.2.2:8080/api/v1/payments/momo/return   # the emulator's browser is sent here
    momo.ipn-url=http://localhost:8080/api/v1/payments/momo/ipn          # this script calls it, like MoMo does

then restart the backend, pick MoMo at checkout in the app. The create call is answered with a pay URL on this server
(10.0.2.2:8098, how the emulator reaches your machine); there you choose an outcome and this script posts a signed IPN
(expecting HTTP 204) and redirects the browser to the redirect URL, which opens the app.

It checks the signature of the create request the backend sends, so a wrong signing rule shows up here. It can NOT prove
that real MoMo accepts our requests: the field lists follow MoMo's v2 documentation as recalled when this was written,
and this script mirrors the backend, so check both against the current MoMo docs once real sandbox keys exist.
"""
import argparse, hashlib, hmac, html, json, os, random, sys, time, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlencode, urlparse, parse_qs

DEFAULT_PROPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "application-local.properties")

CREATE_FIELDS = ["accessKey", "amount", "extraData", "ipnUrl", "orderId", "orderInfo", "partnerCode", "redirectUrl", "requestId", "requestType"]
CALLBACK_FIELDS = ["accessKey", "amount", "extraData", "message", "orderId", "orderInfo", "orderType", "partnerCode", "payType",
                   "requestId", "responseTime", "resultCode", "transId"]

# What the customer can "do" on the MoMo page: label, MoMo resultCode, message.
OUTCOMES = {
    "ok": ("Thanh toán thành công", 0, "Successful."),
    "denied": ("Từ chối thanh toán", 1006, "Transaction denied by user."),
    "funds": ("Số dư không đủ", 1001, "Transaction failed due to insufficient fund."),
}

ORDERS = {}  # orderId -> the create request, kept so the IPN and redirect can be built later


def sign(values: dict, fields: list, access_key: str, secret: str) -> str:
    """HMAC-SHA256 (lowercase hex) over 'key=value' pairs in the fixed order; MoMo signs the raw, unencoded values."""
    raw = "&".join(f"{f}={access_key if f == 'accessKey' else values.get(f, '')}" for f in fields)
    return hmac.new(secret.strip().encode(), raw.encode(), hashlib.sha256).hexdigest()


def read_props(path: str) -> dict:
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


def build_callback(order: dict, outcome: str, access_key: str, secret: str) -> dict:
    """The body MoMo posts to the IPN and appends to the redirect URL, signed."""
    _, code, message = OUTCOMES[outcome]
    back = {
        "partnerCode": order["partnerCode"], "orderId": order["orderId"], "requestId": order["requestId"], "amount": str(order["amount"]),
        "orderInfo": order.get("orderInfo", ""), "orderType": "momo_wallet", "transId": str(random.randint(2_000_000_000, 4_999_999_999)),
        "resultCode": str(code), "message": message, "payType": "qr", "responseTime": str(int(time.time() * 1000)),
        "extraData": order.get("extraData", ""),
    }
    back["signature"] = sign(back, CALLBACK_FIELDS, access_key, secret)
    return back


PAGE = """<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MoMo (giả lập)</title><style>
body{{font-family:system-ui,sans-serif;margin:0;background:#fdf2f8;color:#111}}main{{max-width:440px;margin:0 auto;padding:20px}}
.banner{{background:#fde68a;border-radius:10px;padding:10px 14px;font-size:13px;margin-bottom:16px}}
.card{{background:#fff;border-radius:16px;padding:20px;box-shadow:0 1px 4px rgba(0,0,0,.08)}}
.row{{display:flex;justify-content:space-between;padding:6px 0;font-size:14px}}.row span:first-child{{color:#666}}
.amount{{font-size:28px;font-weight:800;margin:6px 0 14px;color:#a50064}}button{{width:100%;border:0;border-radius:12px;padding:14px;font-size:16px;font-weight:700;margin-top:10px;color:#fff}}
.ok{{background:#a50064}}.funds{{background:#d97706}}.denied{{background:#6b7280}}.err{{color:#b91c1c;font-weight:700}}
</style></head><body><main><div class="banner">Đây là bản <b>giả lập cục bộ</b> của cổng MoMo, không phải MoMo thật. Không có tiền nào bị trừ.</div>{body}</main></body></html>"""


class Handler(BaseHTTPRequestHandler):
    partner = access = secret = ""
    public_host = "10.0.2.2:8098"

    def log_message(self, fmt, *args):
        sys.stderr.write("[momo-sim] " + (fmt % args) + "\n")

    def _send(self, status, body, headers=None, ctype="text/html; charset=utf-8"):
        raw = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def _json(self, obj, status=200):
        self._send(status, json.dumps(obj), ctype="application/json")

    def _page(self, status, body):
        self._send(status, PAGE.format(body=body))

    def _body(self) -> bytes:
        """The request body, whether it came with a Content-Length or (as from Spring's HTTP client) chunked."""
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            body = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()  # the blank line after the last chunk
                    return body
                body += self.rfile.read(size)
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_POST(self):
        path = urlparse(self.path).path
        raw = self._body().decode("utf-8")
        if path == "/v2/gateway/api/create":
            return self._create(raw)
        if path == "/finish":
            return self._finish(dict(parse_qsl(raw)))
        self._page(404, '<div class="card">Không tìm thấy.</div>')

    def _create(self, raw: str):
        try:
            body = json.loads(raw)
        except ValueError:
            return self._json({"resultCode": 7, "message": "Invalid JSON"})
        values = {k: ("" if v is None else str(v)) for k, v in body.items()}
        if values.get("partnerCode") != self.partner:
            return self._json({"resultCode": 13, "message": "Merchant authentication failed (partnerCode)."})
        if sign(values, CREATE_FIELDS, self.access, self.secret) != values.get("signature", "").lower():
            return self._json({"resultCode": 13, "message": "Merchant authentication failed (signature)."})
        if values.get("requestType") != "captureWallet" or not values.get("orderId") or not values.get("amount", "").isdigit():
            return self._json({"resultCode": 7, "message": "Invalid request fields."})
        ORDERS[values["orderId"]] = {**values, "amount": int(values["amount"])}
        sys.stderr.write(f"[momo-sim] order {values['orderId']} created for {values['amount']} VND\n")
        self._json({
            "partnerCode": self.partner, "orderId": values["orderId"], "requestId": values["requestId"], "amount": int(values["amount"]),
            "responseTime": int(time.time() * 1000), "message": "Successful.", "resultCode": 0,
            "payUrl": f"http://{self.public_host}/pay?orderId={values['orderId']}",
        })

    def do_GET(self):
        url = urlparse(self.path)
        if url.path != "/pay":
            return self._page(404, '<div class="card">Không tìm thấy.</div>')
        order = ORDERS.get(parse_qs(url.query).get("orderId", [""])[0])
        if not order:
            return self._page(400, '<div class="card"><p class="err">Không tìm thấy đơn hàng (đã tạo qua API chưa?).</p></div>')
        amount = f"{order['amount']:,}".replace(",", ".")
        buttons = "".join(f'<button class="{key}" name="outcome" value="{key}">{label}</button>' for key, (label, _, _) in OUTCOMES.items())
        self._page(200, f"""<div class="card">
<div class="row"><span>Đơn hàng</span><b>{html.escape(order["orderId"])}</b></div>
<div class="row"><span>Nội dung</span><span>{html.escape(order.get("orderInfo", ""))}</span></div>
<div class="amount">{amount} ₫</div>
<form method="post" action="/finish"><input type="hidden" name="orderId" value="{html.escape(order["orderId"], quote=True)}">{buttons}</form></div>""")

    def _finish(self, form: dict):
        order, outcome = ORDERS.get(form.get("orderId", "")), form.get("outcome")
        if not order or outcome not in OUTCOMES:
            return self._page(400, '<div class="card"><p class="err">Lựa chọn không hợp lệ.</p></div>')
        back = build_callback(order, outcome, self.access, self.secret)
        self._send_ipn(order["ipnUrl"], back)
        sys.stderr.write(f"[momo-sim] {order['orderId']} -> {outcome} ({back['resultCode']}), redirecting\n")
        target = order["redirectUrl"] + ("&" if "?" in order["redirectUrl"] else "?") + urlencode(back)
        self._send(302, "", {"Location": target})

    @staticmethod
    def _send_ipn(url: str, back: dict):
        # MoMo posts JSON with numbers where they are numbers; the signature covers their text form, which is the same.
        body = {k: (int(v) if k in ("amount", "resultCode", "transId", "responseTime") else v) for k, v in back.items()}
        req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                sys.stderr.write(f"[momo-sim] IPN answered HTTP {r.status}{'' if r.status == 204 else ' (MoMo expects 204)'}\n")
        except urllib.error.HTTPError as e:
            sys.stderr.write(f"[momo-sim] IPN refused with HTTP {e.code}: MoMo would retry\n")
        except (urllib.error.URLError, OSError) as e:
            sys.stderr.write(f"[momo-sim] IPN not delivered ({e}); the redirect will still settle the payment\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--port", type=int, default=8098)
    ap.add_argument("--props", default=DEFAULT_PROPS, help="application-local.properties to read momo.* from")
    ap.add_argument("--public-host", default="10.0.2.2:8098", help="host:port the browser uses to reach this script (the emulator's name for your machine)")
    args = ap.parse_args()
    props = read_props(args.props)
    missing = [k for k in ("momo.partner-code", "momo.access-key", "momo.secret-key") if not props.get(k)]
    if missing:
        sys.exit(f"{', '.join(missing)} not found in {os.path.normpath(args.props)}")
    Handler.partner, Handler.access, Handler.secret, Handler.public_host = props["momo.partner-code"], props["momo.access-key"], props["momo.secret-key"], args.public_host
    print(f"MoMo simulator on http://0.0.0.0:{args.port}  (partner {Handler.partner}; pay pages at http://{args.public_host}/pay)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
