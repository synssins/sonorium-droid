"""
The server's Theme Editor routes, answered on the phone for a theme folder.

The app's Theme Editor (server/ThemeEditor.kt) talks to /api/themes/... on a
server. For a theme on this phone, Kotlin (local/PhoneThemeApi.kt) sends the
same requests here instead, and every change is made by the same Core code the
server's routes call (sonorium.core.theme_edit, sequences, theme_metadata), so
the files come out as the server writes them and the answers have the
server's JSON shapes.

    handle(method, path, query, body, themes_root, pcm_root=None, current=None)
        -> '{"status": 200, "body": {...}}'

- path: the part after /api/, as a JSON list of decoded path segments
  ('["themes", "Rain", "tracks", "Birds/Robin", "volume"]': a track key holds
  a "/"), or a plain "themes/Rain/tracks" string when no segment holds one.
- query: JSON object of query parameters (or ""); body: the JSON body text (or "").
- themes_root: the folder holding the prepared theme copies (files/engine/themes);
  a theme id is its metadata.json "id", else its folder name.
- pcm_root: the decoded audio beside them (files/engine/pcm), for file lengths.
- current: the folder name of the theme being edited (for settings/playback-values).

What the phone can't do yet answers 501 {"detail"/"error": NOT_HERE}: upload,
linked files (add/remove/copy in), group create/rename/delete, moving tracks
(these move audio files, which live in the user's themes folder) and the
server-wide category list changes.
"""
import json
import os
import re
from pathlib import Path

os.environ.setdefault("SONORIUM_LOG_LEVEL", "warning")

from sonorium import decoders  # noqa: E402
from sonorium import playback_values as _values  # noqa: E402
from sonorium.core import intrusions, sequences as seq, theme_edit as te, theme_groups, theme_presets  # noqa: E402
from sonorium.core import theme_metadata as tm  # noqa: E402

NOT_HERE = "Not available for themes on this phone yet"
OLD_FORMAT = "This theme is in an older format. Open it once on your server to update it, then edit it here."
NOT_READY = "This theme isn't ready yet. Play it once on this phone, then edit it."
MARKER = ".sonorium-package.json"
TRACK_SETTINGS = ("volume", "presence", "muted", "playback_mode", "seamless_loop", "exclusive")
SAMPLE_RATE = 44100


class _Http(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def handle(method, path, query, body, themes_root, pcm_root=None, current=None):
    """One request; returns the JSON text {"status": int, "body": ...}."""
    try:
        segments = json.loads(path) if str(path).startswith("[") else [s for s in str(path).strip("/").split("/") if s]
        params = json.loads(query) if query else {}
        data = json.loads(body) if body else None
        status, answer = _route(method.upper(), segments, params or {}, data, Path(themes_root),
                                Path(pcm_root) if pcm_root else None, current)
    except _Http as e:
        status, answer = e.status, _error(str(e))
    except (te.EditError,) as e:
        status, answer = e.status, _error(str(e))
    except theme_groups.GroupError as e:
        status, answer = getattr(e, "status", 400), _error(str(e))
    except seq.SequenceError as e:
        status, answer = 400, _error(str(e))
    except json.JSONDecodeError:
        status, answer = 400, _error("Invalid JSON body")
    except (TypeError, ValueError) as e:
        status, answer = 400, _error(f"Invalid value: {e}")
    except Exception as e:  # noqa: BLE001 - an answer, never a crash in the app
        status, answer = 500, _error(f"{type(e).__name__}: {e}")
    return json.dumps({"status": status, "body": answer}, default=str)


def _error(message):
    # "detail" as FastAPI answers errors (the app reads it), "error" as the older routes do
    return {"detail": message, "error": message}


def package_values(theme_dir):
    """The server's Advanced values saved with a downloaded theme (known keys only), else {} (as phone.py)."""
    try:
        with open(os.path.join(theme_dir, MARKER), encoding="utf-8") as f:
            values = json.load(f).get("playback_values") or {}
        values = {k: v for k, v in values.items() if k in _values.DEFAULTS}
        _values.validate(values)
        return values
    except (OSError, ValueError, AttributeError):
        return {}


def needs_audio(theme_dir, pcm_dir):
    """
    True when editing this theme needs its decoded audio first: Core's one-time
    conversion of "auto" playback modes reads the files' lengths (it would read
    the empty stand-ins wrong) and a decoded file is missing.
    """
    raw = _raw_metadata(Path(theme_dir))
    if raw is None or raw.get("auto_retired"):
        return False
    return any(not _wav(Path(theme_dir), Path(pcm_dir), p).exists() for p in _audio_files(Path(theme_dir)))


# --- routing -----------------------------------------------------------------------

def _route(method, s, query, data, root, pcm_root, current):
    if s == ["categories"] and method == "GET":
        return 200, {"categories": _all_categories(root)}
    if s == ["settings", "playback-values"] and method == "GET":
        values = package_values(str(root / current)) if current else {}
        with _values.use_values(values):
            return 200, {"values": _values.values(), "defaults": dict(_values.DEFAULTS)}
    if len(s) < 3 or s[0] != "themes":
        raise _Http(501, NOT_HERE)
    folder = _folder(root, s[1])
    pcm = (pcm_root / folder.name) if pcm_root else None
    raw = _raw_metadata(folder)
    if raw is not None and not raw.get("intrusions_retired") and any(
            isinstance(g, dict) and g.get("mode") == "intrusion" for g in (raw.get("groups") or {}).values()):
        raise _Http(409, OLD_FORMAT)  # Core's conversion moves audio files: the server does it
    if pcm is not None and needs_audio(folder, pcm):
        raise _Http(409, NOT_READY)
    decoder = decoders.PcmFileDecoder(lookup=decoders.mirror_lookup(folder, pcm)) if pcm else None
    with decoders.use_decoder(decoder), _values.use_values(package_values(str(folder))):
        return _theme_route(method, s[1], s[2:], query, data, folder, root, pcm)


def _theme_route(method, theme_id, s, query, data, folder, root, pcm):
    head = s[0]
    if head == "tracks":
        if s == ["tracks"] and method == "GET":
            return 200, _tracks(theme_id, folder, root, pcm)
        if s == ["tracks", "reset"] and method == "POST":
            te.reset_theme_tracks(folder)
            return 200, {"status": "ok", "theme_id": theme_id}
        if len(s) == 3 and method == "PUT":
            return 200, _set_track(folder, s[1], s[2], _object(data))
    elif head == "groups":
        if s == ["groups"] and method == "GET":
            return 200, _groups(folder, root)
        if len(s) == 2 and method == "PUT":
            settings = te.set_group(folder, s[1], **_object(data))
            return 200, {"name": s[1], "settings": settings}
    elif head == "rename" and method == "PUT" and len(s) == 1:
        return 200, _rename_theme(folder, _object(data))
    elif head == "metadata" and method == "PUT" and len(s) == 1:
        return 200, _set_metadata(folder, _object(data))
    elif head == "categories" and method == "POST" and len(s) == 1:
        metadata = te.load(folder)
        metadata.categories = list(_object(data).get("categories", []))
        te.save(folder, metadata)
        return 200, {"theme_id": theme_id, "categories": metadata.categories}
    elif head == "presets":
        return _preset_route(method, theme_id, s[1:], data, folder, root)
    elif head == "sequence_defaults" and len(s) == 1:
        if method == "GET":
            return 200, {"theme_id": theme_id, **seq.load_defaults(folder)}
        if method == "PUT":
            body = _object(data)
            unknown = set(body) - set(seq.TIMING_FIELDS)
            if unknown:
                raise _Http(400, f"Unknown field(s): {', '.join(sorted(unknown))}")
            return 200, {"theme_id": theme_id, **seq.save_defaults(folder, body)}
    elif head == "sequences":
        return _sequence_route(method, theme_id, s[1:], data, folder)
    elif head == "transition_tracks" and method == "GET" and len(s) == 1:
        return 200, _transition_tracks(theme_id, folder, root, query)
    raise _Http(501, NOT_HERE)


def _preset_route(method, theme_id, s, data, folder, root):
    if not s:
        if method == "GET":
            return 200, _presets(theme_id, folder, root)
        if method == "POST":
            name = data.get("name") if isinstance(data, dict) else None
            return 200, {"status": "ok", **te.new_preset(folder, name)}
    elif s == ["import"] and method == "POST":
        return 200, _import_preset(folder, _object(data))
    elif len(s) == 1:
        pid = s[0]
        if method == "GET":
            return 200, te.get_preset(folder, pid)
        if method == "PUT":
            saved = te.save_preset(folder, pid)
            return 200, {"status": "ok", "preset_id": pid, "name": saved["name"], "tracks_updated": len(saved["tracks"])}
        if method == "DELETE":
            te.delete_preset(folder, pid)
            return 200, {"status": "ok", "preset_id": pid}
    elif len(s) == 2:
        pid, action = s
        if action == "load" and method == "POST":
            loaded = te.load_preset(folder, pid)
            return 200, {"status": "ok", "preset_id": pid, "name": loaded["name"], "tracks_applied": len(loaded["tracks"])}
        if action == "default" and method == "PUT":
            te.set_default_preset(folder, pid)
            return 200, {"status": "ok", "preset_id": pid, "is_default": True}
        if action == "rename" and method == "PUT":
            name = te.rename_preset(folder, pid, str(_object(data).get("name", "")).strip())
            return 200, {"status": "ok", "preset_id": pid, "name": name}
        if action == "export" and method == "GET":
            preset = te.get_preset(folder, pid)
            return 200, {"name": preset["name"], "tracks": preset["tracks"]}
    raise _Http(501, NOT_HERE)


def _sequence_route(method, theme_id, s, data, folder):
    if not s:
        if method == "GET":
            defaults = seq.load_defaults(folder)
            return 200, {"theme_id": theme_id, "sequence_defaults": defaults,
                         "sequences": [seq.to_response(sid, d, defaults) for sid, d in seq.load_sequences(folder).items()]}
        if method == "POST":
            sequence_id, cleaned, defaults = te.create_sequence(folder, _object(data))
            return 201, seq.to_response(sequence_id, cleaned, defaults)
    sid = s[0] if s else None
    if len(s) == 1:
        if method == "GET":
            existing = _sequences_with(folder, sid)
            return 200, seq.to_response(sid, existing[sid], seq.load_defaults(folder))
        if method == "PUT":
            cleaned, defaults = te.update_sequence(folder, sid, _object(data))
            return 200, seq.to_response(sid, cleaned, defaults)
        if method == "DELETE":
            te.delete_sequence(folder, sid)
            return 200, {"status": "ok", "sequence_id": sid, "stopped_on": []}
    if len(s) == 3 and s[1] in ("while", "transitions"):
        pid = s[2]
        existing = _sequences_with(folder, sid)
        if pid not in (existing[sid].get("presets") or []):
            raise _Http(404, "That preset is not in the sequence")
        read, write = (seq.step_while, seq.set_step_while) if s[1] == "while" else (seq.step_transitions, seq.set_step_transitions)
        if method == "PUT":
            existing[sid] = write(existing[sid], pid, _object(data))
            seq.save_sequences(folder, existing)
        elif method != "GET":
            raise _Http(501, NOT_HERE)
        return 200, {"theme_id": theme_id, "sequence_id": sid, "preset_id": pid, **read(existing[sid], pid)}
    raise _Http(501, NOT_HERE)


# --- answers -----------------------------------------------------------------------

def _tracks(theme_id, folder, root, pcm):
    """GET /themes/{id}/tracks (api.py get_theme_tracks), from the folder."""
    metadata = te.load(folder)
    links = metadata.links or {}
    lengths = {}
    for path in _audio_files(folder):
        from sonorium.theme_files import track_key
        lengths[track_key(folder, path)] = _seconds(_wav(folder, pcm, path)) if pcm else None
    folder_for_id = intrusions.folder_for_id_in(root)
    groups = metadata.groups or {}
    threshold = metadata.short_file_threshold
    tracks = []
    for key in te.track_keys(folder, metadata):
        settings = metadata.tracks.get(key)
        link = links.get(key)
        values = (settings or tm.TrackSettings(muted=link is not None)).to_dict()
        group = intrusions.link_group(key) if link is not None else (key.split("/", 1)[0] if "/" in key else None)
        seconds = lengths.get(key) or 0.0
        track = {
            "name": key,
            **{k: values[k] for k in ("presence", "muted")},
            "is_enabled": not values["muted"],
            "duration_seconds": round(seconds, 1),
            "is_short_file": bool(seconds) and seconds < threshold,
            **{k: values[k] for k in ("volume", "playback_mode", "seamless_loop", "exclusive", "fade_in", "fade_out")},
            **{k: values[k] for k in tm.EBB_TRACK_SETTINGS},
            "group": group,
            "intrusion_group": intrusions.is_intrusion_group(groups.get(group)),
        }
        if link is not None:
            track["linked"] = _link_info(link, folder_for_id)
        tracks.append(track)
    tracks.sort(key=lambda t: t["name"].lower())
    return {"theme_id": theme_id, "theme_name": metadata.name, "short_file_threshold": threshold, "tracks": tracks}


def _set_track(folder, key, setting, body):
    """PUT /themes/{id}/tracks/{key}/{setting}: the editor's checks, then theme_edit.set_track."""
    if setting == "ebb":
        values = te.clean_ebb(body)
    elif setting == "fades":
        values = te.clean_fades(body)
    elif setting in TRACK_SETTINGS:
        values = {setting: te.clean_track_value(setting, body.get(setting))}
    else:
        raise _Http(501, NOT_HERE)  # move (audio files) and anything unknown
    saved = te.set_track(folder, key, **values)
    if setting == "ebb":
        return {"status": "ok", "track": key, **{k: saved[k] for k in tm.EBB_TRACK_SETTINGS}}
    if setting == "fades":
        return {"status": "ok", "track": key, "fade_in": saved["fade_in"], "fade_out": saved["fade_out"]}
    return {"status": "ok", "track": key, setting: saved[setting]}


def _groups(folder, root):
    """GET /themes/{id}/groups (api.py list_groups)."""
    metadata = te.load(folder)
    raw_groups = metadata.groups or {}
    links = metadata.links or {}
    folder_for_id = intrusions.folder_for_id_in(root)
    out = []
    for name, group in te.list_groups(folder).items():
        out.append({
            "name": name,
            "settings": group["settings"],
            "tracks": group["tracks"],
            "intrusion": (raw_groups.get(name) or {}).get("mode") == "intrusion",
            "links": [{"key": k, **_link_info(link, folder_for_id)} for k, link in links.items()
                      if intrusions.link_group(k) == name],
        })
    return {"groups": out}


def _presets(theme_id, folder, root):
    """GET /themes/{id}/presets (api.py list_presets): with the ⚭ marks and the sequences."""
    metadata = te.load(folder)
    folder_for_id = intrusions.folder_for_id_in(root)
    playable = {k for k, link in (metadata.links or {}).items() if intrusions.resolve_link(link, folder_for_id)}
    linked = intrusions.linked_track_keys(metadata, playable)
    presets = []
    for p in te.list_presets(folder):
        presets.append({**p, "has_intrusion": intrusions.enables_intrusion(metadata, linked, metadata.presets.get(p["id"]))})
    marks = {p["id"]: p["has_intrusion"] for p in presets}
    defaults = seq.load_defaults(folder)
    return {"theme_id": theme_id, "presets": presets,
            "default_has_intrusion": intrusions.enables_intrusion(metadata, linked),
            "sequences": [seq.to_choice(sid, d, defaults, marks) for sid, d in seq.load_sequences(folder).items()]}


def _import_preset(folder, body):
    """POST /themes/{id}/presets/import (api.py import_preset): same checks and answer."""
    preset_json = body.get("preset_json")
    name = body.get("name")
    if not preset_json:
        raise _Http(400, "No preset JSON provided")
    try:
        preset_data = json.loads(preset_json)
    except json.JSONDecodeError as e:
        raise _Http(400, f"Invalid JSON: {e}")
    if not isinstance(preset_data, dict):
        raise _Http(400, "Preset must be a JSON object")
    if "tracks" in preset_data:
        tracks = preset_data["tracks"]
        imported_name = preset_data.get("name", name or "Imported Preset")
    else:
        tracks = preset_data
        imported_name = name or "Imported Preset"
    if not isinstance(tracks, dict):
        raise _Http(400, "Tracks must be a JSON object")
    metadata = te.load(folder)
    valid = set(te.track_keys(folder, metadata))
    validated, unknown = {}, []
    for track_name, settings in tracks.items():
        if track_name not in valid:
            unknown.append(track_name)
            continue
        if not isinstance(settings, dict):
            continue
        validated[track_name] = {
            "volume": float(settings.get("volume", 1.0)),
            "presence": float(settings.get("presence", 1.0)),
            "playback_mode": str(settings.get("playback_mode", "auto")),
            "seamless_loop": bool(settings.get("seamless_loop", False)),
            "exclusive": bool(settings.get("exclusive", False)),
            "muted": bool(settings.get("muted", False)),
        }
    if not validated:
        raise _Http(400, "No valid track settings found in preset")
    preset_id = re.sub(r'[^a-z0-9]+', '_', imported_name.lower()).strip('_') or "imported"
    base_id, counter = preset_id, 1
    while preset_id in metadata.presets:
        preset_id = f"{base_id}_{counter}"
        counter += 1
    metadata.presets[preset_id] = {"name": imported_name, "is_default": False, "tracks": validated}
    te.save(folder, metadata)
    result = {"status": "ok", "preset_id": preset_id, "name": imported_name, "tracks_imported": len(validated)}
    if unknown:
        result["unknown_tracks"] = unknown
        result["warning"] = f"{len(unknown)} track(s) not found in theme"
    return result


def _rename_theme(folder, body):
    """PUT /themes/{id}/rename (api.py rename_theme): the display name; the folder stays."""
    new_name = str(body.get("name", "")).strip()
    if not new_name:
        raise _Http(400, "Name is required")
    metadata = te.load(folder)
    old_name, metadata.name = metadata.name, new_name
    te.save(folder, metadata)
    return {"status": "ok", "old_name": old_name, "new_name": new_name, "theme_id": metadata.id}


def _set_metadata(folder, body):
    """PUT /themes/{id}/metadata (web/api_v2.py update_theme_metadata): description, icon, short_file_threshold."""
    path = folder / theme_presets.METADATA_FILE
    try:
        metadata = theme_presets.read_json(path) or {}
    except theme_presets.BrokenJsonError as e:
        raise _Http(409, f"metadata.json can't be read ({e})")
    if "description" in body:
        metadata["description"] = body["description"]
    if "icon" in body:
        if body["icon"]:
            metadata["icon"] = body["icon"]
        else:
            metadata.pop("icon", None)
    if "short_file_threshold" in body:
        threshold = float(body["short_file_threshold"])
        if threshold < 0:
            raise _Http(400, "short_file_threshold must be >= 0")
        metadata["short_file_threshold"] = threshold
    theme_presets.write_json_atomic(path, metadata)
    return {"status": "ok", "metadata": metadata}


def _transition_tracks(theme_id, folder, root, query):
    """
    GET /themes/{id}/transition_tracks (web/api_v2.py get_transition_tracks), on
    the theme loaded as the engine loads it (file lengths from the decoded audio).
    """
    from sonorium.core.transitions import EPS, track_level
    from sonorium.engine.theme_loader import load_theme
    from sonorium.recording import (GROUP_MODE_MERRY_GO_ROUND, PlaybackMode, TrackView, group_mode,
                                    preset_group_overrides, preset_track_overrides)
    preset, prev, nxt = query.get("preset"), query.get("prev"), query.get("next")
    loaded = load_theme(folder, themes_root=root)
    presets = loaded.presets
    for value in (preset, prev, nxt):
        if value and value not in presets:
            raise _Http(404, f"Preset '{value}' not found")
    theme = loaded.theme
    groups = getattr(theme, "groups", None) or {}
    labels = {PlaybackMode.SPARSE: "Intermittent", PlaybackMode.PRESENCE: "Ebb & Flow", PlaybackMode.CONTINUOUS: "Background"}

    def overrides(pid):
        p = presets.get(pid) or {}
        return {**preset_track_overrides(p.get("tracks", {})), **preset_group_overrides(p.get("groups", {}))}

    def rows(before_id, after_id, side):
        if not before_id or not after_id:
            return []
        before, after = overrides(before_id), overrides(after_id)
        result = []
        for instance in list(theme.tracks):
            group = getattr(instance.meta, "group", None)
            if group and group_mode(groups.get(group)) == GROUP_MODE_MERRY_GO_ROUND:
                continue
            va, vb = TrackView(instance, before), TrackView(instance, after)
            la, lb = track_level(va), track_level(vb)
            if (side == "in" and lb > la + EPS) or (side == "out" and lb < la - EPS):
                view = vb if side == "in" else va
                try:
                    mode = labels.get(view._resolve_playback_mode(), "Background")
                except Exception:  # noqa: BLE001 - as the server
                    mode = "Background"
                try:
                    duration = round(float(instance.meta.duration_seconds), 1)
                except Exception:  # noqa: BLE001
                    duration = 0.0
                result.append({
                    "key": instance.name, "name": instance.name.split("/", 1)[-1], "group": group, "mode": mode,
                    "from": round(la, 4), "to": round(lb, 4),
                    "presence_from": round(float(va.presence), 4), "presence_to": round(float(vb.presence), 4),
                    "duration_seconds": duration,
                })
        return result

    return {"theme_id": theme_id, "preset": preset, "prev": prev, "next": nxt,
            "in": rows(prev, preset, "in"), "out": rows(preset, nxt, "out")}


# --- helpers -----------------------------------------------------------------------

def _object(data):
    if not isinstance(data, dict):
        raise _Http(400, "Expected a JSON object")
    return data


def _folder(root, theme_id):
    """A theme folder under root by its metadata.json id, else by folder name."""
    found = intrusions.folder_for_id_in(root)(theme_id)
    if found is not None:
        return Path(found)
    folder = root / theme_id
    if theme_id not in ("", ".", "..") and "/" not in theme_id and folder.is_dir():
        return folder
    raise _Http(404, "Theme not found")


def _raw_metadata(folder):
    try:
        raw = theme_presets.read_json(Path(folder) / theme_presets.METADATA_FILE)
    except Exception:  # noqa: BLE001 - Core rebuilds a broken file when it loads the theme
        return None
    return raw if isinstance(raw, dict) else None


def _all_categories(root):
    """The categories of the themes under root (the server's list is server-wide)."""
    found = []
    try:
        folders = sorted(p for p in root.iterdir() if p.is_dir())
    except OSError:
        folders = []
    for folder in folders:
        for c in ((_raw_metadata(folder) or {}).get("categories") or []):
            if isinstance(c, str) and c not in found:
                found.append(c)
    return found


def _link_info(link, folder_for_id):
    source = folder_for_id(link.get("theme"))
    name = ((_raw_metadata(source) or {}).get("name")) if source else None
    return {"theme_id": link.get("theme"), "theme_name": name, "track": link.get("track"),
            "missing": intrusions.resolve_link(link, folder_for_id) is None}


def _sequences_with(folder, sequence_id):
    existing = seq.load_sequences(folder)
    if sequence_id not in existing:
        raise _Http(404, "Sequence not found")
    return existing


def _audio_files(folder):
    from sonorium.theme_files import theme_audio_files
    return theme_audio_files(Path(folder))


def _wav(folder, pcm, path):
    return (Path(pcm) / Path(path).relative_to(folder)).with_suffix(".wav")


def _seconds(wav):
    """A decoded file's length (16-bit mono at 44100 Hz), or None when it isn't there."""
    try:
        import wave
        with wave.open(str(wav), "rb") as w:
            return w.getnframes() / float(w.getframerate() or SAMPLE_RATE)
    except Exception:  # noqa: BLE001
        try:
            return max(0, wav.stat().st_size - 44) / (2.0 * SAMPLE_RATE)
        except OSError:
            return None
