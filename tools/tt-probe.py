#!/usr/bin/env python3
"""Read-only probe of TimeTree's web API, used to capture real JSON for test fixtures.

Run it yourself in a terminal: python3 tools/tt-probe.py
It asks for the TimeTree email and password. The password is only sent to
timetreeapp.com and is never printed or written to disk; the session cookie
is not saved either. It only reads: login, calendar list, labels and the
event sync pages of the calendar you pick. Output goes to tools/out/<time>/
(gitignored, mode 700).
"""
import datetime
import getpass
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "https://timetreeapp.com/api"
HEADERS = {
    "Content-Type": "application/json",
    "Accept": "application/json",
    "X-Timetreea": "web/2.1.0/en",
}
MAX_PAGES = 50


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "out", datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out, mode=0o700)

    email = input("TimeTree email: ").strip()
    password = getpass.getpass("TimeTree password (hidden, not stored): ")

    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
    )

    def call(method, path, body=None, save_as=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method, headers=HEADERS)
        try:
            with opener.open(req, timeout=30) as resp:
                status, raw = resp.status, resp.read()
        except urllib.error.HTTPError as err:
            status, raw = err.code, err.read()
        if save_as:
            with open(os.path.join(out, save_as), "wb") as f:
                f.write(raw)
        time.sleep(0.5)  # stay far below any rate limit
        return status, raw

    status, raw = call(
        "PUT",
        "/v1/auth/email/signin",
        {"uid": email, "password": password, "uuid": uuid.uuid4().hex},
    )
    password = None
    if status != 200:
        # Only the error code, never the request.
        try:
            code = json.loads(raw).get("error", {}).get("code")
        except ValueError:
            code = None
        print(f"Login failed: HTTP {status}, TimeTree error code {code}")
        return 1
    print("Login OK")

    status, raw = call("GET", "/v1/calendars?since=0", save_as="calendars.json")
    if status != 200:
        print(f"Calendar list failed: HTTP {status}")
        return 1
    calendars = json.loads(raw).get("calendars", [])
    for i, cal in enumerate(calendars):
        print(f"  [{i}] {cal.get('name')!r} (id {cal.get('id')})")
    choice = input("Dump which calendar? Number: ").strip()
    cal = calendars[int(choice)]
    cal_id = cal["id"]

    call("GET", f"/v1/calendar/{cal_id}/labels", save_as="labels.json")

    path = f"/v1/calendar/{cal_id}/events/sync"
    for page in range(MAX_PAGES):
        status, raw = call("GET", path, save_as=f"events-page-{page:03d}.json")
        if status != 200:
            print(f"Event page {page} failed: HTTP {status}")
            return 1
        body = json.loads(raw)
        print(f"  page {page}: {len(body.get('events', []))} events, chunk={body.get('chunk')}, since={body.get('since')}")
        if not body.get("chunk"):
            break
        path = f"/v1/calendar/{cal_id}/events/sync?since={body.get('since')}"

    print(f"Saved to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
