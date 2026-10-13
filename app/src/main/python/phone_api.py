"""
The server's Theme Editor routes, answered on the phone for a theme folder.

The app's Theme Editor (server/ThemeEditor.kt) talks to /api/themes/... on a
server. For a theme on this phone, Kotlin (local/PhoneThemeApi.kt) sends the
same requests here instead, and every change is made by the same Core code the
server's routes call (sonorium.core.theme_edit, sequences, theme_metadata), so
the files come out as the server writes them and the answers have the
server's JSON shapes.

    handle(method, path, query, body, themes_root, pcm_root=None, current=None)
        -> '{"status": 200, "body": {...}[, "ops": [...]]}'

- path: the part after /api/, as a JSON list of decoded path segments
  ('["themes", "Rain", "tracks", "Birds/Robin", "volume"]': a track key holds
  a "/"), or a plain "themes/Rain/tracks" string when no segment holds one.
- query: JSON object of query parameters (or ""); body: the JSON body text (or "").
- themes_root: the folder holding the prepared theme copies (files/engine/themes);
  a theme id is its metadata.json "id", else its folder name.
- pcm_root: the decoded audio beside them (files/engine/pcm), for file lengths.
- current: the folder name of the theme being edited (for settings/playback-values).

Changes that make, move or remove files (group create/rename/delete, linked
files, upload) are made on the prepared copy by Core's folder functions; their
file operations come back as "ops" (theme_edit.FileOps) for the app to repeat
in the user's themes folder. Upload's body here is JSON: {"filename", "path"}
(the app saved the file it was sent at "path"), the group a query parameter.

Themes are loaded without converting (theme_edit.convert_on_load = False):
nothing is written on open, and the stand-ins' lengths are never read.

What the phone can't do yet answers 501 {"detail"/"error": NOT_HERE}: moving
tracks, copying a linked file in, and the server-wide category list changes.
"""
import json
import os
from pathlib import Path

os.environ.setdefault("SONORIUM_LOG_LEVEL", "warning")

from sonorium import decoders  # noqa: E402
from sonorium import playback_values as _values  # noqa: E402
from sonorium.core import intrusions, sequences as seq, theme_edit as te, theme_groups, theme_presets  # noqa: E402
from sonorium.core import theme_metadata as tm  # noqa: E402

# Opening a theme writes nothing (no conversion, backup or broken-file rename): conversions that need
# the real audio files are left to the server
te.convert_on_load = False

NOT_HERE = "Not available for themes on this phone yet"
OLD_FORMAT = "This theme is in an older format. Open it once on your server to update it, then edit it here."
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
        ops = answer.pop("ops", None) if isinstance(answer, dict) else None
    except _Http as e:
        status, answer, ops = e.status, _error(str(e)), None
    except (te.EditError,) as e:
        status, answer, ops = e.status, _error(str(e)), None
    except theme_groups.GroupError as e:
        status, answer, ops = getattr(e, "status", 400), _error(str(e)), None
    except seq.SequenceError as e:
        status, answer, ops = 400, _error(str(e)), None
    except json.JSONDecodeError:
        status, answer, ops = 400, _error("Invalid JSON body"), None
    except (TypeError, ValueError) as e:
        status, answer, ops = 400, _error(f"Invalid value: {e}"), None
    except Exception as e:  # noqa: BLE001 - an answer, never a crash in the app
        status, answer, ops = 500, _error(f"{type(e).__name__}: {e}"), None
    out = {"status": status, "body": answer}
    if ops is not None:
        out["ops"] = ops
    return json.dumps(out, default=str)


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
    decoder = decoders.PcmFileDecoder(lookup=decoders.mirror_lookup(folder, pcm)) if pcm else None
    with decoders.use_decoder(decoder), _values.use_values(package_values(str(folder))):
        return _theme_route(method, s[1], s[2:], query, data, folder, root, pcm, decoder)


def _theme_route(method, theme_id, s, query, data, folder, root, pcm, decoder=None):
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
        if s == ["groups"] and method == "POST":
            ops = te.FileOps(folder)
            name = te.create_group(folder, _object(data).get("name"))
            ops.made_dir(folder / name)
            return 201, {"name": name, "ops": ops.done()}
        if len(s) == 3 and s[2] == "rename" and method == "POST":
            result = te.rename_group(folder, s[1], _object(data).get("name"))
            return 200, {"name": result["name"], "tracks": result["tracks"], "ops": result["ops"]}
        if len(s) == 2 and method == "DELETE":
            return 200, te.delete_group(folder, s[1])
    elif head == "links":
        return _link_route(method, s[1:], data, folder, root, pcm)
    elif head == "upload" and method == "POST" and len(s) == 1:
        return 200, _upload(theme_id, folder, query, _object(data))
    elif head == "rename" and method == "PUT" and len(s) == 1:
        return 200, _rename_theme(folder, _object(data))
    elif head == "metadata" and method == "PUT" and len(s) == 1:
        return 200, _set_metadata(folder, _object(data))
    elif head == "categories" and method == "POST" and len(s) == 1:
        categories = te.set_categories(folder, list(_object(data).get("categories", [])))
        return 200, {"theme_id": theme_id, "categories": categories}
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
        return 200, te.transition_tracks(folder, query.get("preset"), query.get("prev") or None,
                                         query.get("next") or None, themes_root=root, decoder=decoder)
    raise _Http(501, NOT_HERE)


def _link_route(method, s, data, folder, root, pcm):
    """Linked files (api.py add_links, delete_link, list_link_source_tracks)."""
    if not s and method == "POST":
        body = _object(data)
        source_id = body.get("theme")
        if not isinstance(source_id, str) or not source_id:
            raise _Http(400, "'theme' (the source theme id) is required")
        te.clean_link_request(body.get("group"), body.get("tracks"))
        return 201, te.add_links(folder, _source(root, source_id), body.get("tracks"), body.get("group"))
    if len(s) == 2 and s[0] == "source" and method == "GET":
        return 200, _source_tracks(folder, root, pcm, s[1])
    if len(s) == 1 and method == "DELETE":
        return 200, te.remove_link(folder, s[0])
    raise _Http(501, NOT_HERE)


def _source(root, source_id):
    try:
        return _folder(root, source_id)
    except _Http:
        raise _Http(404, "Source theme not found")


def _source_tracks(folder, root, pcm, source_id):
    """GET /themes/{id}/links/source/{source_id}: another theme's own files, to link from."""
    from urllib.parse import quote
    from sonorium.theme_files import track_display_name, track_key
    metadata = te.load(folder)
    source = _source(root, source_id)
    source_metadata = te.load(source)
    source_pcm = (pcm.parent / source.name) if pcm is not None else None
    linked_as = {(link.get("theme"), link.get("track")): key for key, link in (metadata.links or {}).items()}
    tracks = []
    for path in _audio_files(source):
        key = track_key(source, path)
        seconds = _seconds(_wav(source, source_pcm, path)) if source_pcm is not None else None
        tracks.append({
            "id": key, "name": key, "display_name": track_display_name(key),
            "group": key.split("/", 1)[0] if "/" in key else None,
            "duration_seconds": round(seconds, 1) if seconds else None,
            "preview_url": f"/api/themes/{quote(source_metadata.id, safe='')}/tracks/{quote(key, safe='')}/audio",
            "linked_as": linked_as.get((source_metadata.id, key)),
        })
    tracks.sort(key=lambda t: t["name"].lower())
    return {"theme_id": metadata.id, "source": {"id": source_metadata.id, "name": source_metadata.name},
            "same_theme": source_metadata.id == metadata.id, "tracks": tracks}


def _upload(theme_id, folder, query, body):
    """POST /themes/{id}/upload (web/api_v2.py upload_theme_file), the file already saved at body["path"]."""
    path, filename = body.get("path"), body.get("filename")
    if not path or not filename:
        raise _Http(400, "No file provided")
    group = body.get("group") or query.get("group") or None
    saved = te.upload(folder, filename, Path(path), group)
    return {"status": "ok", "filename": saved["filename"], "size": saved["size"], "theme_id": theme_id,
            "group": saved["group"], "track": saved["track"], "ops": saved["ops"]}


def _preset_route(method, theme_id, s, data, folder, root):
    if not s:
        if method == "GET":
            return 200, _presets(theme_id, folder, root)
        if method == "POST":
            name = data.get("name") if isinstance(data, dict) else None
            return 200, {"status": "ok", **te.new_preset(folder, name)}
    elif s == ["import"] and method == "POST":
        body = _object(data)
        return 200, te.import_preset(folder, body.get("preset_json"), body.get("name"))
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


def _rename_theme(folder, body):
    """PUT /themes/{id}/rename (api.py rename_theme): the display name; the folder stays."""
    old_name, new_name = te.rename_theme(folder, str(body.get("name", "")).strip())
    return {"status": "ok", "old_name": old_name, "new_name": new_name, "theme_id": te.load(folder).id}


def _set_metadata(folder, body):
    """PUT /themes/{id}/metadata (web/api_v2.py update_theme_metadata): description, icon, short_file_threshold."""
    values = {k: body[k] for k in ("description", "icon", "short_file_threshold") if k in body}
    return {"status": "ok", "metadata": te.set_metadata_fields(folder, values)}


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
