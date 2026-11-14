#!/usr/bin/env python3
"""
A stand-in for Authelia when running the meal planner locally: a tiny OpenID Connect provider
with the authorization code flow + PKCE, refresh tokens and RS256 JWT access tokens, enough for
the app, the web client and the server to log in the way they do in production.

    python3 scripts/dev-oidc-provider.py                # http://localhost:8090
    MEALPLANNER_OIDC_ISSUER=http://localhost:8090 java -jar server/build/libs/server-all.jar

The login page asks only for a username (no password): development only, never expose it.
On an emulator, `adb reverse tcp:8090 tcp:8090` and `adb reverse tcp:8080 tcp:8080` make both
reachable as localhost, so the issuer is the same for the app and the server.
"""
import argparse
import base64
import hashlib
import html
import json
import secrets
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
CODES = {}     # code -> (client_id, redirect_uri, challenge, username)
REFRESH = {}   # refresh token -> (client_id, username)


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def int_b64(n: int) -> str:
    return b64(n.to_bytes((n.bit_length() + 7) // 8, "big"))


def jwt(claims: dict) -> str:
    header = b64(json.dumps({"alg": "RS256", "typ": "at+jwt", "kid": "dev"}).encode())
    body = b64(json.dumps(claims).encode())
    signature = KEY.sign(f"{header}.{body}".encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{header}.{body}.{b64(signature)}"


class Provider(BaseHTTPRequestHandler):
    issuer = ""
    lifetime = 300

    def send(self, status, body, content_type="application/json", headers=None):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send(204, b"")

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        query = dict(urllib.parse.parse_qsl(url.query))
        if url.path == "/.well-known/openid-configuration":
            self.send(200, json.dumps({
                "issuer": self.issuer,
                "authorization_endpoint": f"{self.issuer}/authorize",
                "token_endpoint": f"{self.issuer}/token",
                "userinfo_endpoint": f"{self.issuer}/userinfo",
                "jwks_uri": f"{self.issuer}/jwks.json",
                "response_types_supported": ["code"],
                "code_challenge_methods_supported": ["S256"],
            }))
        elif url.path == "/jwks.json":
            numbers = KEY.public_key().public_numbers()
            self.send(200, json.dumps({"keys": [{"kty": "RSA", "kid": "dev", "use": "sig", "alg": "RS256",
                                                  "n": int_b64(numbers.n), "e": int_b64(numbers.e)}]}))
        elif url.path == "/authorize":
            if query.get("code_challenge_method") != "S256" or not query.get("code_challenge"):
                return self.send(400, "PKCE (S256) is required", "text/plain")
            hidden = "".join(f'<input type="hidden" name="{html.escape(k)}" value="{html.escape(v)}">' for k, v in query.items())
            self.send(200, f"""<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Dev login</title><body style="font-family:sans-serif;margin:2rem">
                <h1>Dev login</h1><p>Client: {html.escape(query.get("client_id", ""))}</p>
                <form method="post" action="/authorize">{hidden}
                <label>Username <input name="username" value="tester" autofocus></label>
                <button type="submit" id="login">Log in</button></form>""", "text/html")
        elif url.path == "/userinfo":
            claims = self.bearer_claims()
            if claims is None:
                return self.send(401, json.dumps({"error": "invalid_token"}))
            self.send(200, json.dumps({"sub": claims["sub"], "preferred_username": claims["preferred_username"],
                                       "name": claims["preferred_username"].title()}))
        else:
            self.send(404, "not found", "text/plain")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        form = dict(urllib.parse.parse_qsl(self.rfile.read(length).decode()))
        path = urllib.parse.urlparse(self.path).path
        if path == "/authorize":
            code = secrets.token_urlsafe(24)
            CODES[code] = (form["client_id"], form["redirect_uri"], form["code_challenge"], form.get("username") or "tester")
            target = form["redirect_uri"] + ("&" if "?" in form["redirect_uri"] else "?") + urllib.parse.urlencode(
                {"code": code, "state": form.get("state", "")})
            self.send(302, b"", headers={"Location": target})
        elif path == "/token":
            grant = form.get("grant_type")
            if grant == "authorization_code":
                entry = CODES.pop(form.get("code", ""), None)
                verifier = form.get("code_verifier", "")
                if entry is None or entry[1] != form.get("redirect_uri") or entry[0] != form.get("client_id") \
                        or b64(hashlib.sha256(verifier.encode()).digest()) != entry[2]:
                    return self.send(400, json.dumps({"error": "invalid_grant"}))
                client_id, username = entry[0], entry[3]
            elif grant == "refresh_token":
                entry = REFRESH.pop(form.get("refresh_token", ""), None)
                if entry is None or entry[0] != form.get("client_id"):
                    return self.send(400, json.dumps({"error": "invalid_grant"}))
                client_id, username = entry
            else:
                return self.send(400, json.dumps({"error": "unsupported_grant_type"}))
            refresh = secrets.token_urlsafe(32)
            REFRESH[refresh] = (client_id, username)
            now = int(time.time())
            access = jwt({"iss": self.issuer, "sub": f"dev-{username}", "aud": [client_id], "client_id": client_id,
                          "preferred_username": username, "iat": now, "exp": now + self.lifetime, "jti": secrets.token_hex(8)})
            self.send(200, json.dumps({"access_token": access, "token_type": "bearer", "expires_in": self.lifetime,
                                       "refresh_token": refresh, "scope": "openid profile offline_access"}))
        else:
            self.send(404, "not found", "text/plain")

    def bearer_claims(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return None
        try:
            body = auth[7:].split(".")[1]
            return json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
        except (IndexError, ValueError):
            return None


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--issuer", default=None, help="defaults to http://localhost:PORT")
    parser.add_argument("--lifetime", type=int, default=300, help="access token lifetime in seconds")
    args = parser.parse_args()
    Provider.issuer = args.issuer or f"http://localhost:{args.port}"
    Provider.lifetime = args.lifetime
    print(f"Dev OIDC provider at {Provider.issuer}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Provider).serve_forever()
