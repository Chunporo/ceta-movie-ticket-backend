"""Tests for the local MoMo simulator: python3 -m unittest scripts/test_momo_sim.py"""
import json, os, sys, threading, unittest, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlencode, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import momo_sim as sim

PARTNER, ACCESS, SECRET = "MOMOTEST", "access-t", "secret-t"


def create_body(**override):
    values = {"partnerCode": PARTNER, "requestId": "req-1", "amount": "170000", "orderId": "42_1", "orderInfo": "Dat ve phim #42",
              "redirectUrl": "http://10.0.2.2:8080/api/v1/payments/momo/return", "ipnUrl": "", "requestType": "captureWallet", "extraData": ""}
    values.update(override)
    body = {**values, "amount": int(values["amount"]), "lang": "vi", "autoCapture": True}
    body["signature"] = sim.sign(values, sim.CREATE_FIELDS, ACCESS, SECRET)
    return body


class Signing(unittest.TestCase):
    def test_signature_is_hmac_sha256_of_the_fixed_field_order(self):
        # Independent check of the raw string and the digest.
        import hashlib, hmac
        values = {"amount": "1", "orderId": "o"}
        raw = "accessKey=access-t&amount=1&extraData=&message=&orderId=o&orderInfo=&orderType=&partnerCode=&payType=&requestId=&responseTime=&resultCode=&transId="
        self.assertEqual(hmac.new(b"secret-t", raw.encode(), hashlib.sha256).hexdigest(), sim.sign(values, sim.CALLBACK_FIELDS, ACCESS, SECRET))

    def test_any_changed_value_changes_the_signature(self):
        a = sim.sign({"amount": "1"}, sim.CALLBACK_FIELDS, ACCESS, SECRET)
        self.assertNotEqual(a, sim.sign({"amount": "2"}, sim.CALLBACK_FIELDS, ACCESS, SECRET))
        self.assertNotEqual(a, sim.sign({"amount": "1"}, sim.CALLBACK_FIELDS, ACCESS, "other-secret"))


class Server(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sim.Handler.partner, sim.Handler.access, sim.Handler.secret = PARTNER, ACCESS, SECRET
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), sim.Handler)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        sim.Handler.public_host = cls.base.removeprefix("http://")
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

        # A fake backend IPN endpoint that records what MoMo would have posted and answers 204.
        cls.ipns = []
        outer = cls

        class Ipn(BaseHTTPRequestHandler):
            def do_POST(self):
                outer.ipns.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(204)
                self.end_headers()

            def log_message(self, *a):
                pass

        cls.ipn_server = ThreadingHTTPServer(("127.0.0.1", 0), Ipn)
        cls.ipn_url = f"http://127.0.0.1:{cls.ipn_server.server_address[1]}/ipn"
        threading.Thread(target=cls.ipn_server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.ipn_server.shutdown()

    def post_json(self, path, body):
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read())

    def test_a_signed_create_returns_a_pay_url_and_the_page_shows_the_order(self):
        answer = self.post_json("/v2/gateway/api/create", create_body(orderId="42_1", ipnUrl=self.ipn_url))
        self.assertEqual(0, answer["resultCode"])
        self.assertTrue(answer["payUrl"].endswith("/pay?orderId=42_1"))
        with urllib.request.urlopen(answer["payUrl"]) as page:
            html_text = page.read().decode()
        self.assertIn("42_1", html_text)
        self.assertIn("170.000", html_text)

    def test_a_tampered_or_foreign_create_is_refused(self):
        body = create_body(orderId="42_2")
        body["amount"] = 100
        self.assertEqual(13, self.post_json("/v2/gateway/api/create", body)["resultCode"])
        self.assertEqual(13, self.post_json("/v2/gateway/api/create", create_body(partnerCode="OTHER"))["resultCode"])

    def test_finishing_posts_a_signed_ipn_and_redirects_with_the_same_signed_result(self):
        self.post_json("/v2/gateway/api/create", create_body(orderId="42_3", ipnUrl=self.ipn_url))

        class NoFollow(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None

        req = urllib.request.Request(self.base + "/finish", data=urlencode({"orderId": "42_3", "outcome": "ok"}).encode(), method="POST")
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.build_opener(NoFollow).open(req)
        self.assertEqual(302, raised.exception.code)
        target = urlparse(raised.exception.headers["Location"])
        self.assertEqual("/api/v1/payments/momo/return", target.path)
        redirect = dict(parse_qsl(target.query))
        ipn = next(i for i in self.ipns if i["orderId"] == "42_3")

        for params in (redirect, {k: str(v) for k, v in ipn.items()}):
            self.assertEqual(params["signature"], sim.sign(params, sim.CALLBACK_FIELDS, ACCESS, SECRET))
            self.assertEqual("0", params["resultCode"])
            self.assertEqual("170000", params["amount"])
        self.assertIsInstance(ipn["amount"], int, "numbers go out as JSON numbers, like MoMo")


if __name__ == "__main__":
    unittest.main()
