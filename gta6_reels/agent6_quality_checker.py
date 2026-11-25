"""Agent 6 - Quality Checker.

  no duplicate        (same file hash, or overlaps a clip already made from the same VOD)
  subtitles exist     (ASS file with dialogue lines)
  1080x1920           (and H.264 / yuv420p / has audio)
  under 90 seconds    (and at least 5s)
  audio normalised    (integrated loudness within +-1.5 LU of target)
  thumbnail exists
  caption exists
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

from common import db, ffprobe, load_config, log, upsert_reel

log = log.getChild("qa")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def integrated_loudness(path: Path) -> float | None:
    p = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-vn", "-af", "ebur128",
                        "-f", "null", "-"], capture_output=True, text=True)
    m = re.findall(r"I:\s+(-?[\d.]+) LUFS", p.stderr)
    return float(m[-1]) if m else None


def check(cfg: dict, moment: dict, reel: dict) -> tuple[bool, dict]:
    r = cfg["render"]
    conn = db(cfg)
    res: dict[str, tuple[bool, str]] = {}
    path = Path(reel.get("reel_path") or "")
    if not path.exists():
        return False, {"reel_exists": (False, "missing")}

    digest = sha256(path)
    dup = conn.execute(
        """SELECT m.id FROM moments m JOIN reels r ON r.moment_id=m.id
           WHERE m.id != ? AND m.status IN ('qa_passed','published')
             AND (r.sha256 = ? OR (m.video_id = ? AND m.start < ? AND m.end > ?))""",
        (moment["id"], digest, moment["video_id"], moment["end"], moment["start"])).fetchone()
    res["no_duplicate"] = (dup is None, f"duplicates m{dup[0]}" if dup else "ok")

    subs = Path(reel.get("subs_path") or "")
    n_lines = subs.read_text(encoding="utf-8").count("Dialogue:") if subs.exists() else 0
    res["subtitles_exist"] = (n_lines > 0, f"{n_lines} subtitle events")

    info = ffprobe(path)
    v = next((s for s in info["streams"] if s["codec_type"] == "video"), {})
    a = next((s for s in info["streams"] if s["codec_type"] == "audio"), None)
    size_ok = (v.get("width"), v.get("height")) == (r["width"], r["height"])
    res["resolution"] = (size_ok, f"{v.get('width')}x{v.get('height')}")
    res["codec"] = (v.get("codec_name") == "h264" and v.get("pix_fmt") == "yuv420p",
                    f"{v.get('codec_name')}/{v.get('pix_fmt')}")
    dur = float(info["format"]["duration"])
    res["duration"] = (5 <= dur < r["max_seconds"], f"{dur:.1f}s")
    if a is None:
        res["audio_normalized"] = (False, "no audio stream")
    else:
        lufs = integrated_loudness(path)
        res["audio_normalized"] = (lufs is not None and abs(lufs - r["loudness_lufs"]) <= 1.5, f"{lufs} LUFS")
    thumb = Path(reel.get("thumb_path") or "")
    res["thumbnail_exists"] = (thumb.exists() and thumb.stat().st_size > 1000, str(thumb.name))
    res["caption_exists"] = (bool((reel.get("caption") or "").strip()), f"{len(reel.get('caption') or '')} chars")

    ok = all(v[0] for v in res.values())
    report = {k: {"ok": v[0], "detail": v[1]} for k, v in res.items()}
    upsert_reel(conn, moment["id"], qa_report=json.dumps(report), sha256=digest)
    for k, (good, detail) in res.items():
        log.info("  %s %-17s %s", "✔" if good else "✘", k, detail)
    return ok, report


if __name__ == "__main__":
    cfg = load_config()
    conn = db(cfg)
    for row in conn.execute("SELECT m.*, r.* FROM moments m JOIN reels r ON r.moment_id=m.id "
                            "WHERE m.status='captioned' LIMIT 1"):
        d = dict(row); d["id"] = d["moment_id"]
        print(check(cfg, d, d))
