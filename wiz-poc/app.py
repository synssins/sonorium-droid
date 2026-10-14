"""
WiZ light-show proof of concept.

Tiny local web UI for Philips WiZ bulbs:
  * discover bulbs, turn them on/off, pick a colour (all bulbs or one at a time)
  * audio visualizer driven by what the PC is playing (WASAPI loopback) or a microphone
  * music-file visualizer and video "ambilight" - the file plays in the browser, the browser
    analyses it and streams the result here, and this server drives the bulbs

WiZ bulbs speak JSON over UDP port 38899 (e.g. {"method":"setPilot","params":{"r":255,...}}).
No cloud, no pairing - they just need to be on the same LAN.

Run:  python app.py   then open http://127.0.0.1:8765
"""

import colorsys
import ipaddress
import json
import math
import socket
import sys
import threading
import time
import warnings
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
INDEX_PATH = HERE / "index.html"
WIZ_PORT = 38899

MODES = ["frequency_bands", "energy", "rainbow", "multi", "pulse", "strobe", "spectrum_pulse", "chase", "split"]

DEFAULT_CONFIG = {
    "bulbs": [],                  # list of IP strings
    "broadcast": "255.255.255.255",  # extra broadcast address for discovery (each adapter's /24 is added automatically)
    "scan_subnets": [],           # extra subnets / IPs to probe host-by-host, e.g. ["192.168.20.0/24", "10.0.5.17"]
    "scan_local": False,          # also probe this PC's own /24s host-by-host (for networks that block broadcast)
    "bulb_meta": {},              # ip -> {"name": "Desk lamp", "group": "Office"}
    "active_groups": [],          # rooms that effects / the main colour picker drive; empty = all bulbs
    "bulb_macs": {},              # mac -> last known ip; lets discovery spot bulbs whose DHCP address changed
    "http_host": "127.0.0.1",
    "http_port": 8765,
    "color": "#ff0080",           # colour picker value / base colour for pulse, strobe, split
    "brightness": 100,            # 10-100, used by the static colour picker
    "mode": "frequency_bands",    # one of MODES
    "audio_source": "loopback",   # loopback (what the PC plays) | mic | test
    "audio_device": "",           # substring of a device name; blank = system default
    "fps": 20,                    # max updates per second sent to each bulb
    "sensitivity": 1.0,           # >1 = more reactive
    "smoothing": 0.5,             # 0 = jumpy, 0.95 = very smooth (release only; attack is fast)
    "brightness_boost": 1.5,      # multiplies the computed brightness (1.0 = none, 3.0 = max)
    "min_brightness": 10,         # 10-100 (WiZ minimum is 10)
    "max_brightness": 100,
    "brightness_method": "dimming",  # dimming (WiZ dimming param) | rgb (scale the colour; firmware-dependent)
}


# --------------------------------------------------------------------------- config

_cfg_lock = threading.Lock()


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if CONFIG_PATH.exists():
        try:
            cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
        except Exception as e:
            print(f"[config] could not read {CONFIG_PATH}: {e} - using defaults")
    return cfg


CONFIG = load_config()


def get_cfg():
    with _cfg_lock:
        return dict(CONFIG)


def update_cfg(patch):
    with _cfg_lock:
        for k, v in patch.items():
            if k not in DEFAULT_CONFIG:
                continue
            if k == "bulbs":
                v = [s.strip() for s in v if isinstance(s, str) and s.strip()]
            elif isinstance(DEFAULT_CONFIG[k], (int, float)) and not isinstance(DEFAULT_CONFIG[k], bool):
                v = type(DEFAULT_CONFIG[k])(v)
            CONFIG[k] = v
        CONFIG_PATH.write_text(json.dumps(CONFIG, indent=2), encoding="utf-8")
        return dict(CONFIG)


# --------------------------------------------------------------------------- WiZ UDP

_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)


def wiz_send(ip, method, params=None):
    msg = json.dumps({"method": method, "params": params or {}}).encode()
    try:
        _sock.sendto(msg, (ip, WIZ_PORT))
    except OSError as e:
        print(f"[wiz] send to {ip} failed: {e}")


def wiz_query(targets, method="getPilot", params=None, timeout=1.5):
    """Send a request to each target and collect replies {ip: response_dict}."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.2)
    msg = json.dumps({"method": method, "params": params or {}}).encode()
    for ip in targets:
        try:
            s.sendto(msg, (ip, WIZ_PORT))
        except OSError as e:
            print(f"[wiz] query to {ip} failed: {e}")
    out = {}
    end = time.time() + timeout
    while time.time() < end:
        try:
            data, (ip, _) = s.recvfrom(4096)
        except socket.timeout:
            continue
        except OSError:
            break
        try:
            out[ip] = json.loads(data.decode(errors="replace"))
        except ValueError:
            pass
    s.close()
    return out


def bulb_group(cfg, ip):
    return (cfg["bulb_meta"].get(ip) or {}).get("group", "")


def target_bulbs(cfg):
    """Bulbs the effects and main colour picker drive: the selected rooms, or everything."""
    groups = set(cfg["active_groups"])
    if not groups:
        return list(cfg["bulbs"])
    return [ip for ip in cfg["bulbs"] if bulb_group(cfg, ip) in groups]


def group_bulbs(cfg, group):
    return [ip for ip in cfg["bulbs"] if bulb_group(cfg, ip) == group]


IDENTIFYING = set()   # bulbs mid-flash; the effect engine leaves them alone


def restore_command(res):
    """Turn a getPilot result back into the command that recreates that state."""
    if not res.get("state"):
        return "setState", {"state": False}
    p = {"state": True}
    if res.get("dimming") is not None:
        p["dimming"] = res["dimming"]
    if res.get("sceneId"):
        p["sceneId"] = res["sceneId"]
        if res.get("speed") is not None:
            p["speed"] = res["speed"]
    elif all(k in res for k in ("r", "g", "b")):
        p.update({k: res[k] for k in ("r", "g", "b", "c", "w") if k in res})
    elif res.get("temp"):
        p["temp"] = res["temp"]
    return "setPilot", p


def identify(ip, cycles=4):
    """Blink a bulb bright-cold / dim-warm a few times, then put it back how it was."""
    if ip in IDENTIFYING:
        return
    IDENTIFYING.add(ip)
    try:
        before = (wiz_query([ip], "getPilot", timeout=0.6).get(ip) or {}).get("result")
        for _ in range(cycles):
            wiz_send(ip, "setPilot", {"state": True, "temp": 6500, "dimming": 100})
            time.sleep(0.35)
            wiz_send(ip, "setPilot", {"state": True, "temp": 2200, "dimming": 10})
            time.sleep(0.35)
        if before:
            method, params = restore_command(before)
            for _ in range(2):   # UDP: say it twice
                wiz_send(ip, method, params)
                time.sleep(0.1)
        else:
            wiz_send(ip, "setPilot", {"state": True, "temp": 4000, "dimming": 100})
    finally:
        IDENTIFYING.discard(ip)


# --------------------------------------------------------------------------- discovery

MAX_SCAN_HOSTS = 8192   # e.g. a /19, or 32 x /24s


def local_ipv4s():
    """This PC's IPv4 addresses (all adapters), minus loopback and link-local."""
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:   # the address of the default route, in case the hostname lookup missed it
        t = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        t.connect(("10.255.255.255", 1))
        ips.add(t.getsockname()[0])
        t.close()
    except OSError:
        pass
    return sorted((ip for ip in ips if not ip.startswith(("127.", "169.254.", "0."))),
                  key=ipaddress.ip_address)


def parse_targets(entries):
    """CIDRs or single IPs -> list of IPv4Network. Raises ValueError on junk."""
    nets = []
    for e in entries:
        e = e.strip()
        if not e:
            continue
        net = ipaddress.ip_network(e, strict=False)
        if net.version != 4:
            raise ValueError(f"{e}: only IPv4 is supported")
        nets.append(net)
    return nets


def udp_round(sends, socks, timeout):
    """Send [(sock, ip, payload)], then collect replies on all sockets for `timeout` seconds."""
    import select
    for n, (sk, ip, payload) in enumerate(sends):
        try:
            sk.sendto(payload, (ip, WIZ_PORT))
        except OSError:
            pass   # unreachable network, broadcast not allowed on this adapter, ...
        if n % 64 == 63:
            time.sleep(0.003)   # pace big scans so neither the PC nor the Wi-Fi drops packets
    out = {}
    end = time.time() + timeout
    while (left := end - time.time()) > 0:
        ready, _, _ = select.select(socks, [], [], min(left, 0.2))
        for sk in ready:
            try:
                data, (ip, _) = sk.recvfrom(4096)
                out.setdefault(ip, []).append(json.loads(data.decode(errors="replace")))
            except (OSError, ValueError):
                pass
    return out


def device_kind(module):
    m = (module or "").upper()
    if "SOCKET" in m:
        return "plug"
    if "RGB" in m:
        return "colour"
    if "TW" in m:
        return "tunable white"
    if "DW" in m:
        return "dimmable white"
    return "unknown"


def discover(subnets, scan_local, extra_broadcast, timeout=2.5):
    """
    Find WiZ devices by
      1. broadcasting getPilot out of every adapter (255.255.255.255 + each adapter's /24 broadcast),
      2. directed broadcasts to any extra subnets (only works if the router forwards them - most don't),
      3. unicast getPilot to every host in the extra subnets (and the local /24s if scan_local) -
         this is what crosses subnets/VLANs, as long as routing and firewalls pass UDP 38899.
    Then asks each responder for getSystemConfig to get the model and firmware.
    """
    probe = json.dumps({"method": "getPilot", "params": {}}).encode()
    local = local_ipv4s()
    nets = parse_targets(subnets)
    local_nets = [ipaddress.ip_network(f"{ip}/24", strict=False) for ip in local]

    hosts = set()
    for net in nets + (local_nets if scan_local else []):
        if net.num_addresses > MAX_SCAN_HOSTS:
            raise ValueError(f"{net} has {net.num_addresses} addresses - the limit is {MAX_SCAN_HOSTS}. "
                             "Split it into smaller ranges.")
        hosts.update(str(h) for h in (net.hosts() if net.num_addresses > 2 else net))
    hosts -= set(local)
    if len(hosts) > MAX_SCAN_HOSTS:
        raise ValueError(f"{len(hosts)} hosts to scan - the limit is {MAX_SCAN_HOSTS}.")

    default = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    default.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    socks, sends = [default], []
    # On Windows 255.255.255.255 only leaves through one adapter, so send it from a socket bound to each one.
    for ip, net in zip(local, local_nets):
        try:
            sk = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sk.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sk.bind((ip, 0))
        except OSError:
            continue
        socks.append(sk)
        sends += [(sk, "255.255.255.255", probe), (sk, str(net.broadcast_address), probe)]
    bcasts = {extra_broadcast} if extra_broadcast else set()
    bcasts |= {str(n.broadcast_address) for n in nets if n.num_addresses > 2}
    sends += [(default, b, probe) for b in sorted(bcasts)]
    sends += [(default, h, probe) for h in sorted(hosts, key=ipaddress.ip_address)]

    try:
        replies = udp_round(sends, socks, timeout)
        found = {}
        for ip, msgs in replies.items():
            for m in msgs:
                res = m.get("result") if isinstance(m, dict) else None
                if m.get("method") == "getPilot" and isinstance(res, dict) and res.get("mac"):
                    found[ip] = {"ip": ip, "mac": res["mac"], "rssi": res.get("rssi"),
                                 "state": res.get("state")}
        if found:
            sysq = json.dumps({"method": "getSystemConfig", "params": {}}).encode()
            info = udp_round([(default, ip, sysq) for ip in found], [default], 1.0)
            for ip, msgs in info.items():
                for m in msgs:
                    res = m.get("result") if isinstance(m, dict) else None
                    if ip in found and m.get("method") == "getSystemConfig" and isinstance(res, dict):
                        found[ip]["module"] = res.get("moduleName")
                        found[ip]["fw"] = res.get("fwVersion")
        for d in found.values():
            d["kind"] = device_kind(d.get("module"))
        return {"found": sorted(found.values(), key=lambda d: ipaddress.ip_address(d["ip"])),
                "local": [str(n) for n in local_nets], "scanned": len(hosts)}
    finally:
        for sk in socks:
            sk.close()


def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def hsv(h, s=1.0, v=1.0):
    return tuple(c * 255 for c in colorsys.hsv_to_rgb(h % 1.0, s, v))


def pilot(rgb, level, cfg):
    """Build setPilot params for a colour at a 0..1 brightness level."""
    lo = max(10, cfg["min_brightness"]) if cfg["brightness_method"] == "dimming" else cfg["min_brightness"]
    hi = max(10, min(100, cfg["max_brightness"]))
    level = min(max(level, 0.0), 1.0)
    r, g, b = (int(round(min(255, max(0, c)))) for c in rgb)
    if cfg["brightness_method"] == "dimming":
        dim = int(round(lo + (hi - lo) * level))
        if max(r, g, b) == 0:
            r = g = b = 1
        return {"r": r, "g": g, "b": b, "dimming": max(10, min(100, dim))}
    scale = (lo + (hi - lo) * level) / 100.0
    r, g, b = (int(round(c * scale)) for c in (r, g, b))
    if max(r, g, b) == 0:
        r = g = b = 1   # an all-zero colour is rejected by the bulb
    return {"r": r, "g": g, "b": b, "dimming": hi}


def color_params(color, brightness):
    rgb = hex_to_rgb(color)
    if max(rgb) == 0:
        return {"state": False}
    return {"r": rgb[0], "g": rgb[1], "b": rgb[2], "dimming": max(10, min(100, int(brightness))), "state": True}


# --------------------------------------------------------------------------- analysis

BLOCK = 512
FFT_SIZE = 2048
BANDS = {"bass": (20, 250), "mid": (250, 4000), "treble": (4000, 20000)}
DOM_LO, DOM_HI = 40.0, 5000.0


def raw_features(samples, rate, window=None):
    """Raw (un-normalised) band energies, RMS level and dominant frequency of a mono frame."""
    if window is None:
        window = np.hanning(len(samples))
    spec = np.abs(np.fft.rfft(samples * window))
    freqs = np.fft.rfftfreq(len(samples), 1.0 / rate)
    out = {}
    for k, (lo, hi) in BANDS.items():
        m = (freqs >= lo) & (freqs < hi)
        out[k] = float(np.sqrt(np.mean(spec[m] ** 2))) if m.any() else 0.0
    out["level"] = float(np.sqrt(np.mean(samples ** 2)))
    m = (freqs >= DOM_LO) & (freqs < DOM_HI)
    out["dominant"] = float(freqs[m][np.argmax(spec[m])]) if m.any() else 0.0
    return out


class Normalizer:
    """Auto-gain, smoothing and beat detection on top of raw features (from any source)."""

    KEYS = ("bass", "mid", "treble", "level")

    def __init__(self):
        self.peaks = {k: 1e-6 for k in self.KEYS}
        self.vals = {k: 0.0 for k in self.KEYS}
        self.bass_avg = 0.0
        self.last_beat = 0.0
        self.flash = 0.0
        self.dom_pos = 0.0

    def process(self, raw, sensitivity, smoothing):
        silent = raw is None or raw.get("level", 0) < 1e-4
        raw = raw or {}
        out = {}
        for k in self.KEYS:
            v = float(raw.get(k, 0.0))
            # auto-gain: a slowly decaying peak so quiet and loud tracks both use the full range
            self.peaks[k] = max(v, self.peaks[k] * 0.997, 1e-6)
            norm = 0.0 if silent else min(1.0, (v / self.peaks[k]) * sensitivity)
            prev = self.vals[k]
            self.vals[k] = norm if norm > prev else prev * smoothing + norm * (1 - smoothing)
            out[k] = self.vals[k]

        now = time.time()
        bass = float(raw.get("bass", 0.0))
        if (not silent and bass > self.bass_avg * 1.5 and out["bass"] > 0.5
                and now - self.last_beat > 0.15):
            self.last_beat = now
            self.flash = 1.0
        else:
            self.flash *= 0.85
        self.bass_avg = self.bass_avg * 0.95 + bass * 0.05
        out["flash"] = self.flash
        out["beat"] = self.flash > 0.5

        dom = float(raw.get("dominant", 0.0))
        if not silent and dom > 0:
            pos = math.log(max(dom, DOM_LO) / DOM_LO) / math.log(DOM_HI / DOM_LO)
            self.dom_pos = self.dom_pos * smoothing + pos * (1 - smoothing)
        out["dominant"] = dom
        out["dom_pos"] = self.dom_pos
        return out


# --------------------------------------------------------------------------- sources

class PcmSource:
    """Keeps a rolling buffer of mono PCM and turns it into raw features on demand."""

    name = "pcm"

    def __init__(self):
        self.rate = 48000
        self.buf = np.zeros(FFT_SIZE, dtype=np.float32)
        self.window = np.hanning(FFT_SIZE).astype(np.float32)
        self.last_push = 0.0
        self.lock = threading.Lock()
        self.device = None

    def push(self, mono):
        with self.lock:
            n = len(mono)
            if n >= FFT_SIZE:
                self.buf[:] = mono[-FFT_SIZE:]
            else:
                self.buf[:-n] = self.buf[n:]
                self.buf[-n:] = mono
            self.last_push = time.time()

    def raw(self):
        with self.lock:
            if time.time() - self.last_push > 0.2:
                return None   # WASAPI loopback simply stops delivering while nothing plays
            frame = self.buf.copy()
        return raw_features(frame, self.rate, self.window)

    def colors(self):
        return None

    def close(self):
        pass


class PyAudioSource(PcmSource):
    def __init__(self, kind, want):
        super().__init__()
        import pyaudiowpatch as pyaudio  # Windows WASAPI-loopback fork of PyAudio

        self.name = kind
        self.pa = pyaudio.PyAudio()
        dev = find_device(self.pa, kind, want)
        channels = max(1, min(2, int(dev["maxInputChannels"])))
        self.rate = int(dev["defaultSampleRate"])
        self.device = dev["name"]

        def cb(in_data, frame_count, time_info, status):
            a = np.frombuffer(in_data, dtype=np.float32)
            self.push(a.reshape(-1, channels).mean(axis=1))
            return (None, pyaudio.paContinue)

        # Callback mode: a blocking read() would hang on a silent loopback device.
        self.stream = self.pa.open(format=pyaudio.paFloat32, channels=channels, rate=self.rate, input=True,
                                   input_device_index=dev["index"], frames_per_buffer=BLOCK,
                                   stream_callback=cb)
        self.stream.start_stream()

    def close(self):
        try:
            self.stream.stop_stream()
            self.stream.close()
        finally:
            self.pa.terminate()


class TestSource(PcmSource):
    """Synthetic 120 BPM kick + hats + sweeping tone, for trying modes without any audio."""

    name = "test"

    def __init__(self):
        super().__init__()
        self.rate = 44100
        self.device = "test signal (120 BPM)"
        self.stop_evt = threading.Event()
        threading.Thread(target=self._gen, daemon=True).start()

    def _gen(self):
        t0, n = time.time(), 0
        while not self.stop_evt.is_set():
            t = (n + np.arange(BLOCK)) / self.rate
            kick = np.sin(2 * np.pi * 60 * t) * np.exp(-(t % 0.5) * 12)
            hat = np.random.randn(BLOCK) * 0.15 * np.exp(-(t % 0.25) * 40)
            tone = 0.2 * np.sin(2 * np.pi * (300 + 250 * np.sin(2 * np.pi * 0.05 * t)) * t)
            self.push((kick + hat + tone).astype(np.float32))
            n += BLOCK
            time.sleep(max(0.0, t0 + n / self.rate - time.time()))

    def close(self):
        self.stop_evt.set()


class BrowserSource:
    """Fed by the web page (music file / video) through POST /api/frame."""

    name = "browser"

    def __init__(self, label):
        self.device = label
        self.lock = threading.Lock()
        self.frame = {}
        self.t = 0.0

    def feed(self, frame):
        with self.lock:
            self.frame = frame
            self.t = time.time()

    def _fresh(self):
        with self.lock:
            return dict(self.frame) if time.time() - self.t < 0.5 else {}

    def raw(self):
        return self._fresh().get("raw")

    def colors(self):
        return self._fresh().get("colors")

    def close(self):
        pass


def find_device(pa, kind, want):
    want = (want or "").strip().lower()
    if kind == "loopback":
        devs = list(pa.get_loopback_device_info_generator())
        if want:
            for d in devs:
                if want in d["name"].lower():
                    return d
            raise RuntimeError(f"No loopback device matching '{want}'")
        import pyaudiowpatch as pyaudio
        wasapi = pa.get_host_api_info_by_type(pyaudio.paWASAPI)
        spk = pa.get_device_info_by_index(wasapi["defaultOutputDevice"])
        if spk.get("isLoopbackDevice"):
            return spk
        for d in devs:
            if spk["name"] in d["name"]:
                return d
        raise RuntimeError("Could not find a loopback device for the default speakers")
    # microphone
    if want:
        for i in range(pa.get_device_count()):
            d = pa.get_device_info_by_index(i)
            if d["maxInputChannels"] > 0 and not d.get("isLoopbackDevice") and want in d["name"].lower():
                return d
        raise RuntimeError(f"No input device matching '{want}'")
    return pa.get_default_input_device_info()


def list_devices():
    try:
        import pyaudiowpatch as pyaudio
    except ImportError:
        return {"loopback": [], "mic": [], "error": "pyaudiowpatch not installed (Windows only)"}
    pa = pyaudio.PyAudio()
    try:
        loop = [d["name"] for d in pa.get_loopback_device_info_generator()]
        mics = []
        for i in range(pa.get_device_count()):
            d = pa.get_device_info_by_index(i)
            if d["maxInputChannels"] > 0 and not d.get("isLoopbackDevice") and d["name"] not in mics:
                mics.append(d["name"])
        return {"loopback": loop, "mic": mics}
    finally:
        pa.terminate()


# --------------------------------------------------------------------------- rendering

BAND_RGB = {"bass": (255, 0, 0), "mid": (0, 255, 0), "treble": (0, 0, 255)}
BAND_ORDER = ("bass", "mid", "treble")


def band_mix(v):
    """bass -> red, mid -> green, treble -> blue, normalised so the strongest channel is 255."""
    rgb = np.array([v["bass"], v["mid"], v["treble"]], dtype=float)
    peak = rgb.max()
    return tuple(rgb / peak * 255) if peak > 0.01 else (255, 255, 255)


def render(mode, v, i, n, base, chase_hue):
    """-> (rgb, brightness 0..1) for bulb i of n."""
    if mode == "frequency_bands":
        return band_mix(v), max(v["bass"], v["mid"], v["treble"])
    if mode == "energy":
        e = v["level"]   # quiet = cool blue, loud = warm red/orange
        return hsv(0.66 * (1 - e) ** 1.5), 0.3 + 0.7 * e
    if mode == "rainbow":
        return hsv(v["dom_pos"] * 0.85), v["level"]   # hue from the dominant frequency
    if mode == "multi":
        band = BAND_ORDER[i % 3]
        return BAND_RGB[band], v[band]
    if mode == "strobe":
        return base, v["flash"] ** 2
    if mode == "spectrum_pulse":
        r, g, b = band_mix(v)   # subtle (pastel) band colours, strong brightness swings
        return (r * 0.5 + 127, g * 0.5 + 127, b * 0.5 + 127), v["level"] ** 1.6 + v["flash"] * 0.4
    if mode == "chase":
        return hsv(chase_hue + i / max(1, n)), 0.25 + 0.75 * v["level"]
    if mode == "split":
        return base, v[BAND_ORDER[i % 3]]
    # pulse
    return base, max(v["bass"], v["level"])


class Engine(threading.Thread):
    def __init__(self, source):
        super().__init__(daemon=True)
        self.source = source
        self.stop_evt = threading.Event()
        self.norm = Normalizer()
        self.chase_hue = 0.0
        self.last_sent = {}
        self.status = {"running": True, "source": source.name, "device": source.device, "error": None,
                       "bass": 0, "mid": 0, "treble": 0, "level": 0, "beat": False, "dominant": 0}

    def run(self):
        next_send = 0.0
        try:
            while not self.stop_evt.is_set():
                cfg = get_cfg()
                v = self.norm.process(self.source.raw(), cfg["sensitivity"],
                                      min(max(cfg["smoothing"], 0.0), 0.99))
                self.status.update(v)
                now = time.time()
                if now >= next_send:
                    next_send = now + 1.0 / max(1, cfg["fps"])
                    self.drive(cfg, v)
                time.sleep(0.01)
        except Exception as e:
            self.status["error"] = f"{type(e).__name__}: {e}"
        finally:
            try:
                self.source.close()
            except Exception:
                pass
            self.status["running"] = False

    def drive(self, cfg, v):
        bulbs = [ip for ip in target_bulbs(cfg) if ip not in IDENTIFYING]
        n = len(bulbs)
        base = hex_to_rgb(cfg["color"])
        boost = cfg["brightness_boost"]
        colors = self.source.colors()
        hybrid = colors and self.source.raw() is not None
        self.chase_hue = (self.chase_hue + 0.004 + v["bass"] * 0.02 + (0.08 if v["beat"] else 0)) % 1.0

        for i, ip in enumerate(bulbs):
            if colors:
                # video: bulb i takes zone i (edge mode) or the single frame colour
                rgb = colors[i % len(colors)]
                peak = max(rgb)
                rgb = [c / peak * 255 for c in rgb] if peak > 0 else (1, 1, 1)
                # brightness follows the picture, or the soundtrack in hybrid mode
                p = pilot(rgb, v["level"] * boost if hybrid else peak / 255.0, cfg)
            else:
                rgb, level = render(cfg["mode"], v, i, n, base, self.chase_hue)
                p = pilot(rgb, level * boost, cfg)

            key = (p["r"], p["g"], p["b"], p["dimming"])
            last = self.last_sent.get(ip)
            # skip duplicates, but refresh every second in case a UDP packet was dropped
            if last and last[0] == key and time.time() - last[1] < 1.0:
                continue
            self.last_sent[ip] = (key, time.time())
            wiz_send(ip, "setPilot", p)

    def stop(self):
        self.stop_evt.set()


_engine = None
_engine_lock = threading.Lock()
_last_error = None


def start_engine(kind):
    global _engine, _last_error
    with _engine_lock:
        if _engine and _engine.is_alive():
            if _engine.source.name == kind or (kind.startswith("browser") and _engine.source.name == "browser"):
                if kind.startswith("browser"):
                    _engine.source.device = kind.split(":", 1)[-1]
                return
            _engine.stop()
            _engine.join(timeout=2)
        cfg = get_cfg()
        _last_error = None
        try:
            if kind == "test":
                src = TestSource()
            elif kind in ("loopback", "mic"):
                src = PyAudioSource(kind, cfg["audio_device"])
            elif kind.startswith("browser"):
                src = BrowserSource(kind.split(":", 1)[-1])
            else:
                raise ValueError(f"unknown source '{kind}'")
        except ImportError:
            _last_error = ("pyaudiowpatch is not installed (Windows only). "
                           "Run: pip install -r requirements.txt  - or pick the Test signal source.")
            return
        except Exception as e:
            _last_error = f"{type(e).__name__}: {e}"
            return
        _engine = Engine(src)
        _engine.start()


def stop_engine():
    with _engine_lock:
        if _engine:
            _engine.stop()
            _engine.join(timeout=2)


def engine_status():
    if _engine and (_engine.is_alive() or _engine.status.get("error")):
        return dict(_engine.status)
    return {"running": False, "error": _last_error, "device": None, "source": None}


def engine_running():
    return bool(_engine and _engine.is_alive())


# --------------------------------------------------------------------------- HTTP

def check_ip(ip):
    ipaddress.ip_address(ip)   # raises ValueError on junk
    return ip


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # keep the console quiet; the UI polls /api/status constantly

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode()) if n else {}

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            body = INDEX_PATH.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/api/config":
            self._json(get_cfg())
        elif self.path == "/api/status":
            self._json(engine_status())
        elif self.path == "/api/devices":
            self._json(list_devices())
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        # Refuse requests from other web pages open in the browser (cross-site POSTs).
        origin = self.headers.get("Origin")
        if origin and origin.split("://", 1)[-1] != self.headers.get("Host"):
            return self._json({"error": "cross-origin request refused"}, 403)
        try:
            data = self._body()
            path = self.path
            if path == "/api/config":
                self._json(update_cfg(data))
            elif path == "/api/frame":
                if engine_running() and isinstance(_engine.source, BrowserSource):
                    _engine.source.feed(data)
                    self._json({"ok": True})
                else:
                    self._json({"ok": False, "error": "browser source not active"})
            elif path == "/api/color":
                cfg = update_cfg({k: data[k] for k in ("color", "brightness") if k in data})
                if not engine_running():
                    p = color_params(cfg["color"], cfg["brightness"])
                    for ip in target_bulbs(cfg):
                        wiz_send(ip, "setPilot", p)
                self._json({"ok": True})
            elif path == "/api/power":
                for ip in target_bulbs(get_cfg()):
                    wiz_send(ip, "setState", {"state": bool(data.get("on"))})
                self._json({"ok": True})
            elif path == "/api/light":
                ip = check_ip(data["ip"])
                if "on" in data:
                    wiz_send(ip, "setState", {"state": bool(data["on"])})
                if "color" in data:
                    wiz_send(ip, "setPilot", color_params(data["color"], data.get("brightness", 100)))
                self._json({"ok": True})
            elif path == "/api/group":
                # body: {"group": "Kitchen", "on"?: bool, "color"?: "#rrggbb", "brightness"?: int}
                ips = group_bulbs(get_cfg(), str(data.get("group", "")))
                for ip in ips:
                    if "on" in data:
                        wiz_send(ip, "setState", {"state": bool(data["on"])})
                    if "color" in data:
                        wiz_send(ip, "setPilot", color_params(data["color"], data.get("brightness", 100)))
                self._json({"ok": True, "bulbs": ips})
            elif path == "/api/identify":
                # body: {"ips": [...]} or {"group": "Kitchen"}
                ips = data.get("ips") or group_bulbs(get_cfg(), str(data.get("group", "")))
                for ip in ips:
                    threading.Thread(target=identify, args=(check_ip(ip),), daemon=True).start()
                self._json({"ok": True, "bulbs": ips})
            elif path == "/api/bulbs/meta":
                # body: {"ip", "name"?, "group"?}
                cfg = get_cfg()
                ip = check_ip(data["ip"])
                meta = dict(cfg["bulb_meta"])
                m = dict(meta.get(ip) or {})
                for k in ("name", "group"):
                    if k in data:
                        m[k] = str(data[k]).strip()[:60]
                meta[ip] = m
                present = {(meta.get(b) or {}).get("group", "") for b in cfg["bulbs"]}
                groups = {g for g in cfg["active_groups"] if g in present}   # drop rooms that no longer exist
                self._json(update_cfg({"bulb_meta": meta, "active_groups": sorted(groups)}))
            elif path == "/api/bulbs/remove":
                cfg = get_cfg()
                ip = data.get("ip")
                meta = {k: v for k, v in cfg["bulb_meta"].items() if k != ip}
                self._json(update_cfg({"bulbs": [b for b in cfg["bulbs"] if b != ip], "bulb_meta": meta}))
            elif path == "/api/engine/start":
                start_engine(data.get("source") or get_cfg()["audio_source"])
                time.sleep(0.2)
                self._json(engine_status())
            elif path == "/api/engine/stop":
                stop_engine()
                self._json(engine_status())
            elif path == "/api/ping":
                bulbs = get_cfg()["bulbs"]
                replies = wiz_query(bulbs, "getPilot")
                self._json({ip: replies.get(ip) for ip in bulbs})
            elif path == "/api/discover":
                patch = {k: data[k] for k in ("scan_subnets", "scan_local") if k in data}
                if "scan_subnets" in patch:
                    patch["scan_subnets"] = [str(n) for n in parse_targets(patch["scan_subnets"])]
                cfg = update_cfg(patch)
                result = discover(cfg["scan_subnets"], cfg["scan_local"], cfg["broadcast"])
                # flag each device as configured / new / moved (same MAC, new DHCP address)
                macs = cfg["bulb_macs"]
                for d in result["found"]:
                    old = macs.get(d["mac"])
                    d.update(cfg["bulb_meta"].get(d["ip"]) or cfg["bulb_meta"].get(old or "") or {})
                    if d["ip"] in cfg["bulbs"]:
                        d["status"] = "configured"
                        macs[d["mac"]] = d["ip"]
                    elif old and old in cfg["bulbs"]:
                        d["status"], d["was"] = "moved", old
                    else:
                        d["status"] = "new"
                update_cfg({"bulb_macs": macs})
                self._json(result)
            elif path == "/api/bulbs/add":
                # body: {"devices": [{"ip", "mac", "was"?}]} - adds new ones, swaps moved ones in place
                cfg = get_cfg()
                bulbs, macs, meta = list(cfg["bulbs"]), dict(cfg["bulb_macs"]), dict(cfg["bulb_meta"])
                for d in data.get("devices", []):
                    ip = check_ip(d["ip"])
                    was = d.get("was")
                    if was in bulbs:
                        bulbs[bulbs.index(was)] = ip
                        if was in meta:
                            meta[ip] = meta.pop(was)   # name and room follow the bulb to its new IP
                    elif ip not in bulbs:
                        bulbs.append(ip)
                    if d.get("mac"):
                        macs[str(d["mac"])] = ip
                    m = dict(meta.get(ip) or {})
                    for k in ("name", "group"):
                        if str(d.get(k) or "").strip():
                            m[k] = str(d[k]).strip()[:60]
                    if m:
                        meta[ip] = m
                self._json(update_cfg({"bulbs": bulbs, "bulb_macs": macs, "bulb_meta": meta}))
            else:
                self._json({"error": "not found"}, 404)
        except Exception as e:
            self._json({"error": f"{type(e).__name__}: {e}"}, 400)


def main():
    if not CONFIG_PATH.exists():
        update_cfg({})
    ips = [a for a in sys.argv[1:] if not a.startswith("--")]
    if ips:   # optional: python app.py 192.168.1.50 192.168.1.51 ...
        update_cfg({"bulbs": ips})
    cfg = get_cfg()
    srv = ThreadingHTTPServer((cfg["http_host"], cfg["http_port"]), Handler)
    print(f"WiZ PoC running on http://{cfg['http_host']}:{cfg['http_port']}  "
          f"({len(cfg['bulbs'])} bulbs configured)  - Ctrl+C to quit")
    if "--no-browser" not in sys.argv:
        webbrowser.open(f"http://127.0.0.1:{cfg['http_port']}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop_engine()


if __name__ == "__main__":
    warnings.simplefilter("ignore")
    main()
