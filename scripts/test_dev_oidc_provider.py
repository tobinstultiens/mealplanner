#!/usr/bin/env python3
"""
Self-test of scripts/dev-oidc-provider.py, the stand-in for Authelia: the login as the app makes it (authorization
code with PKCE, then renewals), the token's signature against the published key, and the users and groups it is
started with.

    python3 scripts/test_dev_oidc_provider.py

Needs what the provider needs (`cryptography`); not run by CI.
"""
import base64
import hashlib
import importlib.util
import json
import os
import secrets
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

# Loaded from its file (its name is no module name), without leaving a __pycache__ in scripts/.
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("dev_oidc_provider", os.path.join(os.path.dirname(__file__), "dev-oidc-provider.py"))
dev = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dev)


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def b64decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class DevProviderTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), dev.Provider)
        dev.Provider.issuer = f"http://127.0.0.1:{self.server.server_address[1]}"
        dev.Provider.users, dev.Provider.users_file, dev.Provider.default_groups = None, None, ["mealplanner"]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.opener = urllib.request.build_opener(NoRedirects)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def post(self, path, form):
        request = urllib.request.Request(dev.Provider.issuer + path, urllib.parse.urlencode(form).encode(), method="POST")
        try:
            response = self.opener.open(request, timeout=10)
            return response.status, response.headers, response.read()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.headers, e.read()

    def log_in(self, username, scope="openid profile email offline_access groups"):
        """The app's login: the answer of the token endpoint, or the status the login page refused it with."""
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        redirect = "http://localhost:8080/app/login"
        status, headers, _ = self.post("/authorize", {"client_id": "mealplanner", "redirect_uri": redirect, "scope": scope,
                                                      "state": "s", "code_challenge": challenge, "username": username})
        if status != 302:
            return status
        code = urllib.parse.parse_qs(urllib.parse.urlparse(headers["Location"]).query)["code"][0]
        status, _, body = self.post("/token", {"grant_type": "authorization_code", "code": code, "redirect_uri": redirect,
                                               "client_id": "mealplanner", "code_verifier": verifier})
        self.assertEqual(200, status, body)
        return json.loads(body)

    def renew(self, tokens):
        status, _, body = self.post("/token", {"grant_type": "refresh_token", "refresh_token": tokens["refresh_token"], "client_id": "mealplanner"})
        return json.loads(body) if status == 200 else status

    def claims(self, token):
        """The access token's claims, after checking its signature with the key the provider publishes."""
        header, body, signature = token.split(".")
        with urllib.request.urlopen(dev.Provider.issuer + "/jwks.json", timeout=10) as response:
            key = json.loads(response.read())["keys"][0]
        public = rsa.RSAPublicNumbers(int.from_bytes(b64decode(key["e"]), "big"), int.from_bytes(b64decode(key["n"]), "big")).public_key()
        public.verify(b64decode(signature), f"{header}.{body}".encode(), padding.PKCS1v15(), hashes.SHA256())
        self.assertEqual("RS256", json.loads(b64decode(header))["alg"])
        return json.loads(b64decode(body))

    def test_anyone_logs_in_in_the_default_groups(self):
        claims = self.claims(self.log_in("tester")["access_token"])
        self.assertEqual("dev-tester", claims["sub"])
        self.assertEqual(["mealplanner"], claims["groups"])

    def test_the_groups_claim_comes_only_with_the_groups_scope(self):
        self.assertNotIn("groups", self.claims(self.log_in("tester", scope="openid profile offline_access")["access_token"]))

    def test_a_member_and_a_non_member(self):
        dev.Provider.users = {"anna": ["family", "mealplanner"], "eve": ["family"], "bob": []}
        self.assertEqual(["family", "mealplanner"], self.claims(self.log_in("anna")["access_token"])["groups"])
        self.assertEqual(["family"], self.claims(self.log_in("eve")["access_token"])["groups"])
        self.assertEqual([], self.claims(self.log_in("bob")["access_token"])["groups"])
        self.assertEqual(403, self.log_in("mallory"), "only the users given may log in")

    def test_the_file_is_read_again_at_each_renewal(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"anna": ["mealplanner"]}, f)
        try:
            dev.Provider.users_file = f.name
            tokens = self.log_in("anna")
            self.assertEqual(["mealplanner"], self.claims(tokens["access_token"])["groups"])
            # Taken out of the group: the renewed token says so.
            with open(f.name, "w") as out:
                json.dump({"anna": []}, out)
            tokens = self.renew(tokens)
            self.assertEqual([], self.claims(tokens["access_token"])["groups"])
            # Removed altogether: no renewal any more.
            with open(f.name, "w") as out:
                json.dump({}, out)
            self.assertEqual(400, self.renew(tokens))
        finally:
            os.unlink(f.name)

    def test_users_from_flags(self):
        self.assertEqual(("anna", ["mealplanner", "family"]), dev.parse_user("anna=mealplanner,family"))
        self.assertEqual(("bob", []), dev.parse_user("bob="))
        self.assertEqual(("bob", []), dev.parse_user("bob"))


if __name__ == "__main__":
    unittest.main()
