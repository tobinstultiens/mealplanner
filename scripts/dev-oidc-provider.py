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

Users and groups, to play a member and a non-member of the server's group (MEALPLANNER_OIDC_GROUP):

    python3 scripts/dev-oidc-provider.py --user anna=mealplanner,family --user eve=family --user bob=
    python3 scripts/dev-oidc-provider.py --users users.json      # {"anna": ["mealplanner"], "eve": []}

With users given only they can log in; the file is read again for every token, a renewal too, as
Authelia reads the directory, so taking someone out of a group in it shows at their next renewal.
Without users anyone can, in the --default-groups (`mealplanner` unless set; `--default-groups ""`
for none). As with Authelia's `groups` scope and a claims policy that puts the claim in the access
token, the token has a `groups` claim only when the client asked for the `groups` scope.

It can also play the registration helper (scripts/registration-helper.py) on its own directory, so the server's
registration (issue 314) runs without LLDAP: with `--helper-secret S` it answers the helper's POST /register, /approve
and /reject (`Authorization: Bearer S`). A registered user may log in at once, in no group; approving puts them in
--registration-group (`mealplanner`), rejecting removes them. The password is not kept (the login page asks none).

    python3 scripts/dev-oidc-provider.py --user anna=mealplanner --helper-secret dev-registration-secret-0123456789
    MEALPLANNER_REGISTRATION=on MEALPLANNER_OIDC_GROUP=mealplanner MEALPLANNER_REGISTRATION_HELPER_URL=http://localhost:8090 \
      MEALPLANNER_REGISTRATION_HELPER_SECRET=dev-registration-secret-0123456789 java -jar server/build/libs/server-all.jar
"""
import argparse
import base64
import hashlib
import hmac
import html
import json
import secrets
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
CODES = {}     # code -> (client_id, redirect_uri, challenge, username, scope)
REFRESH = {}   # refresh token -> (client_id, username, scope)
REGISTERED = {}  # user name -> groups, of the users registered through the helper's routes
WAITING = set()  # those of them still waiting for approve or reject, as the helper's own list


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def int_b64(n: int) -> str:
    return b64(n.to_bytes((n.bit_length() + 7) // 8, "big"))


def jwt(claims: dict) -> str:
    header = b64(json.dumps({"alg": "RS256", "typ": "at+jwt", "kid": "dev"}).encode())
    body = b64(json.dumps(claims).encode())
    signature = KEY.sign(f"{header}.{body}".encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{header}.{body}.{b64(signature)}"


def parse_user(text: str):
    """`name=group,group` (or `name=` for no groups, `name` alone the same) as (name, [groups])."""
    name, _, groups = text.partition("=")
    if not name:
        raise argparse.ArgumentTypeError(f"no user name in '{text}'")
    return name, [group for group in groups.split(",") if group]


class Provider(BaseHTTPRequestHandler):
    issuer = ""
    lifetime = 300
    users = None          # {name: [groups]} from --user; None: anyone may log in
    users_file = None     # --users, read again for every token
    default_groups = ["mealplanner"]
    helper_secret = None  # --helper-secret; None: no helper routes
    registration_group = "mealplanner"

    @classmethod
    def directory(cls):
        """The users who may log in and their groups, as now; None when anyone may."""
        if cls.users_file is None:
            return cls.users
        with open(cls.users_file) as f:
            listed = json.load(f)
        return {**(cls.users or {}), **{name: list(groups) for name, groups in listed.items()}}

    @classmethod
    def groups_of(cls, username):
        """The user's groups now; None for a user who may not log in (any more)."""
        if username in REGISTERED:
            return list(REGISTERED[username])
        directory = cls.directory()
        if directory is None:
            return list(cls.default_groups)
        return directory.get(username)

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
                <label>Username <input name="username" value="{html.escape(self.first_user())}" autofocus></label>
                <button type="submit" id="login">Log in</button></form>""", "text/html")
        elif url.path == "/userinfo":
            claims = self.bearer_claims()
            if claims is None:
                return self.send(401, json.dumps({"error": "invalid_token"}))
            info = {"sub": claims["sub"], "preferred_username": claims["preferred_username"],
                    "name": claims["preferred_username"].title()}
            if "groups" in claims:
                info["groups"] = claims["groups"]
            self.send(200, json.dumps(info))
        else:
            self.send(404, "not found", "text/plain")

    def first_user(self):
        directory = self.directory()
        return next(iter(directory), "") if directory else "tester"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode()
        path = urllib.parse.urlparse(self.path).path
        if path in ("/register", "/approve", "/reject"):
            return self.helper(path, body)
        form = dict(urllib.parse.parse_qsl(body))
        if path == "/authorize":
            username = form.get("username") or "tester"
            if self.groups_of(username) is None:
                return self.send(403, f"Unknown user {html.escape(username)}", "text/html")
            code = secrets.token_urlsafe(24)
            CODES[code] = (form["client_id"], form["redirect_uri"], form["code_challenge"], username, form.get("scope", ""))
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
                client_id, username, scope = entry[0], entry[3], entry[4]
            elif grant == "refresh_token":
                entry = REFRESH.pop(form.get("refresh_token", ""), None)
                if entry is None or entry[0] != form.get("client_id"):
                    return self.send(400, json.dumps({"error": "invalid_grant"}))
                client_id, username, scope = entry
            else:
                return self.send(400, json.dumps({"error": "unsupported_grant_type"}))
            # Read now, for a renewal too: a user taken out of the list can no longer renew, as with Authelia.
            groups = self.groups_of(username)
            if groups is None:
                return self.send(400, json.dumps({"error": "invalid_grant"}))
            refresh = secrets.token_urlsafe(32)
            REFRESH[refresh] = (client_id, username, scope)
            now = int(time.time())
            claims = {"iss": self.issuer, "sub": f"dev-{username}", "aud": [client_id], "client_id": client_id,
                      "preferred_username": username, "iat": now, "exp": now + self.lifetime, "jti": secrets.token_hex(8)}
            if "groups" in scope.split():
                claims["groups"] = groups
            self.send(200, json.dumps({"access_token": jwt(claims), "token_type": "bearer", "expires_in": self.lifetime,
                                       "refresh_token": refresh, "scope": scope}))
        else:
            self.send(404, "not found", "text/plain")

    def helper(self, path, body):
        """The registration helper's three operations, answered as it answers them, on this stand-in's directory."""
        secret = self.helper_secret
        if not secret or not hmac.compare_digest(self.headers.get("Authorization", "").encode(), f"Bearer {secret}".encode()):
            return self.send(401, json.dumps({"error": "unauthorised"}))
        name = json.loads(body or "{}").get("username", "")
        if path == "/register":
            if name in REGISTERED or name in (self.directory() or {}):
                return self.send(409, json.dumps({"error": "taken"}))
            REGISTERED[name] = []
            WAITING.add(name)
        elif name not in WAITING:
            return self.send(404, json.dumps({"error": "unknown"}))
        elif path == "/approve":
            REGISTERED[name] = [self.registration_group]
            WAITING.discard(name)
        else:
            del REGISTERED[name]
            WAITING.discard(name)
        self.send(200, "{}")

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
    parser.add_argument("--user", type=parse_user, action="append", metavar="NAME=GROUP,GROUP",
                        help="a user who may log in, with their groups (repeat; NAME= for none)")
    parser.add_argument("--users", metavar="FILE", help='users and groups as JSON, {"anna": ["mealplanner"]}; read for every token')
    parser.add_argument("--default-groups", default="mealplanner", metavar="GROUP,GROUP",
                        help="the groups of anyone logging in when no users are given (default: mealplanner)")
    parser.add_argument("--helper-secret", help="answer the registration helper's routes for callers with this secret")
    parser.add_argument("--registration-group", default="mealplanner", help="the group approving puts a registered user in")
    args = parser.parse_args()
    Provider.issuer = args.issuer or f"http://localhost:{args.port}"
    Provider.lifetime = args.lifetime
    Provider.users = dict(args.user) if args.user else None
    Provider.users_file = args.users
    Provider.default_groups = [group for group in args.default_groups.split(",") if group]
    Provider.helper_secret = args.helper_secret
    Provider.registration_group = args.registration_group
    print(f"Dev OIDC provider at {Provider.issuer}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), Provider).serve_forever()
