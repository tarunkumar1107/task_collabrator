"""Agent 3 - Clip Generator.

Downloads ONLY the chosen section of the VOD (yt-dlp --download-sections), then
transcribes that clip with word timestamps for the subtitles.
"""
from __future__ import annotations

import json
from pathlib import Path

from common import db, fmt_ts, load_config, log, run, set_moment_status, upsert_reel, work_path
from transcribe import transcribe

log = log.getChild("clipper")


def cut_clip(cfg: dict, stream: dict, start: float, end: float, out: Path) -> Path:
    if out.exists() and out.stat().st_size > 0:
        return out
    if stream["live_status"] == "local":
        run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-i", stream["url"], "-t", f"{end - start:.3f}",
             "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-c:a", "aac", "-b:a", "192k", str(out)])
        return out
    log.info("downloading %s %s-%s", stream["video_id"], fmt_ts(start), fmt_ts(end))
    run(["yt-dlp", "--no-warnings", *cfg["search"].get("ytdlp_args", []), "-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/bv*[height<=1080]+ba/b",
         "--merge-output-format", "mp4", "--download-sections", f"*{start:.2f}-{end:.2f}",
         "--force-keyframes-at-cuts", "-o", str(out), stream["url"]], timeout=3600)
    return out


def make_clip(cfg: dict, moment: dict) -> dict:
    conn = db(cfg)
    stream = dict(conn.execute("SELECT * FROM streams WHERE video_id=?", (moment["video_id"],)).fetchone())
    max_len = cfg["render"]["max_seconds"] - 1
    start, end = moment["start"], min(moment["end"], moment["start"] + max_len)
    clip = cut_clip(cfg, stream, start, end, work_path(cfg, "clips", f"m{moment['id']}.mp4"))
    wav = work_path(cfg, "tmp", f"m{moment['id']}.wav")
    run(["ffmpeg", "-v", "error", "-y", "-i", str(clip), "-ac", "1", "-ar", "16000", str(wav)])
    words = transcribe(cfg, wav)
    wav.unlink(missing_ok=True)
    words_path = work_path(cfg, "subs", f"m{moment['id']}.words.json")
    words_path.write_text(json.dumps(words, ensure_ascii=False, indent=1))
    upsert_reel(conn, moment["id"], clip_path=str(clip), words_path=str(words_path))
    set_moment_status(conn, moment["id"], "clipped")
    log.info("clip m%d: %.1fs, %d words", moment["id"], end - start, len(words))
    return {"clip": clip, "words": words}


if __name__ == "__main__":
    cfg = load_config()
    conn = db(cfg)
    for r in conn.execute("SELECT * FROM moments WHERE status='scored' ORDER BY score DESC LIMIT 1"):
        make_clip(cfg, dict(r))
