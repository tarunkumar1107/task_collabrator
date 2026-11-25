"""Agent 1 - Trend Hunter.

Finds GTA 6 streams from the priority streamers (and a few generic searches),
collects url / title / duration / viewers and stores them in SQLite.

Uses yt-dlp metadata extraction only (nothing is downloaded here).
Live streams are recorded as `is_live`; they are analysed once the VOD exists
(`was_live`), because a stream in progress cannot be cut reliably.
"""
from __future__ import annotations

import json
import time

from common import db, load_config, log, run

log = log.getChild("trend")


YTDLP_ARGS: list[str] = []  # extra yt-dlp args from config (e.g. ["--cookies", "cookies.txt"])


def _yt_json(args: list[str], timeout: float = 180) -> list[dict]:
    p = run(["yt-dlp", "--no-warnings", "--skip-download", "-J", *YTDLP_ARGS, *args], timeout=timeout)
    data = json.loads(p.stdout or "{}")
    return data.get("entries") or ([data] if data.get("id") else [])


def _matches(title: str, keywords: list[str]) -> bool:
    t = (title or "").lower().replace(" ", "")
    return any(k.lower().replace(" ", "") in t for k in keywords)


def _details(video_id: str) -> dict | None:
    try:
        info = _yt_json([f"https://www.youtube.com/watch?v={video_id}"], timeout=120)
    except Exception as e:  # private, members-only, removed ...
        log.info("skip %s: %s", video_id, str(e).splitlines()[0])
        return None
    return info[0] if info else None


def _candidates(cfg: dict) -> list[tuple[str, dict]]:
    s_cfg = cfg["search"]
    n = s_cfg.get("lookback_videos_per_channel", 8)
    found: list[tuple[str, dict]] = []
    for st in sorted(cfg["streamers"], key=lambda s: s.get("priority", 99)):
        for tab in ("streams", "videos"):
            url = f"{st['channel'].rstrip('/')}/{tab}"
            try:
                entries = _yt_json(["--flat-playlist", "--playlist-end", str(n), url])
            except Exception as e:
                log.warning("%s %s: %s", st["name"], tab, str(e).splitlines()[0])
                continue
            for e in entries:
                if _matches(e.get("title", ""), s_cfg["keywords"]):
                    e["_tab"] = tab
                    found.append((st["name"], e))
    for q in s_cfg.get("extra_queries", []):
        try:
            entries = _yt_json(["--flat-playlist", f"ytsearch{n}:{q}"])
        except Exception as e:
            log.warning("search %r: %s", q, str(e).splitlines()[0])
            continue
        names = {s["name"].lower(): s["name"] for s in cfg["streamers"]}
        for e in entries:
            chan = (e.get("channel") or e.get("uploader") or "").lower()
            streamer = next((v for k, v in names.items() if k.replace(" ", "") in chan.replace(" ", "")), None)
            if streamer and _matches(e.get("title", ""), s_cfg["keywords"]):
                found.append((streamer, e))
    return found


def find_streams(cfg: dict | None = None) -> int:
    cfg = cfg or load_config()
    YTDLP_ARGS[:] = cfg["search"].get("ytdlp_args", [])
    conn = db(cfg)
    s_cfg = cfg["search"]
    new = 0
    seen: set[str] = set()
    for streamer, entry in _candidates(cfg):
        vid = entry.get("id")
        if not vid or vid in seen:
            continue
        seen.add(vid)
        row = conn.execute("SELECT live_status FROM streams WHERE video_id=?", (vid,)).fetchone()
        if row and row["live_status"] == "was_live":
            continue  # already known & final
        info = _details(vid)
        if not info:
            # video page refused (e.g. YouTube's bot check on server IPs): fall back to playlist metadata
            if not entry.get("duration"):
                continue
            info = {**entry, "webpage_url": entry.get("url") or f"https://www.youtube.com/watch?v={vid}"}
        dur = float(info.get("duration") or 0)
        live = info.get("live_status") or ("is_live" if info.get("is_live") else
                                           "was_live" if entry.get("_tab") == "streams" else "not_live")
        if live != "is_live" and not (s_cfg["min_duration_minutes"] * 60 <= dur <= s_cfg["max_duration_hours"] * 3600):
            continue
        viewers = info.get("concurrent_view_count") or info.get("view_count") or 0
        conn.execute(
            """INSERT INTO streams(video_id, streamer, url, title, duration, viewers, live_status, found_at)
               VALUES (?,?,?,?,?,?,?,?)
               ON CONFLICT(video_id) DO UPDATE SET duration=excluded.duration,
                 viewers=excluded.viewers, live_status=excluded.live_status, title=excluded.title""",
            (vid, streamer, info.get("webpage_url"), info.get("title"), dur, viewers, live, time.time()),
        )
        if not row:
            new += 1
            log.info("found [%s] %s (%s, %.0f min, %s viewers)", streamer, info.get("title"), live, dur / 60, viewers)
    conn.commit()
    log.info("trend hunter: %d new streams", new)
    return new


def add_local_source(cfg: dict, path: str, streamer: str, title: str = "local test") -> str:
    """Register a local video file as a 'stream' (testing / your own recordings)."""
    from pathlib import Path
    from common import media_duration
    vid = "local_" + Path(path).stem
    conn = db(cfg)
    conn.execute(
        "INSERT OR REPLACE INTO streams(video_id, streamer, url, title, duration, viewers, live_status, found_at, status)"
        " VALUES (?,?,?,?,?,?,?,?, 'new')",
        (vid, streamer, str(Path(path).resolve()), title, media_duration(path), 0, "local", time.time()),
    )
    conn.commit()
    return vid


if __name__ == "__main__":
    find_streams()
