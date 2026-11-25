#!/usr/bin/env python3
"""
Self-test of scripts/registration-helper.py against a fake LLDAP: its GraphQL API over HTTP and its LDAP server, as far
as the helper uses them (the admin's login, users, groups, and a bind with the password-modify operation).

    python3 -I scripts/test_registration_helper.py

Plain Python, no packages; run by CI (the `scripts` job).
"""
import importlib.util
import json
import logging
import os
import socketserver
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Loaded from its file (its name is no module name), without leaving a __pycache__ in scripts/.
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("registration_helper", os.path.join(os.path.dirname(os.path.abspath(__file__)), "registration-helper.py"))
helper = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(helper)

SECRET = "s" * 40
ADMIN = ("registration", "admin-password")
BASE_DN = "dc=example,dc=com"
PASSWORD = "Correct-horse-42"


class Lldap:
    """The directory both fakes share: users with their groups and passwords, LLDAP's groups, and what to fail."""

    def __init__(self):
        self.users = {"admin": {"email": "admin@example.com", "groups": ["lldap_admin"]}}
        self.groups = [{"id": 1, "displayName": "lldap_admin"}, {"id": 2, "displayName": "family"}, {"id": 3, "displayName": "mealplanner"}]
        self.passwords = {}
        self.refuse_password = False
        self.binds = []
        self.fail_once = set()  # GraphQL operations that answer an error other than "not found", once each


class FakeWeb(BaseHTTPRequestHandler):
    lldap: Lldap = None

    def log_message(self, *args):
        pass

    def send_json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/auth/simple/login":
            if (body["username"], body["password"]) != ADMIN:
                return self.send_json(401, {"error": "wrong password"})
            return self.send_json(200, {"token": "admin-token", "refreshToken": "r"})
        if self.headers.get("Authorization") != "Bearer admin-token":
            return self.send_json(401, {"error": "no token"})
        self.send_json(200, self.graphql(body["operationName"], body["variables"]))

    def graphql(self, operation, v):
        users, error = self.lldap.users, {"errors": [{"message": "Entity not found"}], "data": None}
        if operation in self.lldap.fail_once:
            self.lldap.fail_once.discard(operation)
            return {"errors": [{"message": "database is locked"}], "data": None}
        if operation == "FindUser":
            user = users.get(v["id"])
            return error if user is None else {"data": {"user": {"id": v["id"], "groups": [{"displayName": g} for g in user["groups"]]}}}
        if operation == "CreateUser":
            u = v["user"]
            if u["id"] in users or any(x["email"] == u["email"] for x in users.values()):
                return {"errors": [{"message": "UNIQUE constraint failed"}], "data": None}
            users[u["id"]] = {"email": u["email"], "displayName": u["displayName"], "groups": []}
            return {"data": {"createUser": {"id": u["id"]}}}
        if operation == "Groups":
            return {"data": {"groups": self.lldap.groups}}
        if operation == "AddUserToGroup":
            name = next(g["displayName"] for g in self.lldap.groups if g["id"] == v["group"])
            users[v["id"]]["groups"].append(name)
            return {"data": {"addUserToGroup": {"ok": True}}}
        if operation == "DeleteUser":
            return error if users.pop(v["id"], None) is None else {"data": {"deleteUser": {"ok": True}}}
        raise AssertionError(f"the helper asked {operation}")


class FakeLdap(socketserver.BaseRequestHandler):
    """LLDAP's LDAP server for a simple bind and the password-modify operation (RFC 3062), read with the helper's decoder."""
    lldap: Lldap = None

    def respond(self, message_id, tag, code):
        self.request.sendall(helper.ber(0x30, helper.integer(message_id) + helper.ber(tag, helper.ber(0x0A, bytes([code])) + helper.ber(0x04, b"") * 2)))

    def handle(self):
        bound = None
        while True:
            try:
                message = list(helper.elements(helper.read_message(self.request)))
            except helper.DirectoryError:
                return
            message_id, (tag, operation) = int.from_bytes(message[0][1], "big"), message[1]
            parts = list(helper.elements(operation))
            if tag == 0x60:  # bind: version, name, simple password
                bound = (parts[1][1].decode(), parts[2][1].decode())
                self.lldap.binds.append(bound[0])
                self.respond(message_id, 0x61, 0 if bound == (f"uid={ADMIN[0]},ou=people,{BASE_DN}", ADMIN[1]) else 49)
            elif tag == 0x77:  # extended: its name, then the request value, a sequence of the user and the new password
                assert parts[0][1] == helper.PASSWORD_MODIFY
                sequence_tag, sequence = next(helper.elements(parts[1][1]))
                assert sequence_tag == 0x30
                change = dict(helper.elements(sequence))
                user = change[0x80].decode().removeprefix("uid=").removesuffix(f",ou=people,{BASE_DN}")
                if bound is None or self.lldap.refuse_password or user not in self.lldap.users:
                    self.respond(message_id, 0x78, 50)
                else:
                    self.lldap.passwords[user] = change[0x82].decode()
                    self.respond(message_id, 0x78, 0)
            elif tag == 0x42:
                return


class Captured(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines = []

    def emit(self, record):
        self.lines.append(record.getMessage())


class RegistrationHelperTest(unittest.TestCase):
    def setUp(self):
        self.lldap = Lldap()
        FakeWeb.lldap = FakeLdap.lldap = self.lldap
        self.web = ThreadingHTTPServer(("127.0.0.1", 0), FakeWeb)
        self.ldap = socketserver.ThreadingTCPServer(("127.0.0.1", 0), FakeLdap)
        self.state = os.path.join(tempfile.mkdtemp(), "waiting.json")
        helper.Handler.helper = helper.Helper({
            "HELPER_SECRET": SECRET, "HELPER_GROUP": "mealplanner", "HELPER_STATE": self.state,
            "LLDAP_URL": f"http://127.0.0.1:{self.web.server_address[1]}", "LLDAP_LDAP_HOST": "127.0.0.1",
            "LLDAP_LDAP_PORT": str(self.ldap.server_address[1]), "LLDAP_BASE_DN": BASE_DN,
            "LLDAP_ADMIN_USER": ADMIN[0], "LLDAP_ADMIN_PASSWORD": ADMIN[1],
        })
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), helper.Handler)
        for server in (self.web, self.ldap, self.server):
            threading.Thread(target=server.serve_forever, args=(0.05,), daemon=True).start()
        self.log = Captured()
        helper.log.addHandler(self.log)
        helper.log.setLevel(logging.INFO)
        helper.log.propagate = False

    def tearDown(self):
        helper.log.removeHandler(self.log)
        for server in (self.web, self.ldap, self.server):
            server.shutdown()
            server.server_close()

    def call(self, path, body, secret=SECRET, method="POST"):
        data = json.dumps(body).encode()
        headers = {"Content-Type": "application/json", **({"Authorization": f"Bearer {secret}"} if secret else {})}
        request = urllib.request.Request(f"http://127.0.0.1:{self.server.server_address[1]}{path}", data, headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.load(e)

    def register(self, name="anna", email="anna@example.org", password=PASSWORD):
        return self.call("/register", {"username": name, "displayName": name.title(), "email": email, "password": password})

    def waiting(self):
        with open(self.state) as f:
            return json.load(f)["waiting"]

    def test_register_makes_the_user_in_no_group_with_the_password(self):
        self.assertEqual((200, {}), self.register())
        self.assertEqual({"email": "anna@example.org", "displayName": "Anna", "groups": []}, self.lldap.users["anna"])
        self.assertEqual(PASSWORD, self.lldap.passwords["anna"])
        self.assertEqual([f"uid={ADMIN[0]},ou=people,{BASE_DN}"], self.lldap.binds, "set as the helper's admin, over LDAP")
        self.assertEqual(["anna"], self.waiting())

    def test_a_user_name_or_e_mail_address_in_use_is_taken(self):
        self.assertEqual((409, {"error": "taken"}), self.register(name="admin"))
        self.register()
        self.assertEqual((409, {"error": "taken"}), self.register(name="anna2", email="anna@example.org"))
        self.assertNotIn("anna2", self.lldap.users)
        self.assertEqual(["anna"], self.waiting())
        self.assertEqual(["lldap_admin"], self.lldap.users["admin"]["groups"], "the admin as it was")

    def test_a_password_that_cannot_be_set_deletes_the_user_at_once(self):
        self.lldap.refuse_password = True
        self.assertEqual((502, {"error": "directory"}), self.register())
        self.assertNotIn("anna", self.lldap.users)
        self.assertEqual([], self.waiting())

    def test_approve_puts_the_user_in_the_configured_group_once(self):
        self.register()
        self.assertEqual((200, {}), self.call("/approve", {"username": "anna"}))
        self.assertEqual(["mealplanner"], self.lldap.users["anna"]["groups"])
        self.assertEqual([], self.waiting())
        self.assertEqual((404, {"error": "unknown"}), self.call("/approve", {"username": "anna"}), "no longer its to change")
        self.assertEqual((404, {"error": "unknown"}), self.call("/reject", {"username": "anna"}))
        self.assertIn("anna", self.lldap.users)

    def test_reject_deletes_the_user(self):
        self.register()
        self.assertEqual((200, {}), self.call("/reject", {"username": "anna"}))
        self.assertNotIn("anna", self.lldap.users)
        self.assertEqual([], self.waiting())

    def test_a_passing_lldap_error_keeps_the_name_until_the_next_try(self):
        # The owner's reject and the server's expiry are the same call to the helper; approve looks the user up the same way.
        for path, then in (("/reject", None), ("/approve", ["mealplanner"])):
            self.register()
            for operation in ("FindUser", "DeleteUser" if path == "/reject" else "AddUserToGroup"):
                self.lldap.fail_once.add(operation)
                self.assertEqual((502, {"error": "directory"}), self.call(path, {"username": "anna"}), f"{path} {operation}")
                self.assertEqual(["anna"], self.waiting(), "still the helper's, for the server to try again")
                self.assertEqual([], self.lldap.users["anna"]["groups"])
            self.assertEqual((200, {}), self.call(path, {"username": "anna"}), "the next try")
            self.assertEqual(then, (self.lldap.users.get("anna") or {}).get("groups"))
            self.assertEqual([], self.waiting())
            self.lldap.users.pop("anna", None)

    def test_a_passing_error_while_registering_leaves_nothing(self):
        self.lldap.fail_once.add("FindUser")
        self.assertEqual((502, {"error": "directory"}), self.register())
        self.assertNotIn("anna", self.lldap.users)
        self.assertEqual((200, {}), self.register(), "the next try")

    def test_users_it_did_not_make_are_not_touched(self):
        self.lldap.users["eve"] = {"email": "eve@example.org", "groups": []}
        for path in ("/approve", "/reject"):
            for name in ("admin", "eve"):
                self.assertEqual((404, {"error": "unknown"}), self.call(path, {"username": name}), f"{path} {name}")
        self.assertEqual(["lldap_admin"], self.lldap.users["admin"]["groups"])
        self.assertEqual([], self.lldap.users["eve"]["groups"])

    def test_a_user_put_in_a_group_by_hand_is_no_longer_its_to_change(self):
        self.register()
        self.lldap.users["anna"]["groups"].append("family")
        self.assertEqual((409, {"error": "in_group"}), self.call("/reject", {"username": "anna"}))
        self.assertEqual(["family"], self.lldap.users["anna"]["groups"])
        self.assertEqual([], self.waiting())

    def test_the_group_comes_from_the_configuration_only(self):
        self.register()
        self.assertEqual((400, {"error": "invalid"}), self.call("/approve", {"username": "anna", "group": "lldap_admin"}))
        self.assertEqual((400, {"error": "invalid"}), self.call("/register", {"username": "bob", "displayName": "Bob", "email": "b@example.org",
                                                                          "password": PASSWORD, "groups": ["lldap_admin"]}))
        self.assertEqual([], self.lldap.users["anna"]["groups"])
        self.assertNotIn("bob", self.lldap.users)
        for group in ("lldap_admin", "LLDAP_password_manager", ""):
            with self.assertRaises(SystemExit, msg=group):
                helper.Helper({**self.config(), "HELPER_GROUP": group})

    def config(self):
        h = helper.Handler.helper
        return {"HELPER_SECRET": h.secret, "HELPER_GROUP": h.group, "HELPER_STATE": h.state, "LLDAP_URL": h.url,
                "LLDAP_BASE_DN": h.base_dn, "LLDAP_ADMIN_USER": h.admin[0], "LLDAP_ADMIN_PASSWORD": h.admin[1]}

    def test_only_a_caller_with_the_secret_is_heard(self):
        form = {"username": "anna", "displayName": "Anna", "email": "anna@example.org", "password": PASSWORD}
        for secret in (None, "wrong", SECRET[:-1], SECRET + "x"):
            self.assertEqual(401, self.call("/register", form, secret=secret)[0], secret)
        self.assertNotIn("anna", self.lldap.users)
        self.assertEqual(404, self.call("/delete", {"username": "admin"})[0])
        self.assertEqual(405, self.call("/register", {}, method="GET")[0])
        with self.assertRaises(SystemExit):
            helper.Helper({**self.config(), "HELPER_SECRET": "short"})
        with self.assertRaises(SystemExit):
            helper.Helper({**self.config(), "LLDAP_ADMIN_PASSWORD": ""})

    def test_fields_are_checked_again(self):
        for name, email, password in [("Anna", "a@example.org", PASSWORD), ("an", "a@example.org", PASSWORD), ("anna", "no-address", PASSWORD),
                                      ("anna", "a@example.org", "short"), ("anna", "a@example.org", "ANNA")]:
            self.assertEqual((400, {"error": "invalid"}), self.register(name, email, password), (name, email, password))
        self.assertNotIn("anna", self.lldap.users)

    def test_the_rules_are_the_servers(self):
        """The cases the server's RegistrationRulesAgreementTest reads too: both sides accept and refuse the same strings."""
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "registration-rules-cases.json")) as f:
            rules = json.load(f)
        for case in rules["cases"]:
            self.assertEqual(case["valid"], helper.valid("/register", {**rules["base"], **case["set"]}), case["why"])

    def test_lldap_down_leaves_nothing(self):
        with socketserver.TCPServer(("127.0.0.1", 0), socketserver.BaseRequestHandler) as closed:
            port = closed.server_address[1]
        helper.Handler.helper.url = f"http://127.0.0.1:{port}"  # nobody listens there any more
        self.assertEqual((502, {"error": "directory"}), self.register())
        self.assertFalse(os.path.exists(self.state) and self.waiting(), "nothing remembered")

    def test_no_request_body_is_logged(self):
        self.register()
        self.register(name="bob", email="anna@example.org")
        self.lldap.refuse_password = True
        self.register(name="carl", email="carl@example.org", password=PASSWORD + "!")
        self.call("/approve", {"username": "anna"})
        self.call("/register", {"username": "dora", "password": PASSWORD}, secret="wrong")
        text = "\n".join(self.log.lines)
        self.assertIn("register anna: 200", text)
        for secret in (PASSWORD, "example.org", "Anna", SECRET, ADMIN[1]):
            self.assertNotIn(secret, text)

    def test_the_ldap_messages_are_as_x690_encodes_them(self):
        # A simple bind of "cn=a" with the password "x", message 1, written out by hand.
        expected = bytes.fromhex("3011 020101 600c 020103 0404") + b"cn=a" + bytes.fromhex("8001") + b"x"
        self.assertEqual(expected, helper.ber(0x30, helper.integer(1) + helper.ber(0x60, helper.integer(3) + helper.ber(0x04, b"cn=a") + helper.ber(0x80, b"x"))))
        self.assertEqual(bytes.fromhex("0481c8") + b"a" * 200, helper.ber(0x04, b"a" * 200), "a length of one more byte")
        self.assertEqual(bytes.fromhex("0482012c") + b"a" * 300, helper.ber(0x04, b"a" * 300), "a length of two more bytes")
        self.assertEqual(bytes.fromhex("02020080"), helper.integer(128), "a positive number keeps its sign bit clear")
        self.assertEqual(53, helper.result_code(bytes.fromhex("020102 6107 0a0135 0400 0400")))
        self.assertEqual(10, helper.utf16_length("\U0001F600" * 5), "as Kotlin counts it")


if __name__ == "__main__":
    unittest.main()
