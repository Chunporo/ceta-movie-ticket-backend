#!/usr/bin/env python3
"""A stand-in for a bank-feed aggregator (SePay/Casso style) for developing VietQR payment without a bank account.

    python3 scripts/vietqr_sim.py CETA5A1 170000      # pretend a customer transferred 170.000 d with that note

In the app choose VietQR at checkout: the QR image is shown (it needs internet: img.vietqr.io), and the transfer note is
CETA<bookingId>A<attempt>, e.g. booking 5, first attempt -> CETA5A1. This script then posts the same JSON an aggregator
would to /api/v1/payments/vietqr/webhook with the key from application-local.properties (vietqr.webhook-key).
Options: --type out (money leaving: must be ignored), --account other, --url, --props.
Default style follows vietqr.vn's documented transaction-sync callback (token endpoint + Bearer); --style sepay posts the generic Apikey webhook.
"""
import argparse, json, os, random, sys, urllib.error, urllib.request

DEFAULT_PROPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "application-local.properties")


def read_props(path):
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


def payload(note, amount, account, kind):
    return {"id": random.randint(10_000, 99_999_999), "gateway": "MBBank", "transactionDate": "2026-11-18 10:00:00",
            "accountNumber": account, "transferType": kind, "transferAmount": amount,
            "content": f"MBVCB.{random.randint(1_000_000, 9_999_999)}.{note}.CT tu 0987654321", "referenceCode": f"FT{random.randint(10**9, 10**10 - 1)}"}


def call(url, body, headers):
    req = urllib.request.Request(url, json.dumps(body).encode() if body is not None else b"", {"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def vietqr_style(a, props, account):
    """What vietqr.vn documents: it gets a token from OUR token endpoint with Basic auth, then posts transaction-sync with it."""
    import base64
    user, pw = props.get("vietqr.callback-username"), props.get("vietqr.callback-password")
    if not user or not pw:
        sys.exit("vietqr.callback-username / vietqr.callback-password missing in application-local.properties")
    base = a.url.rsplit("/vietqr/", 1)[0] + "/vietqr"
    status, text = call(base + "/token", None, {"Authorization": "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode()})
    if status != 200:
        sys.exit(f"token endpoint answered {status}: {text}")
    token = json.loads(text)["access_token"]
    body = {"bankaccount": account, "amount": a.amount, "transType": "D" if a.type == "out" else "C", "content": f"{a.note} chuyen tien",
            "transactionid": f"{random.randint(10**9, 10**10 - 1)}", "transactiontime": 1763394000000, "referencenumber": f"FT{random.randint(10**9, 10**10 - 1)}",
            "orderId": a.order_id or ""}
    return call(base + "/bank/api/transaction-sync", body, {"Authorization": "Bearer " + token})


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("note"); ap.add_argument("amount", type=int)
    ap.add_argument("--type", default="in", choices=["in", "out"])
    ap.add_argument("--account"); ap.add_argument("--props", default=DEFAULT_PROPS)
    ap.add_argument("--url", default="http://localhost:8080/api/v1/payments/vietqr/webhook")
    ap.add_argument("--style", default="vietqr", choices=["vietqr", "sepay"], help="vietqr: vietqr.vn's documented callback (default); sepay: the generic Apikey webhook")
    ap.add_argument("--order-id", help="also send orderId (our txn_ref, e.g. 5_1), as VietQR does when the QR was made through its API")
    a = ap.parse_args()
    props = read_props(a.props)
    key, account = props.get("vietqr.webhook-key"), a.account or props.get("vietqr.account-no")
    if a.style == "vietqr":
        status, text = vietqr_style(a, props, account)
        print(status, text)
        sys.exit(0 if status == 200 else 1)
    if not key or not account:
        sys.exit("vietqr.webhook-key / vietqr.account-no missing in " + os.path.normpath(a.props))
    req = urllib.request.Request(a.url, json.dumps(payload(a.note, a.amount, account, a.type)).encode(),
                                 {"Content-Type": "application/json", "Authorization": "Apikey " + key})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            print(r.status, r.read().decode())
    except urllib.error.HTTPError as e:
        print(e.code, e.read().decode()); sys.exit(1)


if __name__ == "__main__":
    main()
