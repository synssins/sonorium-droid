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
    """
    One engine on prepared theme folders: each theme's `cache_dir` holds its predecoded WAVs.
    set_theme() crossfades to another prepared theme while playing.
    """

    def __init__(self, theme_dir, cache_dir, preset_id=None, volume=1.0, seed=0, sequence_id=None):
        self._mirrors = [mirror_lookup(theme_dir, cache_dir)]
        self._roots = [os.path.realpath(theme_dir)]
        # Linked files and playlist entries: other prepared themes beside it, their audio beside its own
        themes_root, pcm_root = os.path.dirname(os.path.realpath(theme_dir)), os.path.dirname(os.path.realpath(cache_dir))
        self._roots.append(themes_root)
        self._mirrors.append(mirror_lookup(themes_root, pcm_root))
        self.engine = Engine(
            theme_dir,
            preset_id=preset_id or None,
            sequence_id=sequence_id or None,
            playback_values=package_values(theme_dir),
            seed=int(seed),
            decoder=PcmFileDecoder(lookup=self._lookup),
        )
        self.engine.set_volume(float(volume))

    def _lookup(self, path):
        real = os.path.realpath(str(path))
        for root, mirror in zip(self._roots, self._mirrors):
            if real == root or real.startswith(root + os.sep):
                return mirror(path)
        return path

    def set_theme(self, theme_dir, cache_dir, preset_id=None, sequence_id=None):
        """Crossfades to another prepared theme (its default preset when preset_id is None)."""
        root = os.path.realpath(theme_dir)
        if root not in self._roots:
            self._roots.insert(0, root)
            self._mirrors.insert(0, mirror_lookup(theme_dir, cache_dir))
        self.engine.set_theme(theme_dir, preset_id=preset_id, sequence_id=sequence_id or None)

    def start_playlist(self, playlist_json, themes_root, pcm_root):
        """
        Plays a playlist (the server's JSON) from its first entry. Its themes are prepared copies under
        themes_root, their decoded audio in the same layout under pcm_root.
        """
        root = os.path.realpath(themes_root)
        if root not in self._roots:
            # After every theme's own mirror: any prepared theme's files map to its decoded audio
            self._roots.append(root)
            self._mirrors.append(mirror_lookup(themes_root, pcm_root))
        return json.dumps(self.engine.start_playlist(json.loads(playlist_json), themes_root=themes_root), default=str)

    def stop_playlist(self):
        return bool(self.engine.stop_playlist())

    def set_audio(self, crossfade, gain):
        """Audio Settings on this device: the crossfade between themes (s) and the master output gain (%)."""
        self.engine.crossfade_seconds = max(0.0, float(crossfade))
        self.engine.set_master_gain(float(gain))

    def sequences(self):
        """[{"id", "name"}] as JSON."""
        return json.dumps([{"id": sid, "name": name or sid} for sid, name in (self.engine.sequences or {}).items()])

    def start_sequence(self, sequence_id):
        self.engine.start_sequence(sequence_id)

    def stop_sequence(self):
        self.engine.stop_sequence()

    def next(self):
        return self.engine.next() or ""

    def previous(self):
        return self.engine.previous() or ""

    def status(self):
        """engine.status() as JSON (the running sequence's step and seconds left)."""
        return json.dumps(self.engine.status(), default=str)

    def render(self, n):
        return self.engine.render(int(n)).tobytes()

    def set_preset(self, preset_id):
        self.engine.set_preset(preset_id or None)

    def set_volume(self, volume):
        self.engine.set_volume(float(volume))

    def reload_theme(self):
        """After the Theme Editor changed the theme's files (phone_api.py): the playing mix follows them."""
        self.engine.reload_theme()

    def name(self):
        return self.engine.theme_name

    def presets(self):
        """[{"id", "name", "is_default"}] as JSON."""
        default = self.engine._folder.default_preset()
        out = [{"id": pid, "name": name or pid, "is_default": pid == default} for pid, name in (self.engine.presets or {}).items()]
        return json.dumps(out)

    def close(self):
        self.engine.close()
