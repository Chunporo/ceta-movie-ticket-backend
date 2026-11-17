"""Tests for the local VNPay simulator: python3 -m unittest scripts/test_vnpay_sim.py"""
import os, sys, threading, unittest, urllib.error, urllib.request
from http.server import ThreadingHTTPServer
from urllib.parse import parse_qsl, urlencode, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vnpay_sim as sim

SECRET, TMN = "test-secret-123", "TESTTMN1"


def payment_query(**override):
    params = {
        "vnp_Version": "2.1.0", "vnp_Command": "pay", "vnp_TmnCode": TMN, "vnp_Amount": "17000000", "vnp_CurrCode": "VND",
        "vnp_TxnRef": "42_1", "vnp_OrderInfo": "Dat ve phim 42_1", "vnp_OrderType": "other", "vnp_Locale": "vn",
        "vnp_ReturnUrl": "http://10.0.2.2:8080/api/v1/payments/vnpay/return", "vnp_IpAddr": "127.0.0.1",
        "vnp_CreateDate": "20261117220000", "vnp_ExpireDate": "20991231235959",
    }
    params.update(override)
    params["vnp_SecureHash"] = sim.sign(params, SECRET)
    # The backend builds the query with the same Java-style encoding.
    return "&".join(f"{sim.java_encode(k)}={sim.java_encode(v)}" for k, v in sorted(params.items()) if k != "vnp_SecureHash") + "&vnp_SecureHash=" + params["vnp_SecureHash"]


class Signing(unittest.TestCase):
    def test_encoding_matches_javas_urlencoder(self):
        # Verified against java.net.URLEncoder.encode(value, "US-ASCII").
        self.assertEqual("a+b%7E*%3A%2F-_.%26%3D%2B%25%21%27%28%29", sim.java_encode("a b~*:/-_.&=+%!'()"))

    def test_sign_then_verify_and_any_change_breaks_it(self):
        params = dict(parse_qsl(payment_query()))
        self.assertTrue(sim.verify(params, SECRET))
        self.assertFalse(sim.verify({**params, "vnp_Amount": "100"}, SECRET))
        self.assertFalse(sim.verify(params, "another-secret"))
        self.assertFalse(sim.verify({k: v for k, v in params.items() if k != "vnp_SecureHash"}, SECRET))

    def test_the_return_carries_a_valid_signature_and_the_right_status(self):
        params = dict(parse_qsl(payment_query()))
        ok = sim.build_return(params, "ok", SECRET)
        self.assertTrue(sim.verify(ok, SECRET))
        self.assertEqual(("00", "00", "42_1", "17000000"), (ok["vnp_ResponseCode"], ok["vnp_TransactionStatus"], ok["vnp_TxnRef"], ok["vnp_Amount"]))
        failed = sim.build_return(params, "funds", SECRET)
        self.assertEqual(("51", "02"), (failed["vnp_ResponseCode"], failed["vnp_TransactionStatus"]))
        self.assertTrue(sim.verify(failed, SECRET))


class Server(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sim.Handler.secret, sim.Handler.tmn, sim.Handler.ipn_url = SECRET, TMN, ""
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), sim.Handler)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def get(self, path):
        try:
            with urllib.request.urlopen(self.base + path) as r:
                return r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    def test_a_valid_payment_url_shows_the_order_and_amount(self):
        status, page = self.get("/pay?" + payment_query())
        self.assertEqual(200, status)
        self.assertIn("42_1", page)
        self.assertIn("170.000", page)

    def test_a_tampered_url_is_refused_like_vnpays_70(self):
        status, page = self.get("/pay?" + payment_query().replace("17000000", "100"))
        self.assertEqual(400, status)
        self.assertIn("70", page)

    def test_a_foreign_merchant_and_an_expired_payment_are_refused(self):
        self.assertEqual(400, self.get("/pay?" + payment_query(vnp_TmnCode="OTHER"))[0])
        status, page = self.get("/pay?" + payment_query(vnp_ExpireDate="20000101000000"))
        self.assertEqual(400, status)
        self.assertIn("hết hạn", page)

    def test_finishing_redirects_to_the_return_url_with_a_signed_result(self):
        query = payment_query()
        body = urlencode({"q": query, "outcome": "ok"}).encode()

        class NoFollow(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None

        opener = urllib.request.build_opener(NoFollow)
        with self.assertRaises(urllib.error.HTTPError) as raised:
            opener.open(urllib.request.Request(self.base + "/finish", data=body, method="POST"))
        self.assertEqual(302, raised.exception.code)
        target = urlparse(raised.exception.headers["Location"])
        self.assertEqual("/api/v1/payments/vnpay/return", target.path)
        back = dict(parse_qsl(target.query))
        self.assertTrue(sim.verify(back, SECRET))
        self.assertEqual("00", back["vnp_ResponseCode"])
        self.assertEqual("42_1", back["vnp_TxnRef"])


if __name__ == "__main__":
    unittest.main()
