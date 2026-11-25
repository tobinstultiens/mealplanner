#!/usr/bin/env python3
"""
The registration helper (issue 314): a small service next to LLDAP that makes the accounts people ask for in the meal
planner, so that the one LLDAP admin credential it needs is held here and not by the meal planner server. It does three
things, and only for a caller that sends the shared secret (`Authorization: Bearer <HELPER_SECRET>`):

    POST /register  {"username", "displayName", "email", "password"}  makes the user, in no group, with that password
    POST /approve   {"username"}  puts the user in HELPER_GROUP
    POST /reject    {"username"}  deletes the user

LLDAP cannot narrow an admin's rights, so the narrowing is here: the group comes from the configuration, never from a
request, and is none of LLDAP's own (`lldap_*`); approving and deleting work only on user names this helper made itself
and that are still in no group (LLDAP records no creator, so it keeps that list in HELPER_STATE); a user whose password
cannot be set is deleted at once. It never logs a request body: only the action, the user name and the outcome.

Answers: 200 `{}`; 400 {"error": "invalid"}; 401 without the secret; 404 {"error": "unknown"}: a user name it did not
make, or one that waits no longer; 409 {"error": "taken"}: the user name or e-mail address is in use (register), or
{"error": "in_group"}: the user is in a group already, so no longer its to change; 502 {"error": "directory"}: LLDAP
failed and nothing was left behind; 500: it is not sure what was left (the meal planner server then rejects the name).

Configuration, from the environment (see docs/deploy.md, "Registration"):
    HELPER_SECRET            the shared secret, 32 characters or more
    HELPER_GROUP             the group approving puts a user in, the server's MEALPLANNER_OIDC_GROUP (`mealplanner`)
    HELPER_STATE             the file of user names it made that still wait, on a volume
    HELPER_PORT              where it listens (8091)
    LLDAP_URL                LLDAP's web address, http://lldap:17170
    LLDAP_LDAP_HOST, LLDAP_LDAP_PORT   its LDAP server (lldap, 3890)
    LLDAP_BASE_DN            dc=example,dc=com
    LLDAP_ADMIN_USER, LLDAP_ADMIN_PASSWORD   a user in lldap_admin, for this helper alone

Plain Python 3, no packages: `python3 scripts/registration-helper.py`. Its test: `python3 scripts/test_registration_helper.py`.
"""
import hmac
import json
import logging
import os
import re
import socket
import tempfile
import threading
import unicodedata
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("registration-helper")

# The same rules as the meal planner's form (shared RegistrationRules), checked again here: the helper trusts no caller.
# Both sides' tests read scripts/registration-rules-cases.json, so that they accept and refuse the same strings; the
# character sets are spelled out, as Python's \s and Kotlin's are not the same.
USERNAME = re.compile(r"[a-z0-9][a-z0-9._-]{2,31}")
EMAIL = re.compile(r"[!-?A-~]+@[!-?A-~]+\.[!-?A-~]+")
FIELDS = {"/register": {"username", "displayName", "email", "password"}, "/approve": {"username"}, "/reject": {"username"}}
MAX_BODY = 4096
PASSWORD_MODIFY = b"1.3.6.1.4.1.4203.1.11.1"  # RFC 3062, the LDAP extended operation LLDAP sets passwords with


class DirectoryError(Exception):
    """LLDAP refused or could not be reached, and nothing this helper did is left behind."""


class Unsure(Exception):
    """Something went wrong after a user was made, and undoing it failed too."""


def utf16_length(text: str) -> int:
    """A text's length as Kotlin counts it, so a password is as long here as in the meal planner's check."""
    return len(text.encode("utf-16-le")) // 2


def valid(path: str, form) -> bool:
    if not isinstance(form, dict) or set(form) != FIELDS[path] or not all(isinstance(v, str) for v in form.values()):
        return False
    if not USERNAME.fullmatch(form["username"]):
        return False
    if path != "/register":
        return True
    name, email, password = form["displayName"], form["email"], form["password"]
    return (0 < utf16_length(name) <= 64 and not any(unicodedata.category(c) == "Cc" for c in name)
            and len(email) <= 254 and EMAIL.fullmatch(email) is not None
            and 10 <= utf16_length(password) <= 128 and password.lower() != form["username"])


# LDAP, as far as setting one password takes it: BER encoding (X.690) of a simple bind and the password-modify request.

def ber(tag: int, value: bytes) -> bytes:
    size = len(value)
    length = bytes([size]) if size < 0x80 else bytes([0x80 | (size.bit_length() + 7) // 8]) + size.to_bytes((size.bit_length() + 7) // 8, "big")
    return bytes([tag]) + length + value


def integer(n: int) -> bytes:
    return ber(0x02, n.to_bytes((n.bit_length() + 8) // 8, "big"))


def elements(data: bytes):
    """The (tag, value) pairs that follow each other in [data]."""
    pos = 0
    while pos < len(data):
        tag, first = data[pos], data[pos + 1]
        pos += 2
        size = first
        if first & 0x80:
            size = int.from_bytes(data[pos:pos + (first & 0x7F)], "big")
            pos += first & 0x7F
        yield tag, data[pos:pos + size]
        pos += size


def read_message(sock) -> bytes:
    """The contents of the next LDAPMessage on [sock]."""
    def exactly(count):
        data = b""
        while len(data) < count:
            chunk = sock.recv(count - len(data))
            if not chunk:
                raise DirectoryError("LDAP closed the connection")
            data += chunk
        return data
    _, first = exactly(2)
    return exactly(first if first < 0x80 else int.from_bytes(exactly(first & 0x7F), "big"))


def result_code(message: bytes) -> int:
    """The resultCode of an LDAP response: the first element of the operation that follows the message id."""
    _, operation = list(elements(message))[1]
    return int.from_bytes(next(elements(operation))[1], "big")


class Helper:
    REQUIRED = ("HELPER_SECRET", "HELPER_GROUP", "HELPER_STATE", "LLDAP_URL", "LLDAP_BASE_DN", "LLDAP_ADMIN_USER", "LLDAP_ADMIN_PASSWORD")

    def __init__(self, env):
        missing = [key for key in self.REQUIRED if not env.get(key)]
        if missing:
            raise SystemExit(f"Not set: {', '.join(missing)}")
        self.secret = env["HELPER_SECRET"]
        self.group = env["HELPER_GROUP"]
        if len(self.secret) < 32:
            raise SystemExit("HELPER_SECRET must be 32 characters or more")
        if not self.group or self.group.lower().startswith("lldap_"):
            raise SystemExit("HELPER_GROUP must name the meal planner's group, not one of LLDAP's own")
        self.state = env["HELPER_STATE"]
        self.url = env["LLDAP_URL"].rstrip("/")
        self.ldap = (env.get("LLDAP_LDAP_HOST", "lldap"), int(env.get("LLDAP_LDAP_PORT", "3890")))
        self.base_dn = env["LLDAP_BASE_DN"]
        self.admin = (env["LLDAP_ADMIN_USER"], env["LLDAP_ADMIN_PASSWORD"])
        self.lock = threading.Lock()  # one operation at a time, so two never act on the same user or the list

    # The list of user names it made that still wait, in a file so that it outlives a restart.

    def waiting(self) -> list:
        try:
            with open(self.state) as f:
                return json.load(f)["waiting"]
        except FileNotFoundError:
            return []

    def save(self, names):
        folder = os.path.dirname(os.path.abspath(self.state))
        with tempfile.NamedTemporaryFile("w", dir=folder, delete=False) as f:
            json.dump({"waiting": sorted(set(names))}, f)
        os.replace(f.name, self.state)

    def remember(self, name):
        self.save(self.waiting() + [name])

    def forget(self, name):
        self.save([n for n in self.waiting() if n != name])

    # LLDAP's web API: a token from the admin's login, then GraphQL.

    def post(self, path, body, token=None):
        headers = {"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})}
        request = urllib.request.Request(self.url + path, json.dumps(body).encode(), headers)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return json.load(response)
        except (OSError, ValueError) as e:  # an HTTPError is an OSError; its text holds the address only
            raise DirectoryError(f"{path}: {type(e).__name__}") from None

    def login(self) -> str:
        token = self.post("/auth/simple/login", {"username": self.admin[0], "password": self.admin[1]}).get("token")
        if not token:
            raise DirectoryError("LLDAP refused the admin's login")
        return token

    def graphql(self, token, operation, query, **variables):
        """The data of a GraphQL operation; None when LLDAP answered it with errors."""
        return self.graphql_answer(token, operation, query, **variables).get("data")

    def graphql_answer(self, token, operation, query, **variables) -> dict:
        """LLDAP's whole answer: `data`, or `errors` instead."""
        answer = self.post("/api/graphql", {"operationName": operation, "query": query, "variables": variables}, token)
        return {"errors": answer["errors"]} if answer.get("errors") else answer

    def find(self, token, name):
        """The user's groups, by name; None when LLDAP says it has no such user. Any other error is LLDAP failing, not
        an answer: taken for "no such user", it would make the helper forget a user it made and never delete it."""
        answer = self.graphql_answer(token, "FindUser", "query FindUser($id: String!) { user(userId: $id) { id groups { displayName } } }", id=name)
        errors = answer.get("errors")
        if errors and all("not found" in str(e.get("message", "")).lower() for e in errors):
            return None
        if errors or not answer.get("data"):
            raise DirectoryError("LLDAP failed to look up the user")
        return [g["displayName"] for g in answer["data"]["user"]["groups"]]

    def group_id(self, token) -> int:
        data = self.graphql(token, "Groups", "query Groups { groups { id displayName } }")
        ids = [g["id"] for g in (data or {}).get("groups", []) if g["displayName"] == self.group]
        if len(ids) != 1:
            raise DirectoryError(f"LLDAP has no group {self.group}")
        return ids[0]

    def delete(self, token, name):
        if self.graphql(token, "DeleteUser", "mutation DeleteUser($id: String!) { deleteUser(userId: $id) { ok } }", id=name) is None:
            raise DirectoryError("LLDAP did not delete the user")

    def set_password(self, name, password):
        """Sets the password over LDAP (LLDAP's GraphQL has no way to), bound as the admin."""
        dn = lambda user: f"uid={user},ou=people,{self.base_dn}".encode()
        with socket.create_connection(self.ldap, timeout=15) as sock:
            sock.sendall(ber(0x30, integer(1) + ber(0x60, integer(3) + ber(0x04, dn(self.admin[0])) + ber(0x80, self.admin[1].encode()))))
            if result_code(read_message(sock)) != 0:
                raise DirectoryError("LDAP refused the admin's bind")
            change = ber(0x30, ber(0x80, dn(name)) + ber(0x82, password.encode()))
            sock.sendall(ber(0x30, integer(2) + ber(0x77, ber(0x80, PASSWORD_MODIFY) + ber(0x81, change))))
            if result_code(read_message(sock)) != 0:
                raise DirectoryError("LDAP refused the password")
            sock.sendall(ber(0x30, integer(3) + ber(0x42, b"")))

    # The three operations.

    def register(self, form):
        name = form["username"]
        token = self.login()
        if self.find(token, name) is not None:
            return 409, "taken"
        user = {"id": name, "email": form["email"], "displayName": form["displayName"]}
        # Remembered first: a user it made and then could not write down would be one it may never delete.
        self.remember(name)
        try:
            created = self.graphql(token, "CreateUser", "mutation CreateUser($user: CreateUserInput!) { createUser(user: $user) { id } }", user=user)
        except DirectoryError:
            raise Unsure() from None  # it may have been made all the same; rejecting the name deletes it if so
        # After the check above, LLDAP refuses a new user for one reason the form can cause: the e-mail address is in use.
        if created is None:
            self.forget(name)
            return 409, "taken"
        try:
            self.set_password(name, form["password"])
        except (OSError, DirectoryError) as e:
            log.warning("register %s: the password was not set (%s); deleting the user", name, type(e).__name__)
            try:
                self.delete(token, name)
            except DirectoryError:
                raise Unsure() from None
            self.forget(name)
            return 502, "directory"
        return 200, None

    def approve(self, form):
        return self.on_own(form["username"], self.add_to_group)

    def reject(self, form):
        return self.on_own(form["username"], self.delete)

    def add_to_group(self, token, name):
        query = "mutation AddUserToGroup($id: String!, $group: Int!) { addUserToGroup(userId: $id, groupId: $group) { ok } }"
        if self.graphql(token, "AddUserToGroup", query, id=name, group=self.group_id(token)) is None:
            raise DirectoryError("LLDAP did not add the user to the group")

    def on_own(self, name, change):
        """Does [change] to [name] only if it is a user this helper made that is still in no group, then forgets it.
        A name that is not is forgotten too: once in a group, or gone, it is no longer this helper's to change."""
        if name not in self.waiting():
            return 404, "unknown"
        token = self.login()
        groups = self.find(token, name)
        if groups is None or groups:
            self.forget(name)
            return (404, "unknown") if groups is None else (409, "in_group")
        change(token, name)
        self.forget(name)
        return 200, None


class Handler(BaseHTTPRequestHandler):
    helper: Helper = None

    def answer(self, status, error=None):
        data = json.dumps({"error": error} if error else {}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        expected = f"Bearer {self.helper.secret}".encode()
        if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), expected):
            return self.answer(401, "unauthorised")
        if self.path not in FIELDS:
            return self.answer(404, "no such operation")
        length = self.headers.get("Content-Length", "0")
        if not length.isdigit() or int(length) > MAX_BODY:
            return self.answer(400, "invalid")
        try:
            form = json.loads(self.rfile.read(int(length)))
        except ValueError:
            return self.answer(400, "invalid")
        if not valid(self.path, form):
            return self.answer(400, "invalid")
        action = self.path.lstrip("/")
        with self.helper.lock:
            try:
                status, error = getattr(self.helper, action)(form)
            except DirectoryError as e:
                status, error = 502, "directory"
                log.warning("%s %s: %s", action, form["username"], e)
            except Exception as e:  # noqa: BLE001 - whatever it was, say only its kind: a message may quote what was sent
                status, error = 500, "unsure"
                log.error("%s %s failed: %s", action, form["username"], type(e).__name__)
        log.info("%s %s: %d%s", action, form["username"], status, f" {error}" if error else "")
        self.answer(status, error)

    def do_GET(self):
        self.answer(405, "only POST")

    def log_message(self, format, *args):
        """The request line only (no body ever is), through the logger, for a refused caller too."""
        log.info("%s %s", self.address_string(), format % args)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    Handler.helper = Helper(os.environ)
    port = int(os.environ.get("HELPER_PORT", "8091"))
    log.info("Registration helper on port %d, approving into %s", port, Handler.helper.group)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
