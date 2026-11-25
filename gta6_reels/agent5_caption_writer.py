"""Agent 5 - Caption Writer (+ thumbnail).

Claude writes title / caption / hashtags / pinned comment / SEO keywords.
Falls back to templates when Claude isn't configured. Always credits the streamer.
"""
from __future__ import annotations

import json
import textwrap
from pathlib import Path

from pydantic import BaseModel, Field

from agent4_video_editor import font_file
from common import (ask_claude, db, load_config, log, media_duration, run, set_moment_status,
                    streamer_cfg, upsert_reel, work_path)
from transcribe import words_to_text

log = log.getChild("captions")


class CaptionPack(BaseModel):
    title: str = Field(description="hook title, max 60 chars, used on the thumbnail and as the first caption line")
    caption: str = Field(description="2-4 short lines with emoji and a question/CTA to drive comments; no hashtags")
    hashtags: list[str] = Field(description="8-12 hashtags, each starting with #")
    pinned_comment: str = Field(description="a comment to post first that sparks replies")
    seo_keywords: list[str] = Field(description="5-10 search keywords")
    thumbnail_text: str = Field(description="2-5 word ALL CAPS overlay for the cover")


SYSTEM = (
    "You write Instagram Reels packaging for an Indian GTA 6 gaming clips page. Audience: Indian gamers "
    "aged 15-28 who speak Hinglish. Titles are punchy, curiosity-driven and honest to the clip - never claim "
    "something that does not happen in it. No slurs, no mocking real people beyond friendly banter."
)


def fallback_pack(cfg: dict, streamer: str, moment: dict) -> CaptionPack:
    cat = (moment.get("category") or "reaction").replace("_", " ")
    tag = "#" + streamer.replace(" ", "")
    return CaptionPack(
        title=f"{streamer} vs GTA 6 {cat} 😂",
        caption=f"{streamer} was NOT ready for this 😭\nWould you survive this? 👇👇👇",
        hashtags=[tag, *cfg["captions"]["base_hashtags"], "#GTA6Gameplay", "#Funny"],
        pinned_comment="Rate this moment 1-10 👇",
        seo_keywords=["GTA 6", f"{streamer} GTA 6", "GTA 6 funny moments", "GTA 6 India"],
        thumbnail_text=f"{cat.upper()}!",
    )


def write_captions(cfg: dict, moment: dict, stream: dict, words: list[dict]) -> CaptionPack:
    prompt = (
        f"Streamer: {stream['streamer']}\nStream title: {stream['title']}\n"
        f"Moment category: {moment.get('category')}\nWhy it's viral: {moment.get('reason')}\n"
        f"Best line: {moment.get('quote')}\nClip transcript: {words_to_text(words)[:3000]}\n"
        f"Language: {cfg['captions']['language']}. Always include #GTA6 and the streamer's tag."
    )
    pack = ask_claude(cfg, SYSTEM, prompt, CaptionPack) or fallback_pack(cfg, stream["streamer"], moment)
    tags = []
    for h in [*pack.hashtags, *cfg["captions"]["base_hashtags"]]:
        h = "#" + h.lstrip("#").replace(" ", "")
        if h.lower() not in {t.lower() for t in tags}:
            tags.append(h)
    pack.hashtags = tags[: cfg["captions"]["max_hashtags"]]
    return pack


def full_caption(pack: CaptionPack, streamer: str, ig_handle: str | None) -> str:
    credit = f"🎥 Credit: @{ig_handle}" if ig_handle else f"🎥 Credit: {streamer}"
    return f"{pack.title}\n\n{pack.caption}\n\n{credit}\n\n{' '.join(pack.hashtags)}"


def make_thumbnail(cfg: dict, reel_path: Path, at: float, text: str, out: Path) -> Path:
    font = font_file(cfg["render"]["subtitle_font"])
    lines = textwrap.wrap(text.upper().replace("'", "").replace(":", ""), 14)[:3]
    draws = ",".join(
        f"drawtext=fontfile='{font}':text='{ln}':x=(w-text_w)/2:y={170 + i * 140}:fontsize=120:"
        f"fontcolor=yellow:borderw=10:bordercolor=black" for i, ln in enumerate(lines))
    vf = "eq=saturation=1.3:contrast=1.08" + ("," + draws if draws else "")
    run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.2f}", "-i", str(reel_path), "-frames:v", "1",
         "-vf", vf, "-q:v", "2", str(out)])
    return out


def package(cfg: dict, moment: dict, reel: dict) -> CaptionPack:
    conn = db(cfg)
    stream = dict(conn.execute("SELECT * FROM streams WHERE video_id=?", (moment["video_id"],)).fetchone())
    words = json.loads(Path(reel["words_path"]).read_text()) if reel.get("words_path") else []
    pack = write_captions(cfg, moment, stream, words)
    st = streamer_cfg(cfg, stream["streamer"])
    reel_path = Path(reel["reel_path"])
    dur = media_duration(reel_path)
    peak = (moment["peak"] - moment["start"]) if moment.get("peak") else dur / 2
    thumb = make_thumbnail(cfg, reel_path, min(max(0.5, peak), dur - 0.5), pack.thumbnail_text,
                           work_path(cfg, "thumbs", f"m{moment['id']}.jpg"))
    upsert_reel(conn, moment["id"], title=pack.title,
                caption=full_caption(pack, stream["streamer"], st.get("ig_handle")),
                hashtags=" ".join(pack.hashtags), pinned_comment=pack.pinned_comment,
                seo_keywords=", ".join(pack.seo_keywords), thumb_path=str(thumb))
    set_moment_status(conn, moment["id"], "captioned")
    log.info("m%d: %s", moment["id"], pack.title)
    return pack


if __name__ == "__main__":
    cfg = load_config()
    conn = db(cfg)
    for row in conn.execute("SELECT m.*, r.* FROM moments m JOIN reels r ON r.moment_id=m.id "
                            "WHERE m.status='rendered' LIMIT 1"):
        d = dict(row); d["id"] = d["moment_id"]
        package(cfg, d, d)
