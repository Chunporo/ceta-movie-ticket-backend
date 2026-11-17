#!/usr/bin/env python3
"""A stand-in for VNPay's sandbox, for developing the payment flow on your own machine (no merchant account needed).

    python3 scripts/vnpay_sim.py                # listens on 0.0.0.0:8099, reads the secret from application-local.properties

Point the backend at it (application-local.properties is git-ignored):

    vnpay.url=http://10.0.2.2:8099/pay          # 10.0.2.2 is how the Android emulator reaches this machine
    vnpay.return-url=http://10.0.2.2:8080/api/v1/payments/vnpay/return

then restart the backend, book seats in the app and tap Pay. The emulator opens this page; choose an outcome and it
sends the backend a correctly signed return (and, like VNPay, a server-to-server IPN first), then redirects the browser to
the return URL, which opens the app. It checks the signature of the payment URL the backend made, so a wrong signing rule
shows up here; it can NOT prove that real VNPay accepts our requests: only the real sandbox can.

The signing rules mirror service/VNPayService: parameters sorted by name, values URL-encoded like Java's
URLEncoder (US-ASCII), HMAC-SHA512 with the merchant secret, lowercase hex.
"""
import argparse, hashlib, hmac, html, os, random, re, sys, threading, urllib.error, urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, quote_plus, urlencode

VN = timezone(timedelta(hours=7))
DEFAULT_PROPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "application-local.properties")

# What the customer can "do" on the bank page: label, VNPay response code, whether it counts as paid.
OUTCOMES = {
    "ok": ("Thanh toán thành công", "00", True),
    "funds": ("Số dư không đủ", "51", False),
    "cancel": ("Hủy giao dịch", "24", False),
}


def java_encode(value: str) -> str:
    """URLEncoder.encode(value, "US-ASCII"): spaces become '+', '*' stays, '~' is escaped."""
    return quote_plus(value, safe="*").replace("~", "%7E")


def sign(params: dict, secret: str) -> str:
    data = "&".join(f"{k}={java_encode(v)}" for k, v in sorted(params.items()) if v not in (None, "") and k not in ("vnp_SecureHash", "vnp_SecureHashType"))
    return hmac.new(secret.strip().encode("ascii"), data.encode("ascii"), hashlib.sha512).hexdigest()


def verify(params: dict, secret: str) -> bool:
    got = params.get("vnp_SecureHash", "")
    return bool(got) and hmac.compare_digest(sign(params, secret), got.lower())


def read_props(path: str) -> dict:
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


def build_return(params: dict, outcome: str, secret: str) -> dict:
    """The query VNPay appends to the return URL (and sends to the IPN), signed."""
    _, code, paid = OUTCOMES[outcome]
    back = {
        "vnp_Amount": params["vnp_Amount"], "vnp_BankCode": "NCB", "vnp_BankTranNo": "VNP" + str(random.randint(10_000_000, 99_999_999)),
        "vnp_CardType": "ATM", "vnp_OrderInfo": params.get("vnp_OrderInfo", ""), "vnp_PayDate": datetime.now(VN).strftime("%Y%m%d%H%M%S"),
        "vnp_ResponseCode": code, "vnp_TmnCode": params["vnp_TmnCode"], "vnp_TransactionNo": str(random.randint(10_000_000, 99_999_999)),
        "vnp_TransactionStatus": "00" if paid else "02", "vnp_TxnRef": params["vnp_TxnRef"],
    }
    back["vnp_SecureHash"] = sign(back, secret)
    return back


PAGE = """<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>VNPAY (giả lập)</title><style>
body{{font-family:system-ui,sans-serif;margin:0;background:#f3f4f6;color:#111}}main{{max-width:440px;margin:0 auto;padding:20px}}
.banner{{background:#fde68a;border-radius:10px;padding:10px 14px;font-size:13px;margin-bottom:16px}}
.card{{background:#fff;border-radius:16px;padding:20px;box-shadow:0 1px 4px rgba(0,0,0,.08)}}
.row{{display:flex;justify-content:space-between;padding:6px 0;font-size:14px}}.row span:first-child{{color:#666}}
.amount{{font-size:28px;font-weight:800;margin:6px 0 14px}}button{{width:100%;border:0;border-radius:12px;padding:14px;font-size:16px;font-weight:700;margin-top:10px;color:#fff}}
.ok{{background:#16a34a}}.funds{{background:#d97706}}.cancel{{background:#6b7280}}.err{{color:#b91c1c;font-weight:700}}
</style></head><body><main><div class="banner">Đây là bản <b>giả lập cục bộ</b> của cổng VNPAY, không phải VNPAY thật. Không có tiền nào bị trừ.</div>{body}</main></body></html>"""


class Handler(BaseHTTPRequestHandler):
    secret = ""
    tmn = ""
    ipn_url = ""

    def log_message(self, fmt, *args):  # one tidy line per request, never the signature
        sys.stderr.write("[sim] " + (fmt % args).split("vnp_SecureHash")[0] + "\n")

    def _send(self, status, body, headers=None):
        raw = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw)

    def _error(self, text):
        self._send(400, PAGE.format(body=f'<div class="card"><p class="err">{html.escape(text)}</p></div>'))

    def _checked(self, query: str):
        """Parse and validate what the backend put in the payment URL; returns (params, problem)."""
        params = dict(parse_qsl(query, keep_blank_values=True))
        if not all(k in params for k in ("vnp_TmnCode", "vnp_Amount", "vnp_TxnRef", "vnp_ReturnUrl")):
            return params, "Thiếu tham số bắt buộc (mã lỗi 03)."
        if not verify(params, self.secret):
            return params, "Chữ ký không hợp lệ (mã lỗi 70): vnp_HashSecret của backend khác với bản giả lập, hoặc cách ký bị sai."
        if params["vnp_TmnCode"].strip() != self.tmn:
            return params, f"Mã website không đúng (mã lỗi 02): {params['vnp_TmnCode']}."
        expire = params.get("vnp_ExpireDate")
        if expire and datetime.now(VN).strftime("%Y%m%d%H%M%S") > expire:
            return params, "Giao dịch đã hết hạn thanh toán (giữ ghế đã hết)."
        return params, None

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path != "/pay":
            return self._send(404, PAGE.format(body='<div class="card">Không tìm thấy.</div>'))
        params, problem = self._checked(query)
        if problem:
            return self._error(problem)
        vnd = f"{int(params['vnp_Amount']) // 100:,}".replace(",", ".")
        buttons = "".join(f'<button class="{key}" name="outcome" value="{key}">{label}</button>' for key, (label, _, _) in OUTCOMES.items())
        self._send(200, PAGE.format(body=f"""<div class="card">
<div class="row"><span>Đơn hàng</span><b>{html.escape(params["vnp_TxnRef"])}</b></div>
<div class="row"><span>Nội dung</span><span>{html.escape(params.get("vnp_OrderInfo", ""))}</span></div>
<div class="amount">{vnd} ₫</div>
<form method="post" action="/finish"><input type="hidden" name="q" value="{html.escape(query, quote=True)}">{buttons}</form></div>""".replace(",", ".")))

    def do_POST(self):
        if self.path != "/finish":
            return self._send(404, PAGE.format(body='<div class="card">Không tìm thấy.</div>'))
        form = dict(parse_qsl(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8")))
        outcome = form.get("outcome")
        params, problem = self._checked(form.get("q", ""))
        if problem or outcome not in OUTCOMES:
            return self._error(problem or "Lựa chọn không hợp lệ.")
        back = build_return(params, outcome, self.secret)
        if self.ipn_url:
            self._send_ipn(back)
        target = params["vnp_ReturnUrl"] + ("&" if "?" in params["vnp_ReturnUrl"] else "?") + urlencode(back, quote_via=lambda s, *_a, **_k: java_encode(s))
        sys.stderr.write(f"[sim] {params['vnp_TxnRef']} -> {outcome} ({OUTCOMES[outcome][1]}), redirecting to the return URL\n")
        self._send(302, "", {"Location": target})

    def _send_ipn(self, back: dict):
        url = self.ipn_url + "?" + urlencode(back, quote_via=lambda s, *_a, **_k: java_encode(s))
        try:
            with urllib.request.urlopen(url, timeout=10) as r:
                sys.stderr.write(f"[sim] IPN answered: {r.read().decode('utf-8', 'replace').strip()}\n")
        except (urllib.error.URLError, OSError) as e:
            sys.stderr.write(f"[sim] IPN not delivered ({e}); the return URL will still settle the payment\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--props", default=DEFAULT_PROPS, help="application-local.properties to read vnpay.tmn-code / vnpay.hash-secret from")
    ap.add_argument("--ipn-url", default="http://localhost:8080/api/v1/payments/vnpay/ipn")
    ap.add_argument("--no-ipn", action="store_true", help="do not send the server-to-server IPN (only the return URL settles the payment)")
    args = ap.parse_args()
    props = read_props(args.props)
    secret, tmn = props.get("vnpay.hash-secret"), props.get("vnpay.tmn-code")
    if not secret or not tmn:
        sys.exit(f"vnpay.tmn-code / vnpay.hash-secret not found in {os.path.normpath(args.props)}")
    Handler.secret, Handler.tmn, Handler.ipn_url = secret, tmn.strip(), "" if args.no_ipn else args.ipn_url
    print(f"VNPay simulator on http://0.0.0.0:{args.port}/pay  (merchant {tmn.strip()}; IPN {'off' if args.no_ipn else args.ipn_url})")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
