"""
Prepares a fresh Sonorium server (the Docker image) for the app's end-to-end
test: creates the admin account, signs in, creates a channel and makes a Link
App code. Prints shell assignments for GitHub Actions ($GITHUB_ENV format).

Usage: python3 e2e/server_setup.py https://127.0.0.1:8808 10.0.2.2
(the second argument is the address the emulator uses for this machine)
"""
import base64
import http.cookiejar
import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1].rstrip("/")
EMULATOR_HOST = sys.argv[2]
USERNAME, PASSWORD = "ci-admin", "ci-password-123"
CHANNEL = "CI channel"

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE  # the server's own self-signed certificate; the app pins it
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), urllib.request.HTTPCookieProcessor(jar))
csrf = None


def call(method, path, body=None):
    headers = {"Content-Type": "application/json"}
    if csrf and method != "GET":
        headers["X-CSRF-Token"] = csrf
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with opener.open(req, timeout=20) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, text


def log(*a):
    print(*a, file=sys.stderr)


# The server may still be starting.
for _ in range(90):
    try:
        status, info = call("GET", "/api/info")
        if status == 200:
            break
    except OSError:
        pass
    time.sleep(2)
else:
    sys.exit("The server didn't start")
log("server", info.get("version"), "api", info.get("api"), "ui", info.get("ui"))

status, me = call("GET", "/api/auth/me")
if me and me.get("starting"):
    time.sleep(5)
status, body = call("POST", "/api/auth/setup", {"username": USERNAME, "password": PASSWORD})
log("setup", status, body)
if status >= 400:
    status, body = call("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
    log("login", status, body)
status, me = call("GET", "/api/auth/me")
csrf = me.get("csrf")
log("me", me.get("kind"), (me.get("user") or {}).get("username"), "csrf", bool(csrf))

status, themes = call("GET", "/api/themes")
theme = next((t for t in themes if t.get("has_audio")), None) if isinstance(themes, list) else None
log("themes", [t.get("name") for t in themes] if isinstance(themes, list) else themes)
status, channel = call("POST", "/api/sessions", {"custom_name": CHANNEL, **({"theme_id": theme["id"]} if theme else {})})
log("channel", status, channel and channel.get("id"))

status, link = call("POST", "/api/link")
if status != 200:
    sys.exit(f"/api/link failed: {status} {link}")
# The QR lists the container's addresses; the emulator reaches the host as EMULATOR_HOST.
uri = re.sub(r"([#&?])h=[^&]*", lambda m: f"{m.group(1)}h={EMULATOR_HOST}", link["uri"])
log("link", uri.split("&c=")[0] + "&c=…")

print(f"LINK_URI_B64={base64.b64encode(uri.encode()).decode()}")
print(f"CHANNEL_NAME={CHANNEL}")
print(f"CI_ADMIN={USERNAME}")
print(f"CI_PASSWORD={PASSWORD}")
