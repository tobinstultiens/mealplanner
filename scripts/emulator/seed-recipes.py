#!/usr/bin/env python3
"""
Give a test account on a local server recipes that have amounts, so that planning, the shopping
list and the stock have numbers to check. Logs in through `scripts/dev-oidc-provider.py` as the
given user (the same way the app does, with PKCE) and calls the server's API.

    python3 scripts/emulator/seed-recipes.py                       # user "tester", localhost:8080/8090
    python3 scripts/emulator/seed-recipes.py --user tester --server http://localhost:18081
    python3 scripts/emulator/seed-recipes.py --no-profile          # leave the profile alone
    python3 scripts/emulator/seed-recipes.py --self-test           # against a stub, no server needed

Eight recipes (four to six ingredients each, all with a gram, ml or piece amount) and, unless
--no-profile, a made-up profile (a 35-year-old man, 80 kg, 180 cm, moderately active, holding his
weight), so targets exist. Run it once per database: recipes are added, not replaced. Use it only
on a server you started yourself, on a copy of a database.
"""
import argparse
import base64
import hashlib
import json
import secrets
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

PROFILE = {"weightKg": 80, "heightCm": 180, "ageYears": 35, "sex": "MALE",
           "activity": "MODERATE", "goalKgPerWeek": 0}

# (title, servings, ingredient lines "amount | unit | name")
RECIPES = [
    ("Chili con carne", 4, "500 | g | minced beef\n400 | g | kidney beans\n200 | g | onion\n300 | g | tomato\n"),
    ("Chicken curry", 4, "600 | g | chicken breast\n300 | ml | coconut milk\n200 | g | rice\n150 | g | onion\n"),
    ("Lentil stew", 4, "300 | g | lentils\n200 | g | carrot\n150 | g | onion\n100 | g | potato\n"),
    ("Pasta bolognese", 4, "300 | g | pasta\n400 | g | minced beef\n300 | g | tomato\n"),
    ("Veggie stir fry", 4, "400 | g | broccoli\n200 | g | rice\n150 | g | carrot\n"),
    ("Salmon with potatoes", 2, "300 | g | salmon\n600 | g | potato\n200 | g | broccoli\n"),
    ("Egg fried rice", 4, "300 | g | rice\n6 | | egg\n200 | g | peas\n"),
    ("Tomato soup", 4, "800 | g | tomato\n200 | g | onion\n100 | g | potato\n"),
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def token(issuer: str, user: str) -> str:
    """An access token for `user` from the dev provider: authorization code flow with PKCE."""
    verifier = secrets.token_urlsafe(40)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    redirect = "http://localhost/callback"
    form = urllib.parse.urlencode({"client_id": "mealplanner", "redirect_uri": redirect,
                                   "code_challenge": challenge, "code_challenge_method": "S256",
                                   "username": user, "state": "s"}).encode()
    try:
        urllib.request.build_opener(NoRedirect).open(issuer + "/authorize", form)
        raise RuntimeError("the provider did not redirect: is it the dev stand-in on " + issuer + "?")
    except urllib.error.HTTPError as redirected:
        location = redirected.headers["Location"]
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)["code"][0]
    form = urllib.parse.urlencode({"grant_type": "authorization_code", "code": code,
                                   "redirect_uri": redirect, "client_id": "mealplanner",
                                   "code_verifier": verifier}).encode()
    return json.load(urllib.request.urlopen(issuer + "/token", form))["access_token"]


def call(server: str, bearer: str, method: str, path: str, body=None):
    request = urllib.request.Request(
        server + path, method=method, data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + bearer, "Content-Type": "application/json"})
    try:
        return 200, urllib.request.urlopen(request).read().decode()
    except urllib.error.HTTPError as failed:
        return failed.code, failed.read().decode()


def seed(server: str, issuer: str, user: str, with_profile: bool) -> int:
    bearer = token(issuer, user)
    status, _ = call(server, bearer, "GET", "/api/me")
    if status != 200:
        print(f"GET /api/me answered {status}: is the server using issuer {issuer}?", file=sys.stderr)
        return 1
    if with_profile:
        status, text = call(server, bearer, "PUT", "/api/profile", PROFILE)
        print("profile", status, "" if status == 200 else text)
    failed = 0
    for title, servings, ingredients in RECIPES:
        status, text = call(server, bearer, "POST", "/api/recipes", {
            "title": title, "servings": servings, "ingredients": ingredients,
            "instructions": "Cook it.", "mealSlots": ["LUNCH", "DINNER"]})
        print(title, status, "" if status == 200 else text)
        failed += status != 200
    return 1 if failed else 0


def self_test() -> int:
    """A stub that answers as the provider and the server, to check the calls seed() makes."""
    seen = []

    class Stub(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, status, body=b"{}", headers=()):
            self.send_response(status)
            for name, value in headers:
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def handle_any(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
            if self.path == "/authorize":
                form = urllib.parse.parse_qs(raw)
                seen.append(("authorize", form["username"][0], form["code_challenge_method"][0]))
                self.reply(302, headers=[("Location", "http://localhost/callback?code=abc&state=s")])
            elif self.path == "/token":
                form = urllib.parse.parse_qs(raw)
                seen.append(("token", form["code"][0], bool(form["code_verifier"][0])))
                self.reply(200, json.dumps({"access_token": "tok"}).encode())
            else:
                seen.append((self.command, self.path, self.headers["Authorization"], json.loads(raw) if raw else None))
                self.reply(200)

        do_GET = do_POST = do_PUT = handle_any

    httpd = HTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_port}"
    try:
        assert seed(base, base, "someone", True) == 0
        assert seen[0] == ("authorize", "someone", "S256"), seen[0]
        assert seen[1] == ("token", "abc", True), seen[1]
        assert seen[2][:3] == ("GET", "/api/me", "Bearer tok"), seen[2]
        assert seen[3][:2] == ("PUT", "/api/profile") and seen[3][3] == PROFILE
        posts = [s for s in seen if s[:2] == ("POST", "/api/recipes")]
        assert [p[3]["title"] for p in posts] == [r[0] for r in RECIPES]
        assert all(p[3]["ingredients"].count("|") >= 4 for p in posts), "every recipe has amounts"
        seen.clear()
        assert seed(base, base, "someone", False) == 0
        assert not any(s[:2] == ("PUT", "/api/profile") for s in seen), "--no-profile leaves the profile"
    finally:
        httpd.shutdown()
    print("self-test ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", default="http://localhost:8080")
    parser.add_argument("--issuer", default="http://localhost:8090")
    parser.add_argument("--user", default="tester")
    parser.add_argument("--no-profile", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return seed(args.server.rstrip("/"), args.issuer.rstrip("/"), args.user, not args.no_profile)


if __name__ == "__main__":
    sys.exit(main())
