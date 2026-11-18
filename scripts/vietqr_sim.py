#!/usr/bin/env python3
"""A stand-in for a bank-feed aggregator (SePay/Casso style) for developing VietQR payment without a bank account.

    python3 scripts/vietqr_sim.py CETA5A1 170000      # pretend a customer transferred 170.000 d with that note

In the app choose VietQR at checkout: the QR image is shown (it needs internet: img.vietqr.io), and the transfer note is
CETA<bookingId>A<attempt>, e.g. booking 5, first attempt -> CETA5A1. This script then posts the same JSON an aggregator
would to /api/v1/payments/vietqr/webhook with the key from application-local.properties (vietqr.webhook-key).
Options: --type out (money leaving: must be ignored), --account other, --url, --props.
The payload follows SePay's documented webhook as recalled; real credentials are the only way to confirm it.
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


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("note"); ap.add_argument("amount", type=int)
    ap.add_argument("--type", default="in", choices=["in", "out"])
    ap.add_argument("--account"); ap.add_argument("--props", default=DEFAULT_PROPS)
    ap.add_argument("--url", default="http://localhost:8080/api/v1/payments/vietqr/webhook")
    a = ap.parse_args()
    props = read_props(a.props)
    key, account = props.get("vietqr.webhook-key"), a.account or props.get("vietqr.account-no")
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
