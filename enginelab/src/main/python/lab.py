"""
Engine lab: the Kotlin side drives sonorium.engine through these few calls
(shared/engine.md). PCM crosses to Kotlin as little-endian int16 bytes.
"""
import os
import time
from pathlib import Path

os.environ.setdefault("SONORIUM_LOG_LEVEL", "warning")

from sonorium.engine import Engine, PcmFileDecoder, mirror_lookup  # noqa: E402
from engine_theme import FAST_VALUES, make_theme  # noqa: E402


def make_test_theme(root):
    """The engine tests' generated theme (sine/noise WAVs, a group, Ebb & Flow, Intermittent)."""
    return str(make_theme(Path(root)))


class Runner:
    """One engine. `cache_dir` holds the predecoded WAVs (None: the theme's own 16-bit WAVs)."""

    def __init__(self, theme_dir, cache_dir=None, preset_id=None, seed=1, fast=False):
        lookup = mirror_lookup(theme_dir, cache_dir) if cache_dir else None
        self.engine = Engine(
            theme_dir,
            preset_id=preset_id,
            playback_values=FAST_VALUES if fast else None,
            seed=int(seed),
            decoder=PcmFileDecoder(lookup=lookup),
        )

    def render(self, n):
        return self.engine.render(int(n)).tobytes()

    def name(self):
        return self.engine.theme_name

    def tracks(self):
        return len(self.engine.tracks)

    def close(self):
        self.engine.close()


def bench(theme_dir, cache_dir=None, seconds=60, block=4096, fast=False):
    """CPU seconds (this thread) to render `seconds` of audio in `block`-sample calls."""
    runner = Runner(theme_dir, cache_dir, fast=fast)
    total = int(seconds * 44100)
    start = time.thread_time()
    wall = time.perf_counter()
    done = 0
    while done < total:
        n = min(block, total - done)
        runner.engine.render(n)
        done += n
    cpu = time.thread_time() - start
    elapsed = time.perf_counter() - wall
    tracks = runner.tracks()
    runner.close()
    return f"{cpu:.2f} s CPU ({elapsed:.2f} s wall) for {seconds} s of audio, {tracks} tracks"
