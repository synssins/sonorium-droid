"""After the app test: what the server saw. Prints a Markdown summary."""
import json
import ssl
import sys
import urllib.request
import http.cookiejar

BASE = sys.argv[1].rstrip("/")
USERNAME, PASSWORD = sys.argv[2], sys.argv[3]
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx), urllib.request.HTTPCookieProcessor(jar))


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with opener.open(req, timeout=20) as r:
        text = r.read().decode()
        return json.loads(text) if text else None


call("POST", "/api/auth/login", {"username": USERNAME, "password": PASSWORD})
devices = call("GET", "/api/devices")["devices"]
print("### Server view\n")
print(f"Linked phones: {len(devices)}")
for d in devices:
    print(f"- {d['name']} ({d['platform']}, {d['model']}), user {d.get('username')}, last seen {d['last_seen']}")
sys.exit(0 if devices else 1)
