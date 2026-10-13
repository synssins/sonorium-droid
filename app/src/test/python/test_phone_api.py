"""
phone_api.py against Core's theme_edit: each request made through handle() on
a theme laid out as the phone prepares it (metadata.json/presets.json copied,
empty stand-ins for the audio, decoded WAVs beside it) leaves metadata.json and
presets.json exactly as the same theme_edit call leaves them on an untouched
copy of the theme. Not shipped; run from the repository root:

    python3 -I app/src/test/python/test_phone_api.py
"""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
sys.path[:0] = [str(REPO / "app/src/main/python"), str(REPO / "enginelab/src/main/python")]

import phone_api  # noqa: E402
from engine_theme import make_theme  # noqa: E402
from sonorium.core import theme_edit as te  # noqa: E402

FILES = ("metadata.json", "presets.json")


class PhoneApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        source = make_theme(self.tmp / "source")
        self.folder = source.name
        # The phone's prepared copy: the JSON files, an empty stand-in per audio file, decoded audio beside it
        self.root = self.tmp / "engine" / "themes"
        self.pcm_root = self.tmp / "engine" / "pcm"
        theme = self.root / self.folder
        for path in source.rglob("*"):
            if path.is_dir():
                continue
            rel = path.relative_to(source)
            (theme / rel).parent.mkdir(parents=True, exist_ok=True)
            if path.suffix == ".json":
                shutil.copy(path, theme / rel)
            else:
                (theme / rel).touch()
                (self.pcm_root / self.folder / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(path, (self.pcm_root / self.folder / rel).with_suffix(".wav"))
        self.theme = theme
        # The same theme edited with theme_edit directly (the server's way)
        self.reference = self.tmp / "reference" / self.folder
        shutil.copytree(source, self.reference)
        # The user's themes folder (real audio files): the app repeats the returned "ops" there
        self.user = self.tmp / "user" / self.folder
        shutil.copytree(source, self.user)
        self.ops = []

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def call(self, method, segments, body=None, query=None, theme_id=None):
        path = json.dumps(["themes", theme_id or self.folder] + list(segments))
        answer = json.loads(phone_api.handle(method, path, json.dumps(query or {}), json.dumps(body) if body is not None else "",
                                             str(self.root), str(self.pcm_root), self.folder))
        self.assertNotIn("ops", answer["body"] if isinstance(answer["body"], dict) else {})
        self.ops = answer.get("ops")
        if self.ops:
            self.replay(self.ops)
        return answer["status"], answer["body"]

    def replay(self, ops):
        """What PhoneThemeApi does with "ops" in the user's folder (ThemeStore.applyOps)."""
        for op in ops:
            kind, src, dst = op["op"], op["src"], op["dst"]
            if kind == "mkdir":
                (self.user / dst).mkdir(parents=True, exist_ok=True)
            elif kind == "move":
                (self.user / dst).parent.mkdir(parents=True, exist_ok=True)
                (self.user / src).rename(self.user / dst)
            elif kind in ("write", "copy"):
                (self.user / dst).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(self.theme / dst, self.user / dst)
            elif kind == "delete":
                target = self.user / src
                shutil.rmtree(target) if target.is_dir() else target.unlink()
            else:
                self.fail(f"unknown op {op}")

    def same_user_folder(self):
        """The user's folder after the replayed ops: the reference's files, and its JSON byte for byte."""
        def listing(folder):
            return sorted(p.relative_to(folder).as_posix() for p in folder.rglob("*"))
        self.assertEqual(listing(self.reference), listing(self.user))
        for name in FILES:
            self.assertEqual((self.reference / name).read_bytes(), (self.user / name).read_bytes(), name)

    def same_files(self):
        for name in FILES:
            mine, ref = self.theme / name, self.reference / name
            self.assertEqual(ref.read_bytes(), mine.read_bytes(), f"{name} differs from theme_edit's")

    def test_tracks_and_track_settings(self):
        status, body = self.call("GET", ["tracks"])
        self.assertEqual(200, status)
        names = [t["name"] for t in body["tracks"]]
        self.assertEqual(sorted(names, key=str.lower), names)
        self.assertIn("Birds/Bird 1", names)
        drone = next(t for t in body["tracks"] if t["name"] == "Drone")
        self.assertEqual(0.8, drone["volume"])
        self.assertEqual("continuous", drone["playback_mode"])
        self.assertAlmostEqual(6.0, drone["duration_seconds"], places=1)  # from the decoded audio, not the stand-in
        self.assertEqual("Birds", next(t for t in body["tracks"] if t["name"] == "Birds/Bird 2")["group"])
        # The theme id from metadata.json works too
        self.assertEqual(200, self.call("GET", ["tracks"], theme_id="engine-test-theme")[0])

        status, body = self.call("PUT", ["tracks", "Drone", "volume"], {"volume": 0.4})
        self.assertEqual((200, {"status": "ok", "track": "Drone", "volume": 0.4}), (status, body))
        te.set_track(self.reference, "Drone", volume=0.4)
        self.same_files()

        status, body = self.call("PUT", ["tracks", "Wind", "muted"], {"muted": True})
        self.assertEqual({"status": "ok", "track": "Wind", "muted": True}, body)
        te.set_track(self.reference, "Wind", muted=True)
        self.same_files()

        status, body = self.call("PUT", ["tracks", "Chirp", "playback_mode"], {"playback_mode": "presence"})
        self.assertEqual("presence", body["playback_mode"])
        te.set_track(self.reference, "Chirp", playback_mode="presence")
        self.same_files()

        status, body = self.call("PUT", ["tracks", "Chirp", "ebb"], {"volume_low": 0.3, "pause_between": True})
        self.assertEqual((0.3, True), (body["volume_low"], body["pause_between"]))
        te.set_track(self.reference, "Chirp", volume_low=0.3, pause_between=True)
        self.same_files()

        status, body = self.call("PUT", ["tracks", "Chirp", "fades"], {"fade_in": 2})
        self.assertEqual(2.0, body["fade_in"])
        te.set_track(self.reference, "Chirp", fade_in=2)
        self.same_files()

        # Errors as the server answers them
        self.assertEqual((404, "Track not found"), self.err("PUT", ["tracks", "Nope", "volume"], {"volume": 0.5}))
        self.assertEqual((400, "Volume is required"), self.err("PUT", ["tracks", "Drone", "volume"], {}))
        self.assertEqual(501, self.call("POST", ["tracks", "Drone", "move"], {"group": "Birds"})[0])
        self.same_files()

        self.assertEqual(200, self.call("POST", ["tracks", "reset"])[0])
        te.reset_theme_tracks(self.reference)
        self.same_files()

    def err(self, method, segments, body=None):
        status, answer = self.call(method, segments, body)
        return status, answer["detail"]

    def test_groups(self):
        status, body = self.call("GET", ["groups"])
        self.assertEqual(200, status)
        birds = body["groups"][0]
        self.assertEqual("Birds", birds["name"])
        self.assertEqual(["Birds/Bird 1", "Birds/Bird 2", "Birds/Bird 3"], birds["tracks"])
        self.assertEqual({"mode": "intermittent", "gap_min": 1.0, "gap_max": 3.0}, birds["settings"])
        status, body = self.call("PUT", ["groups", "Birds"], {"volume": 0.5, "gap_min": 10, "gap_max": 5})
        self.assertEqual(200, status)
        self.assertEqual({"mode": "intermittent", "gap_min": 5.0, "gap_max": 10.0, "volume": 0.5}, body["settings"])
        te.set_group(self.reference, "Birds", volume=0.5, gap_min=10, gap_max=5)
        self.same_files()
        self.assertEqual((404, "Group not found"), self.err("PUT", ["groups", "Nope"], {"volume": 1}))

        # Group create, rename, delete: Core's folder functions; their ops repeated in the user's folder
        self.assertEqual((201, {"name": "Fire"}), self.call("POST", ["groups"], {"name": "Fire"}))
        self.assertEqual([{"op": "mkdir", "src": None, "dst": "Fire"}], self.ops)
        te.create_group(self.reference, "Fire")
        self.same_files()
        status, body = self.call("POST", ["groups", "Birds", "rename"], {"name": "Flock"})
        self.assertEqual((200, "Flock"), (status, body["name"]))
        self.assertEqual("Flock/Bird 1", body["tracks"]["Birds/Bird 1"])
        self.assertEqual({"op": "move", "src": "Birds", "dst": "Flock"}, self.ops[0])
        te.rename_group(self.reference, "Birds", "Flock")
        self.same_files()
        status, body = self.call("DELETE", ["groups", "Flock"])
        self.assertEqual(200, status)
        self.assertTrue(body["folder_removed"])
        te.delete_group(self.reference, "Flock")
        self.same_files()
        self.assertEqual((409, 400), (self.call("POST", ["groups"], {"name": "Fire"})[0], self.call("POST", ["groups"], {"name": ""})[0]))
        shutil.rmtree(self.reference / "Fire")
        (self.theme / "Fire").rmdir()
        (self.user / "Fire").rmdir()
        self.same_user_folder()

    def test_upload_and_links(self):
        # Upload: the app saved the file it was sent; Core copies it in (a "copy" op) and makes it Unassigned
        sent = self.tmp / "sent.wav"
        shutil.copyfile(self.reference / "Chirp.wav", sent)
        status, body = self.call("POST", ["upload"], {"filename": "Hum.wav", "path": str(sent)}, query={"group": "Hums"})
        self.assertEqual((200, "Hums/Hum", "Hums"), (status, body["track"], body["group"]))
        self.assertEqual(["mkdir", "copy", "write"], [o["op"] for o in self.ops][:3])
        te.upload(self.reference, "Hum.wav", sent, "Hums")
        self.same_files()
        self.same_user_folder()
        self.assertEqual(400, self.call("POST", ["upload"], {"filename": "notes.txt", "path": str(sent)})[0])

        # Links: another theme on the phone as the source
        other = self.root / "Other"
        shutil.copytree(self.theme, other, ignore=shutil.ignore_patterns("Hums"))
        meta = json.loads((other / "metadata.json").read_text(encoding="utf-8"))
        meta["id"] = "other-theme"
        (other / "metadata.json").write_text(json.dumps(meta), encoding="utf-8")
        shutil.copytree(other, self.tmp / "reference" / "Other")
        status, body = self.call("GET", ["links", "source", "other-theme"])
        self.assertEqual(200, status, body)
        self.assertIn("Drone", [t["id"] for t in body["tracks"]])
        status, body = self.call("POST", ["links"], {"theme": "other-theme", "tracks": ["Drone"]})
        self.assertEqual(201, status, body)
        key = body["links"][0]["key"]
        te.add_links(self.reference, self.tmp / "reference" / "Other", ["Drone"])
        self.same_files()
        self.assertEqual((404, "Source theme not found"), self.err("POST", ["links"], {"theme": "nope", "tracks": ["Drone"]}))
        self.assertEqual(200, self.call("DELETE", ["links", key])[0])
        te.remove_link(self.reference, key)
        self.same_files()
        self.same_user_folder()

    def test_presets(self):
        status, body = self.call("GET", ["presets"])
        self.assertEqual(200, status)
        self.assertEqual(["calm", "busy"], [p["id"] for p in body["presets"]])
        self.assertTrue(next(p for p in body["presets"] if p["id"] == "busy")["is_default"])
        self.assertEqual([], body["sequences"])

        self.call("PUT", ["tracks", "Drone", "volume"], {"volume": 0.25})
        te.set_track(self.reference, "Drone", volume=0.25)
        status, body = self.call("POST", ["presets"], {"name": "Evening"})
        self.assertEqual({"status": "ok", "preset_id": "evening", "name": "Evening", "is_default": False}, body)
        te.new_preset(self.reference, "Evening")
        self.same_files()

        self.call("PUT", ["tracks", "Drone", "volume"], {"volume": 0.6})
        te.set_track(self.reference, "Drone", volume=0.6)
        status, body = self.call("PUT", ["presets", "evening"])
        self.assertEqual((200, "Evening"), (status, body["name"]))
        te.save_preset(self.reference, "evening")
        self.same_files()
        self.assertEqual(0.6, self.call("GET", ["presets", "evening"])[1]["tracks"]["Drone"]["volume"])

        self.assertEqual({"status": "ok", "preset_id": "evening", "is_default": True}, self.call("PUT", ["presets", "evening", "default"])[1])
        te.set_default_preset(self.reference, "evening")
        self.same_files()

        self.assertEqual("Late", self.call("PUT", ["presets", "evening", "rename"], {"name": " Late "})[1]["name"])
        te.rename_preset(self.reference, "evening", "Late")
        self.same_files()

        status, body = self.call("POST", ["presets", "calm", "load"])
        self.assertEqual((200, "calm"), (status, body["preset_id"]))
        te.load_preset(self.reference, "calm")
        self.same_files()

        exported = self.call("GET", ["presets", "calm", "export"])[1]
        self.assertEqual("Calm", exported["name"])
        status, body = self.call("POST", ["presets", "import"], {"preset_json": json.dumps({**exported, "tracks": {**exported["tracks"], "Gone": {}}}), "name": None})
        self.assertEqual((200, "calm_1"), (status, body["preset_id"]))
        self.assertEqual(["Gone"], body["unknown_tracks"])

        self.assertEqual({"status": "ok", "preset_id": "evening"}, self.call("DELETE", ["presets", "evening"])[1])
        self.assertEqual((404, "Preset not found"), self.err("DELETE", ["presets", "evening"]))

    def test_sequences_and_details(self):
        status, body = self.call("POST", ["sequences"], {"name": "Night", "presets": ["calm", "busy"]})
        self.assertEqual((201, "night"), (status, body["id"]))
        te.create_sequence(self.reference, {"name": "Night", "presets": ["calm", "busy"]})
        self.same_files()
        self.assertEqual(["night"], [s["id"] for s in self.call("GET", ["presets"])[1]["sequences"]])
        self.assertEqual(409, self.call("POST", ["sequences"], {"name": "night", "presets": ["calm"]})[0])
        status, body = self.call("PUT", ["sequences", "night", "while", "calm"], {"tracks": {"Drone": {"volume": 0.5}}})
        self.assertEqual(200, status)
        self.assertEqual(["calm"], self.call("GET", ["sequences", "night"])[1]["while_steps"])
        status, body = self.call("GET", ["transition_tracks"], query={"preset": "busy", "prev": "calm", "next": "calm"})
        self.assertEqual(200, status, body)
        self.assertIn("Wind", [r["key"] for r in body["in"]])  # calm mutes Wind, busy plays it
        self.assertEqual(200, self.call("PUT", ["sequence_defaults"], {"ramp": 12})[0])
        self.assertEqual(12, self.call("GET", ["sequence_defaults"])[1]["ramp"])
        self.assertEqual(200, self.call("DELETE", ["sequences", "night"])[0])

        self.assertEqual("Tavern", self.call("PUT", ["rename"], {"name": "Tavern"})[1]["new_name"])
        self.assertEqual(200, self.call("PUT", ["metadata"], {"description": "Warm", "icon": "🔥"})[0])
        self.assertEqual(["Indoors"], self.call("POST", ["categories"], {"categories": ["Indoors"]})[1]["categories"])
        meta = json.loads((self.theme / "metadata.json").read_text(encoding="utf-8"))
        self.assertEqual(("Tavern", "Warm", "🔥", ["Indoors"]), (meta["name"], meta["description"], meta["icon"], meta["categories"]))
        answer = json.loads(phone_api.handle("GET", "categories", "", "", str(self.root), str(self.pcm_root), self.folder))
        self.assertEqual({"status": 200, "body": {"categories": ["Indoors"]}}, answer)
        answer = json.loads(phone_api.handle("GET", "settings/playback-values", "", "", str(self.root), str(self.pcm_root), self.folder))
        self.assertIn("group_crossfade_max", answer["body"]["values"])

    def test_not_here(self):
        for method, segments in (("POST", ["tracks", "Drone", "move"]), ("POST", ["links", "Drone", "copy-in"])):
            status, body = self.call(method, segments, {})
            self.assertEqual(501, status, segments)
            self.assertEqual(phone_api.NOT_HERE, body["error"])
        self.assertEqual(404, self.call("GET", ["tracks"], theme_id="nope")[0])

    def test_opening_writes_nothing(self):
        # Themes open without converting: "auto" stays "auto" (the server converts it with the real files),
        # nothing is written, and no decoded audio is needed
        meta_path = self.theme / "metadata.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        meta["auto_retired"] = False
        meta["tracks"]["Drone"]["playback_mode"] = "auto"
        meta_path.write_text(json.dumps(meta), encoding="utf-8")
        (self.pcm_root / self.folder / "Drone.wav").unlink()
        before = meta_path.read_bytes()
        status, body = self.call("GET", ["tracks"])
        self.assertEqual(200, status)
        self.assertEqual("auto", next(t for t in body["tracks"] if t["name"] == "Drone")["playback_mode"])
        self.assertEqual(before, meta_path.read_bytes())


if __name__ == "__main__":
    unittest.main(verbosity=2)
