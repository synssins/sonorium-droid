"""
Playing a theme on the phone: the Kotlin side drives sonorium.engine through these calls.
PCM crosses to Kotlin as little-endian int16 bytes, 44100 Hz, always two channels (interleaved L/R
frames): a stereo mix as it is, a mono one (Stereo off) on both sides.
"""
import json
import os

os.environ.setdefault("SONORIUM_LOG_LEVEL", "warning")

from sonorium import playback_values as _values  # noqa: E402
from sonorium.engine import Engine  # noqa: E402

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


class _LiveAudio:
    """One file read live through the device's decoder (app.sonorium.audio.LiveDecoder.Stream)."""

    def __init__(self, stream, channels):
        self._stream = stream
        self.channels = channels
        self._closed = False

    @property
    def has_audio(self):
        return True

    def header_length(self):
        frames = int(self._stream.getHeaderFrames())
        return frames if frames >= 0 else None

    def blocks(self, flush=True):
        import numpy as np
        try:
            while True:
                chunk = self._stream.read()
                if chunk is None:
                    break
                block = np.frombuffer(bytes(chunk), dtype="<i2")
                if self.channels == 2:
                    block = block.reshape(-1, 2).T
                if block.size:
                    yield block
        finally:
            self.close()

    def close(self):
        if not self._closed:
            self._closed = True
            self._stream.close()


class _NoAudio:
    """A file the device's decoder can't open: nothing to play (the engine leaves it out)."""
    has_audio = False

    def header_length(self):
        return None

    def blocks(self, flush=True):
        return iter(())

    def close(self):
        pass


class LiveDecoder:
    """
    Core's decoder hook (sonorium.decoders): theme files read live while the engine mixes, by the
    device's own decoder (MediaCodec), 44100 Hz, mono or stereo as the mix asks. A prepared copy's
    stand-in names the real file (a document in the user's themes folder); a real file is read as it is.
    """
    accepts_channels = True

    def open(self, path, channels=1):
        from java import jclass
        try:
            stream = jclass("app.sonorium.audio.LiveDecoder").open(str(path), int(channels))
        except Exception as e:  # noqa: BLE001  (a file the device can't read is silent, never the end of the mix)
            import logging
            logging.getLogger("sonorium.phone").warning("Can't read %s: %s", path, e)
            return _NoAudio()
        return _LiveAudio(stream, 2 if channels >= 2 else 1)

    def __repr__(self):
        return "LiveDecoder()"


def live_seconds(path):
    """A file's length from its header (the device's extractor), or None."""
    try:
        from java import jclass
        seconds = float(jclass("app.sonorium.audio.LiveDecoder").durationSeconds(str(path)))
        return seconds if seconds >= 0 else None
    except Exception:  # noqa: BLE001  (off the device: no Java)
        return None


class Player:
    """
    One engine on prepared theme folders (settings and stand-ins); the files are read live while it mixes.
    set_theme() crossfades to another prepared theme while playing. cache_dir is no longer used.
    """

    def __init__(self, theme_dir, cache_dir=None, preset_id=None, volume=1.0, seed=0, sequence_id=None, stereo=True):
        self.engine = Engine(
            theme_dir,
            preset_id=preset_id or None,
            sequence_id=sequence_id or None,
            playback_values=package_values(theme_dir),
            seed=int(seed),
            decoder=LiveDecoder(),
            channels=2,
        )
        # Always a stereo mix; Stereo off folds it to mono at the output (no theme is read again)
        if not stereo:
            self.engine.set_channels(1)
        self.engine.set_volume(float(volume))

    def set_theme(self, theme_dir, cache_dir=None, preset_id=None, sequence_id=None):
        """Crossfades to another prepared theme (its default preset when preset_id is None)."""
        self.engine.set_theme(theme_dir, preset_id=preset_id, sequence_id=sequence_id or None)

    def start_playlist(self, playlist_json, themes_root, pcm_root=None):
        """Plays a playlist (the server's JSON) from its first entry. Its themes are prepared copies under themes_root."""
        return json.dumps(self.engine.start_playlist(json.loads(playlist_json), themes_root=themes_root), default=str)

    def stop_playlist(self):
        return bool(self.engine.stop_playlist())

    def fade_out(self, seconds):
        """The whole mix fades to silence over `seconds` (as a channel's stop)."""
        self.engine.fade_out(float(seconds))

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
        """n frames as interleaved stereo int16 bytes (a folded mono mix on both sides)."""
        out = self.engine.render(int(n))
        if out.ndim == 1:
            import numpy as np
            out = np.repeat(out, 2)
        return out.tobytes()

    def set_stereo(self, on):
        self.engine.set_channels(2 if on else 1)

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
