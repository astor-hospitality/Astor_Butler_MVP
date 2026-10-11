#!/usr/bin/env python3
"""Step 5: the staff portal pass from STAFF_PORTAL_RUNBOOK.md, section 5, as HTTP requests.

    provision   local Keycloak only: create the synthetic accounts and write the accounts file
    managers    print "tenant|subject|display name" for the manager accounts (what the operator binds)
    unbound     checks before the operator has bound anybody
    pass        the pass itself, after the managers are bound in astor_staff_members
    expiry      waits about six minutes and checks that the manager's token stops working
    disabled    for an application started with the portal off: every staff route must be closed

Sign-in is the real one: authorization code with PKCE (S256) against the issuer, no browser and no
password grant. The redirect back to the cabinet is never followed, only its address is read.
Tokens and passwords are never printed.

Settings come from the environment:
    STAFF_ISSUER          default https://localhost:28443/realms/astor
    STAFF_API_BASE        default http://localhost:28088
    STAFF_REDIRECT_URI    default https://localhost:28443/rehearsal-callback/
    STAFF_CA_FILE         certificate to trust for the issuer (self-signed local Keycloak)
    STAFF_ACCOUNTS_FILE   JSON written by `provision`, or by hand for accounts an operator created
    STAFF_KC_ADMIN_PASSWORD_FILE   only for `provision`

Both addresses must be on this machine. Another host is accepted only when its exact name is
repeated in REHEARSAL_NON_LOCAL_HOST, and `provision` is refused there in any case.
"""
import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ISSUER = os.environ.get("STAFF_ISSUER", "https://localhost:28443/realms/astor").rstrip("/")
API = os.environ.get("STAFF_API_BASE", "http://localhost:28088").rstrip("/")
REDIRECT = os.environ.get("STAFF_REDIRECT_URI", "https://localhost:28443/rehearsal-callback/")
CA_FILE = os.environ.get("STAFF_CA_FILE")
ACCOUNTS_FILE = os.environ.get("STAFF_ACCOUNTS_FILE", "accounts.json")
CLIENT_ID = "astor-staff-ui"
AUDIENCE = "astor-api"
LOOPBACK = {"localhost", "127.0.0.1", "::1"}

# The people of the pass. Tenants and names are made up; "OTHER-VENUE" is the foreign venue.
PEOPLE = {
    "manager": ("AERIS", "astor-manager", "Rehearsal Manager"),
    "waiter_a": ("AERIS", "astor-waiter", "Rehearsal Waiter A"),
    "waiter_b": ("AERIS", "astor-waiter", "Rehearsal Waiter B"),
    "foreign_manager": ("OTHER-VENUE", "astor-manager", "Other Venue Manager"),
}


class Failure(Exception):
    pass


def host_of(url):
    return urllib.parse.urlsplit(url).hostname or ""


def is_local(url):
    return host_of(url) in LOOPBACK


def guard(url, what):
    if is_local(url) or os.environ.get("REHEARSAL_NON_LOCAL_HOST") == host_of(url):
        return
    sys.exit(f"{what} is {host_of(url)}, not this machine. Nothing was sent. "
             f"Repeat the exact host in REHEARSAL_NON_LOCAL_HOST if that is intended.")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """A redirect is an answer to read, never an address to open."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def opener(cookies=None):
    context = ssl.create_default_context(cafile=CA_FILE) if CA_FILE else ssl.create_default_context()
    handlers = [NoRedirect(), urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context)]
    if cookies is not None:
        handlers.append(urllib.request.HTTPCookieProcessor(cookies))
    return urllib.request.build_opener(*handlers)


def call(op, method, url, headers=None, form=None, body=None):
    headers = dict(headers or {})
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with op.open(request, timeout=20) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


def claims_of(token):
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def sign_in(username, password):
    """Authorization code with PKCE, as the cabinet does it in the browser."""
    jar = http.cookiejar.CookieJar()
    op = opener(jar)
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    query = urllib.parse.urlencode({
        "client_id": CLIENT_ID, "redirect_uri": REDIRECT, "response_type": "code", "scope": "openid",
        "state": state, "code_challenge": challenge, "code_challenge_method": "S256"})
    status, _, page = call(op, "GET", f"{ISSUER}/protocol/openid-connect/auth?{query}")
    if status != 200:
        raise Failure(f"the sign-in page answered {status}")
    form = re.search(r'<form[^>]+action="([^"]+)"', page.decode("utf-8", "replace"))
    if not form:
        raise Failure("the sign-in page has no form")
    action = html.unescape(form.group(1))
    if urllib.parse.urlsplit(action).netloc != urllib.parse.urlsplit(ISSUER).netloc:
        raise Failure("the sign-in form posts to another host; the password was not sent")
    status, headers, _ = call(op, "POST", action, form={"username": username, "password": password, "credentialId": ""})
    location = headers.get("Location", "")
    if status != 302 or not location.startswith(REDIRECT):
        raise Failure(f"sign-in of {username} answered {status} without the redirect to the cabinet "
                      f"(wrong password, a required action or another callback)")
    answer = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)
    if answer.get("state") != [state] or "code" not in answer:
        raise Failure("the redirect carries no code for this sign-in")
    status, _, raw = call(opener(), "POST", f"{ISSUER}/protocol/openid-connect/token", form={
        "grant_type": "authorization_code", "code": answer["code"][0], "redirect_uri": REDIRECT,
        "client_id": CLIENT_ID, "code_verifier": verifier})
    if status != 200:
        raise Failure(f"the code exchange answered {status}")
    token = json.loads(raw)["access_token"]
    return token, claims_of(token)


def load_accounts():
    with open(ACCOUNTS_FILE, encoding="utf-8") as source:
        return json.load(source)


# ---------- provision: local Keycloak only ----------

def provision():
    if not is_local(ISSUER):
        sys.exit("provision creates accounts and is allowed only on a Keycloak of this machine")
    base = ISSUER.split("/realms/")[0]
    with open(os.environ["STAFF_KC_ADMIN_PASSWORD_FILE"], encoding="utf-8") as source:
        admin_password = source.read().strip()
    status, _, raw = call(opener(), "POST", f"{base}/realms/master/protocol/openid-connect/token", form={
        "grant_type": "password", "client_id": "admin-cli", "username": "rehearsal-admin", "password": admin_password})
    if status != 200:
        sys.exit(f"the local Keycloak refused its bootstrap operator: {status}")
    admin = {"Authorization": "Bearer " + json.loads(raw)["access_token"]}
    op = opener()
    users = f"{base}/admin/realms/astor/users"
    accounts = {}
    for key, (tenant, role, name) in PEOPLE.items():
        username = "rehearsal-" + key.replace("_", "-")
        status, _, raw = call(op, "GET", f"{users}?exact=true&username={username}", headers=admin)
        for existing in json.loads(raw) if status == 200 else []:
            call(op, "DELETE", f"{users}/{existing['id']}", headers=admin)
        # Realm policy: 12+ characters with upper and lower case, a digit and a special character.
        password = secrets.token_urlsafe(18) + "aA1!"
        first, last = name.rsplit(" ", 1)
        status, headers, raw = call(op, "POST", users, headers=admin, body={
            "username": username, "enabled": True, "emailVerified": True,
            "email": f"{username}@rehearsal.invalid", "firstName": first, "lastName": last,
            "attributes": {"tenant": [tenant]},
            "credentials": [{"type": "password", "value": password, "temporary": False}]})
        if status != 201:
            sys.exit(f"could not create {username}: {status} {raw[:200]!r}")
        subject = headers["Location"].rsplit("/", 1)[1]
        status, _, raw = call(op, "GET", f"{base}/admin/realms/astor/roles/{role}", headers=admin)
        status, _, raw = call(op, "POST", f"{users}/{subject}/role-mappings/realm", headers=admin, body=[json.loads(raw)])
        if status != 204:
            sys.exit(f"could not give {role} to {username}: {status}")
        accounts[key] = {"username": username, "password": password, "tenant": tenant, "role": role,
                         "displayName": name, "subject": subject}
    descriptor = os.open(ACCOUNTS_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as target:
        json.dump({"issuer": ISSUER, "accounts": accounts}, target, indent=2)
    print(f"{len(accounts)} synthetic accounts created in realm astor; credentials are in the accounts file only")


def managers():
    for account in load_accounts()["accounts"].values():
        if account["role"] == "astor-manager":
            subject = account.get("subject") or sign_in(account["username"], account["password"])[1]["sub"]
            print(f"{account['tenant']}|{subject}|{account['displayName']}")


# ---------- the checks ----------

class Report:
    def __init__(self):
        self.rows = []

    def add(self, what, expected, got, ok):
        self.rows.append(ok)
        print(f"{len(self.rows):>2}. {'PASS' if ok else 'FAIL'}  {what}: expected {expected}, got {got}")

    def finish(self):
        failed = self.rows.count(False)
        print(f"RESULT: {len(self.rows) - failed} of {len(self.rows)} checks passed" + ("" if not failed else f", {failed} FAILED"))
        return 1 if failed else 0


class Person:
    def __init__(self, key, account):
        self.key = key
        self.account = account
        self.token, self.claims = sign_in(account["username"], account["password"])
        self.subject = self.claims.get("sub", "")

    def request(self, method, path, body=None, token="own"):
        headers = {}
        if token == "own":
            headers["Authorization"] = "Bearer " + self.token
        elif token:
            headers["Authorization"] = "Bearer " + token
        status, _, raw = call(opener(), method, API + path, headers=headers, body=body)
        try:
            data = json.loads(raw) if raw else None
        except ValueError:
            data = None
        code = data["error"].get("code") if isinstance(data, dict) and isinstance(data.get("error"), dict) else None
        return status, code, data


def answer(status, code):
    return f"{status} {code}" if code else str(status)


def expect(report, what, response, status, code=None):
    got_status, got_code, data = response
    report.add(what, answer(status, code), answer(got_status, got_code), got_status == status and (code is None or got_code == code))
    return data


def token_check(report, person):
    tenant, role, _ = PEOPLE[person.key]
    claims = person.claims
    audience = claims.get("aud")
    audience = [audience] if isinstance(audience, str) else (audience or [])
    roles = (claims.get("realm_access") or {}).get("roles") or []
    lifetime = claims.get("exp", 0) - claims.get("iat", 0)
    ok = (claims.get("iss") == ISSUER and AUDIENCE in audience and claims.get("tenant") == tenant
          and role in roles and bool(person.subject) and 0 < lifetime <= 300)
    report.add(f"token of {person.key}", f"iss, aud {AUDIENCE}, tenant {tenant}, role {role}, lifetime <= 300 s",
               f"aud {audience}, tenant {claims.get('tenant')}, astor roles {sorted(r for r in roles if r.startswith('astor-'))}, lifetime {lifetime} s", ok)


def draft(assignee, title):
    return {"sourceRef": "rehearsal", "tableCode": "T5", "title": title, "instruction": "Rehearsal task, no guest behind it",
            "stages": [{"code": "prepare", "title": "Prepare", "evidenceRequired": False},
                       {"code": "serve", "title": "Serve", "evidenceRequired": False}],
            "priority": "NORMAL", "deadline": None, "assigneeStaffId": assignee}


def event():
    return str(uuid.uuid4())


def unbound():
    report = Report()
    accounts = load_accounts()["accounts"]
    status, _, raw = call(opener(), "GET", API + "/api/staff/login-config")
    config = json.loads(raw) if status == 200 else {}
    report.add("login-config without a token", f"200, enabled, issuer {ISSUER}, client {CLIENT_ID}",
               f"{status}, enabled={config.get('enabled')}, issuer {config.get('issuer')}, client {config.get('clientId')}",
               status == 200 and config.get("enabled") is True and config.get("issuer") == ISSUER and config.get("clientId") == CLIENT_ID)
    manager = Person("manager", accounts["manager"])
    token_check(report, manager)
    expect(report, "dashboard without a token", manager.request("GET", "/api/admin/staff-tasks/dashboard", token=None), 401, "UNAUTHORIZED")
    spoiled = manager.token[:-6] + ("AAAAAA" if not manager.token.endswith("AAAAAA") else "BBBBBB")
    expect(report, "dashboard with a token whose signature is spoiled", manager.request("GET", "/api/admin/staff-tasks/dashboard", token=spoiled), 401, "UNAUTHORIZED")
    expect(report, "dashboard of a manager who is signed in but not bound to a venue by the operator",
           manager.request("GET", "/api/admin/staff-tasks/dashboard"), 403, "STAFF_INACTIVE")
    return report.finish()


def run_pass():
    report = Report()
    accounts = load_accounts()["accounts"]
    manager = Person("manager", accounts["manager"])
    first = Person("waiter_a", accounts["waiter_a"])
    second = Person("waiter_b", accounts["waiter_b"])
    foreign = Person("foreign_manager", accounts["foreign_manager"])
    for person in (manager, first, second, foreign):
        token_check(report, person)

    board = expect(report, "manager opens the dashboard", manager.request("GET", "/api/admin/staff-tasks/dashboard"), 200) or {}
    report.add("dashboard of the manager", "tenant AERIS, manageStaff true",
               f"tenant {board.get('tenant')}, manageStaff {board.get('manageStaff')}", board.get("tenant") == "AERIS" and board.get("manageStaff") is True)

    # The manager registers two employees; a task cannot go to somebody whose shift is not open.
    for person in (first, second):
        expect(report, f"manager registers {person.key}", manager.request("PUT", f"/api/admin/staff/members/{person.subject}",
               {"displayName": person.account["displayName"], "role": "WAITER", "active": True}), 204)
    expect(report, "manager assigns a task to an employee whose shift is closed",
           manager.request("POST", "/api/admin/staff-tasks", {"eventId": event(), "task": draft(first.subject, "Closed shift")}), 409, "ASSIGNEE_OFF_SHIFT")
    listing = expect(report, "employee with a closed shift reads own tasks", first.request("GET", "/api/staff/tasks"), 200) or {}
    report.add("feed of an employee with a closed shift", "empty", f"{len(listing.get('items', []))} items", listing.get("items") == [])
    for person in (first, second):
        expect(report, f"manager opens the shift of {person.key}", manager.request("POST", f"/api/admin/staff/members/{person.subject}/shift",
               {"open": True, "deviceId": "rehearsal-" + person.key}), 204)

    # The task.
    created_event = event()
    body = {"eventId": created_event, "task": draft(first.subject, "Water to table five")}
    task = expect(report, "manager creates a task for waiter_a", manager.request("POST", "/api/admin/staff-tasks", body), 200) or {}
    task_id = task.get("taskId", "missing")
    report.add("new task", "ASSIGNED, version 1, assigned to waiter_a",
               f"{task.get('status')}, version {task.get('version')}", task.get("status") == "ASSIGNED" and task.get("version") == 1 and task.get("assigneeStaffId") == first.subject)
    again = expect(report, "the same create request repeated", manager.request("POST", "/api/admin/staff-tasks", body), 200) or {}
    report.add("repeated request", "the same task, no second one", "same task" if again.get("taskId") == task_id else "another task", again.get("taskId") == task_id)
    expect(report, "the same event id with another task", manager.request("POST", "/api/admin/staff-tasks",
           {"eventId": created_event, "task": draft(first.subject, "Something else")}), 409, "EVENT_CONFLICT")
    commands = f"/api/staff/tasks/{task_id}/commands"

    # Only the assigned employee with an open shift may accept.
    expect(report, "another employee on shift accepts the task", second.request("POST", commands, {"eventId": event(), "type": "ACCEPT", "expectedVersion": 1}), 403, "FORBIDDEN")
    other = expect(report, "another employee reads own tasks", second.request("GET", "/api/staff/tasks"), 200) or {}
    report.add("feed of the other employee", "without this task", f"{len(other.get('items', []))} items",
               all(item.get("taskId") != task_id for item in other.get("items", [])))
    expect(report, "manager accepts the task instead of the waiter", manager.request("POST", commands, {"eventId": event(), "type": "ACCEPT", "expectedVersion": 1}), 403, "FORBIDDEN")

    # A foreign venue sees nothing and changes nothing.
    expect(report, "manager of another venue reads the history of the task", foreign.request("GET", f"/api/admin/staff-tasks/{task_id}/history"), 404, "NOT_FOUND")
    expect(report, "manager of another venue cancels the task", foreign.request("POST", f"/api/admin/staff-tasks/{task_id}/cancel", {"eventId": event(), "expectedVersion": 1}), 404, "NOT_FOUND")
    expect(report, "manager of another venue reassigns the task", foreign.request("POST", f"/api/admin/staff-tasks/{task_id}/reassign",
           {"eventId": event(), "expectedVersion": 1, "staffId": second.subject}), 404, "NOT_FOUND")
    expect(report, "manager of another venue opens a shift of this venue's employee",
           foreign.request("POST", f"/api/admin/staff/members/{first.subject}/shift", {"open": False, "deviceId": None}), 404, "NOT_FOUND")
    theirs = expect(report, "manager of another venue opens own dashboard", foreign.request("GET", "/api/admin/staff-tasks/dashboard"), 200) or {}
    report.add("dashboard of the other venue", "tenant OTHER-VENUE, no tasks, none of this venue's staff",
               f"tenant {theirs.get('tenant')}, {len(theirs.get('tasks', []))} tasks, {len(theirs.get('staff', []))} staff",
               theirs.get("tenant") == "OTHER-VENUE" and theirs.get("tasks") == []
               and all(member.get("staffId") not in (first.subject, second.subject, manager.subject) for member in theirs.get("staff", [])))

    # A closed shift.
    expect(report, "manager closes the shift of waiter_a", manager.request("POST", f"/api/admin/staff/members/{first.subject}/shift", {"open": False, "deviceId": None}), 204)
    expect(report, "waiter_a accepts with the shift closed", first.request("POST", commands, {"eventId": event(), "type": "ACCEPT", "expectedVersion": 1}), 403, "SHIFT_CLOSED")
    expect(report, "manager opens the shift of waiter_a again", manager.request("POST", f"/api/admin/staff/members/{first.subject}/shift", {"open": True, "deviceId": "rehearsal-waiter_a"}), 204)

    # Accept, stages, completion.
    mine = expect(report, "waiter_a reads own tasks", first.request("GET", "/api/staff/tasks"), 200) or {}
    report.add("feed of waiter_a", "contains the task", f"{len(mine.get('items', []))} items", any(item.get("taskId") == task_id for item in mine.get("items", [])))
    step = expect(report, "waiter_a accepts", first.request("POST", commands, {"eventId": event(), "type": "ACCEPT", "expectedVersion": 1}), 200) or {}
    report.add("after ACCEPT", "ACCEPTED, version 2", f"{step.get('status')}, version {step.get('version')}", step.get("status") == "ACCEPTED" and step.get("version") == 2)
    expect(report, "a command with a stale version", first.request("POST", commands, {"eventId": event(), "type": "STAGE_DONE", "expectedVersion": 1, "stageCode": "prepare"}), 409, "VERSION_CONFLICT")
    expect(report, "completion while stages are open", first.request("POST", commands, {"eventId": event(), "type": "COMPLETE", "expectedVersion": 2}), 409, "STAGES_OPEN")
    expect(report, "the second stage before the first", first.request("POST", commands, {"eventId": event(), "type": "STAGE_DONE", "expectedVersion": 2, "stageCode": "serve"}), 409, "STAGE_OUT_OF_ORDER")
    step = expect(report, "waiter_a closes stage prepare", first.request("POST", commands, {"eventId": event(), "type": "STAGE_DONE", "expectedVersion": 2, "stageCode": "prepare"}), 200) or {}
    report.add("after the first stage", "IN_PROGRESS, version 3", f"{step.get('status')}, version {step.get('version')}", step.get("status") == "IN_PROGRESS" and step.get("version") == 3)
    step = expect(report, "waiter_a closes stage serve", first.request("POST", commands, {"eventId": event(), "type": "STAGE_DONE", "expectedVersion": 3, "stageCode": "serve"}), 200) or {}
    expect(report, "photo evidence while no storage is connected", first.request("POST", f"/api/staff/tasks/{task_id}/evidence", {}), 503, "EVIDENCE_UNAVAILABLE")
    step = expect(report, "waiter_a completes", first.request("POST", commands, {"eventId": event(), "type": "COMPLETE", "expectedVersion": 4}), 200) or {}
    report.add("after COMPLETE", "DONE, version 5", f"{step.get('status')}, version {step.get('version')}", step.get("status") == "DONE" and step.get("version") == 5)
    expect(report, "a command on the finished task", first.request("POST", commands, {"eventId": event(), "type": "ACCEPT", "expectedVersion": 5}), 409, "TASK_CLOSED")

    # An employee has no manager routes.
    expect(report, "waiter_a opens the manager dashboard", first.request("GET", "/api/admin/staff-tasks/dashboard"), 403, "FORBIDDEN")
    expect(report, "waiter_a registers an employee", first.request("PUT", f"/api/admin/staff/members/{second.subject}", {"displayName": "X", "role": "MANAGER", "active": True}), 403, "FORBIDDEN")

    # The audit as the manager sees it.
    history = expect(report, "manager reads the history of the task", manager.request("GET", f"/api/admin/staff-tasks/{task_id}/history"), 200) or []
    seen = [(entry.get("type"), entry.get("actor"), entry.get("version")) for entry in history]
    wanted = [("COMPLETE", first.subject, 5), ("STAGE_DONE", first.subject, 4), ("STAGE_DONE", first.subject, 3),
              ("ACCEPT", first.subject, 2), ("ASSIGNED", manager.subject, 1)]
    report.add("audit of the task, newest first", "COMPLETE v5, STAGE_DONE v4, STAGE_DONE v3, ACCEPT v2 by waiter_a; ASSIGNED v1 by the manager",
               ", ".join(f"{kind} v{version}" for kind, _, version in seen) + f"; actors match: {seen == wanted}", seen == wanted)
    board = expect(report, "manager opens the dashboard after the pass", manager.request("GET", "/api/admin/staff-tasks/dashboard"), 200) or {}
    done = [item for item in board.get("tasks", []) if item.get("taskId") == task_id]
    report.add("the task on the dashboard", "DONE, version 5", f"{done[0].get('status')}, version {done[0].get('version')}" if done else "absent",
               bool(done) and done[0].get("status") == "DONE" and done[0].get("version") == 5)

    # A directory row entered by hand (glasses pilot): staff_id is not a Keycloak subject.
    pilots = [member for member in board.get("staff", []) if member.get("staffId", "").startswith("pilot-") and member.get("role") == "WAITER" and member.get("shift") == "OPEN"]
    if pilots:
        pilot = pilots[0]["staffId"]
        orphan = expect(report, f"manager assigns a task to the hand-entered row {pilot}",
                        manager.request("POST", "/api/admin/staff-tasks", {"eventId": event(), "task": draft(pilot, "Pilot row task")}), 200) or {}
        orphan_id = orphan.get("taskId", "missing")
        expect(report, "an employee with a real account accepts the task of the hand-entered row",
               first.request("POST", f"/api/staff/tasks/{orphan_id}/commands", {"eventId": event(), "type": "ACCEPT", "expectedVersion": 1}), 403, "FORBIDDEN")
        expect(report, "manager cancels that task", manager.request("POST", f"/api/admin/staff-tasks/{orphan_id}/cancel", {"eventId": event(), "expectedVersion": 1}), 200)
    else:
        print("    (no hand-entered directory rows with an open shift: that part is skipped)")

    # Withdrawal in the directory stops a token that is still valid.
    expect(report, "manager deactivates waiter_b", manager.request("PUT", f"/api/admin/staff/members/{second.subject}",
           {"displayName": second.account["displayName"], "role": "WAITER", "active": False}), 204)
    expect(report, "waiter_b reads own tasks with the token issued before", second.request("GET", "/api/staff/tasks"), 403, "STAFF_INACTIVE")
    return report.finish()


def disabled():
    """The application started without astor.staff.enabled: every staff route is closed, token or not."""
    report = Report()
    status, _, raw = call(opener(), "GET", API + "/api/staff/login-config")
    config = json.loads(raw) if status == 200 else None
    report.add("login-config with the portal off", "200 and only enabled=false", f"{status}, {config}", status == 200 and config == {"enabled": False})
    manager = Person("manager", load_accounts()["accounts"]["manager"])
    routes = [("dashboard", "GET", "/api/admin/staff-tasks/dashboard", None),
              ("own tasks", "GET", "/api/staff/tasks", None),
              ("create a task", "POST", "/api/admin/staff-tasks", {"eventId": event(), "task": draft(manager.subject, "Portal off")}),
              ("register an employee", "PUT", f"/api/admin/staff/members/{manager.subject}", {"displayName": "X", "role": "MANAGER", "active": True})]
    for what, method, path, body in routes:
        status, code, _ = manager.request(method, path, body)
        report.add(f"{what} with a valid token while the portal is off", "401 or 403", answer(status, code), status in (401, 403))
    return report.finish()


def expiry():
    """Waits for the access token of the bound manager to run out: about six minutes."""
    report = Report()
    manager = Person("manager", load_accounts()["accounts"]["manager"])
    expect(report, "dashboard with a fresh token", manager.request("GET", "/api/admin/staff-tasks/dashboard"), 200)
    time.sleep(max(0, manager.claims["exp"] - time.time()) + 5)
    status, code, _ = manager.request("GET", "/api/admin/staff-tasks/dashboard")
    print(f"    NOTE  5 s after exp the same token got {answer(status, code)} "
          f"(200 here means the resource server allows a clock skew after exp)")
    time.sleep(60)
    expect(report, "dashboard 65 s after the token expired", manager.request("GET", "/api/admin/staff-tasks/dashboard"), 401, "UNAUTHORIZED")
    return report.finish()


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    guard(ISSUER, "STAFF_ISSUER")
    guard(API, "STAFF_API_BASE")
    try:
        if command == "provision":
            provision()
        elif command == "managers":
            managers()
        elif command == "unbound":
            sys.exit(unbound())
        elif command == "pass":
            sys.exit(run_pass())
        elif command == "expiry":
            sys.exit(expiry())
        elif command == "disabled":
            sys.exit(disabled())
        else:
            sys.exit(__doc__)
    except Failure as failure:
        sys.exit(f"STOPPED: {failure}")


if __name__ == "__main__":
    main()
