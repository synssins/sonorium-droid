"""
Signs in as the CI admin and makes a fresh Link App code (they last 5 minutes),
with the address the emulator uses for this machine. Prints it base64-encoded.

Usage: python3 e2e/make_link.py https://127.0.0.1:8808 10.0.2.2 USER PASSWORD
"""
import base64
import http.cookiejar
import json
import re
import ssl
import sys
import urllib.request

base, emulator_host, username, password = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3], sys.argv[4]
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE  # the server's own self-signed certificate; the app pins it
opener = urllib.request.build_opener(
    urllib.request.HTTPSHandler(context=ctx), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
)


def call(method, path, body=None, csrf=None):
    headers = {"Content-Type": "application/json"}
    if csrf:
        headers["X-CSRF-Token"] = csrf
    data = json.dumps(body).encode() if body is not None else None
    with opener.open(urllib.request.Request(base + path, data=data, method=method, headers=headers), timeout=20) as r:
        text = r.read().decode()
        return json.loads(text) if text else None


call("POST", "/api/auth/login", {"username": username, "password": password})
csrf = call("GET", "/api/auth/me").get("csrf")
uri = call("POST", "/api/link", csrf=csrf)["uri"]
uri = re.sub(r"([#&?])h=[^&]*", lambda m: f"{m.group(1)}h={emulator_host}", uri)
print(base64.b64encode(uri.encode()).decode())
