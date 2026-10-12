"""
Playing a theme on the phone: the Kotlin side drives sonorium.engine through these calls.
PCM crosses to Kotlin as little-endian int16 bytes (44100 Hz mono).
"""
import json
import os

os.environ.setdefault("SONORIUM_LOG_LEVEL", "warning")

from sonorium import playback_values as _values  # noqa: E402
from sonorium.engine import Engine, PcmFileDecoder, mirror_lookup  # noqa: E402

MARKER = ".sonorium-package.json"


def package_values(theme_dir):
    """The server's Advanced values saved with a downloaded theme (known keys only), else {}."""
    try:
        with open(os.path.join(theme_dir, MARKER), encoding="utf-8") as f:
            values = json.load(f).get("playback_values") or {}
        values = {k: v for k, v in values.items() if k in _values.DEFAULTS}
        _values.validate(values)
        return values
    except (OSError, ValueError, AttributeError):
        return {}


class Player:
    """One engine on a prepared theme folder; `cache_dir` holds its predecoded WAVs."""

    def __init__(self, theme_dir, cache_dir, preset_id=None, volume=1.0, seed=0):
        self.engine = Engine(
            theme_dir,
            preset_id=preset_id or None,
            playback_values=package_values(theme_dir),
            seed=int(seed),
            decoder=PcmFileDecoder(lookup=mirror_lookup(theme_dir, cache_dir)),
        )
        self.engine.set_volume(float(volume))

    def render(self, n):
        return self.engine.render(int(n)).tobytes()

    def set_preset(self, preset_id):
        self.engine.set_preset(preset_id or None)

    def set_volume(self, volume):
        self.engine.set_volume(float(volume))

    def name(self):
        return self.engine.theme_name

    def presets(self):
        """[{"id", "name", "is_default"}] as JSON."""
        out = []
        for pid, data in (self.engine.presets or {}).items():
            data = data or {}
            out.append({"id": pid, "name": data.get("name") or pid, "is_default": bool(data.get("is_default"))})
        return json.dumps(out)

    def close(self):
        self.engine.close()
