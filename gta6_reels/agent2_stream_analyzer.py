"""Agent 2 - Viral Moment Finder.

Never downloads the 5-hour video. Instead:
  1. download audio only (and the live-chat replay if available)
  2. compute a loudness-excitement curve + chat-velocity curve (cheap, whole stream)
  3. pick the top N peaks and run Whisper only on those windows
  4. score each candidate (heuristics, then Claude re-scores with the transcript)
  5. store moments; only score >= threshold continue to Agent 3
"""
from __future__ import annotations

import glob
import json
import subprocess
import time
from pathlib import Path

import numpy as np
from pydantic import BaseModel, Field

from common import ask_claude, db, fmt_ts, load_config, log, run, work_path
from transcribe import transcribe, words_to_text

log = log.getChild("analyzer")
SR = 8000
HOP = 0.5  # seconds per loudness frame


# ----------------------------------------------------------------- download
def fetch_audio(cfg: dict, stream: dict) -> Path:
    if stream["live_status"] == "local":
        return Path(stream["url"])
    out = work_path(cfg, "audio", f"{stream['video_id']}.m4a")
    if not out.exists():
        log.info("downloading audio for %s", stream["video_id"])
        run(["yt-dlp", "--no-warnings", *cfg["search"].get("ytdlp_args", []), "-f", "bestaudio[ext=m4a]/bestaudio", "-o", str(out), stream["url"]],
            timeout=3 * 3600)
    return out


def fetch_chat(cfg: dict, stream: dict) -> Path | None:
    if stream["live_status"] != "was_live" or not cfg["analysis"].get("use_live_chat"):
        return None
    base = work_path(cfg, "audio", stream["video_id"])
    hits = glob.glob(f"{base}*.live_chat.json")
    if hits:
        return Path(hits[0])
    try:
        run(["yt-dlp", "--no-warnings", *cfg["search"].get("ytdlp_args", []), "--skip-download", "--write-subs", "--sub-langs", "live_chat",
             "-o", str(base), stream["url"]], timeout=3600)
    except Exception as e:
        log.info("no chat replay: %s", str(e).splitlines()[0])
        return None
    hits = glob.glob(f"{base}*.live_chat.json")
    return Path(hits[0]) if hits else None


# ----------------------------------------------------------------- signals
def loudness_curve(audio: Path) -> np.ndarray:
    """dBFS per HOP-second frame of the whole file, decoded at 8 kHz mono."""
    proc = subprocess.Popen(["ffmpeg", "-v", "error", "-i", str(audio), "-vn", "-ac", "1", "-ar", str(SR),
                             "-f", "s16le", "-"], stdout=subprocess.PIPE)
    frame = int(SR * HOP)
    out: list[float] = []
    while True:
        buf = proc.stdout.read(frame * 2 * 600)  # 5 minutes at a time
        if not buf:
            break
        x = np.frombuffer(buf, dtype=np.int16).astype(np.float32) / 32768.0
        n = len(x) // frame
        if n:
            rms = np.sqrt(np.mean(x[: n * frame].reshape(n, frame) ** 2, axis=1) + 1e-10)
            out.extend(20 * np.log10(rms))
    proc.stdout.close()
    proc.wait()
    return np.array(out, dtype=np.float32)


def _rolling_z(x: np.ndarray, win: int) -> np.ndarray:
    """z-score of each point relative to a centred rolling median / MAD."""
    if len(x) == 0:
        return x
    pad = win // 2
    xp = np.pad(x, pad, mode="edge")
    step = max(1, win // 20)  # sample the window to stay fast on 5h streams
    idx = np.arange(len(x))[:, None] + np.arange(0, win, step)[None, :]
    w = xp[np.clip(idx, 0, len(xp) - 1)]
    med = np.median(w, axis=1)
    mad = np.median(np.abs(w - med[:, None]), axis=1) * 1.4826 + 1.0
    return (x - med) / mad


def excitement(db_curve: np.ndarray) -> np.ndarray:
    z = _rolling_z(db_curve, int(300 / HOP))           # loud vs the last/next ~5 min
    jump = np.zeros_like(db_curve)
    k = int(3 / HOP)
    if len(db_curve) > k:
        jump[k:] = db_curve[k:] - np.convolve(db_curve, np.ones(k) / k, mode="full")[k - 1:-k][: len(db_curve) - k]
    sm = np.convolve(z + 0.15 * np.clip(jump, 0, None), np.ones(4) / 4, mode="same")
    return sm


def chat_curve(chat_file: Path | None, n_frames: int) -> np.ndarray:
    c = np.zeros(n_frames, dtype=np.float32)
    if not chat_file:
        return c
    for line in chat_file.read_text(errors="ignore").splitlines():
        try:
            d = json.loads(line)
            ms = int(d["replayChatItemAction"]["videoOffsetTimeMsec"])
        except Exception:
            continue
        i = int(ms / 1000 / HOP)
        if 0 <= i < n_frames:
            c[i] += 1
    c = np.convolve(c, np.ones(int(10 / HOP)), mode="same")  # msgs per 10s
    return _rolling_z(c, int(600 / HOP))


def pick_peaks(score: np.ndarray, n: int, min_gap_s: float, margin_s: float) -> list[int]:
    order = np.argsort(score)[::-1]
    gap = int(min_gap_s / HOP)
    lo, hi = int(margin_s / HOP), len(score) - int(margin_s / HOP)
    chosen: list[int] = []
    for i in order:
        if len(chosen) >= n:
            break
        if not (lo <= i < hi) or score[i] <= 0:
            continue
        if all(abs(i - j) >= gap for j in chosen):
            chosen.append(int(i))
    return sorted(chosen)


# ----------------------------------------------------------------- scoring
def keyword_hits(cfg: dict, text: str) -> dict[str, int]:
    t = text.lower()
    return {cat: sum(t.count(k) for k in kws) for cat, kws in cfg["analysis"]["keywords"].items()
            if any(k in t for k in kws)}


def heuristic_score(loud_z: float, chat_z: float, hits: dict[str, int], n_words: int) -> float:
    s = 45 + 9 * min(loud_z, 5) + 5 * min(max(chat_z, 0), 4)
    s += min(sum(hits.values()), 6) * 3 + (4 if len(hits) >= 2 else 0)
    if n_words < 5:
        s -= 10  # nothing said: hard to subtitle, usually weaker
    return float(max(0, min(99, s)))


class MomentScore(BaseModel):
    candidate: int = Field(description="candidate index from the list")
    score: int = Field(description="0-100 virality score for an Instagram Reel")
    category: str = Field(description="scream|laugh|rage|police_chase|crash|explosion|bug|npc|mission_fail|emotional|plot_twist|chat_explosion|other")
    quote: str = Field(description="the funniest/most hooky line, verbatim from the transcript, or empty")
    start_offset: float = Field(description="seconds from window start where the reel should begin (setup)")
    end_offset: float = Field(description="seconds from window start where the reel should end (after the payoff)")
    reason: str


class MomentScores(BaseModel):
    moments: list[MomentScore]


SCORER_SYSTEM = (
    "You are a short-form video editor for an Indian GTA 6 gaming Instagram page. "
    "You judge stream moments for Reels virality: a clear hook in the first 2 seconds, "
    "a strong reaction (scream, laugh, rage), an unexpected game event (police chase, crash, "
    "explosion, funny bug, NPC interaction, mission fail, plot twist) or chat going wild. "
    "Transcripts are Hindi/Hinglish/English Whisper output and may contain errors. "
    "Be strict: 90+ means you would bet this reel outperforms the page average. "
    "Moments that are just loud music, ads, sponsor reads, donations/TTS, or dead air score below 50. "
    "Never pick moments with slurs or targeted harassment of real people."
)


def claude_rescore(cfg: dict, stream: dict, cands: list[dict]) -> dict[int, MomentScore]:
    W = cfg["analysis"]["window_seconds"]
    lines = []
    for i, c in enumerate(cands):
        lines.append(
            f"[{i}] window {fmt_ts(c['start'])}-{fmt_ts(c['end'])} (length {c['end'] - c['start']:.0f}s), "
            f"loudness_z={c['loud_z']:.1f}, chat_z={c['chat_z']:.1f}, keywords={c['hits']}\n"
            f"    transcript (t=0 is window start): {c['timed_text'] or '(no speech)'}"
        )
    prompt = (
        f"Stream: {stream['title']} by {stream['streamer']}.\n"
        f"Score each candidate. Choose start_offset/end_offset inside the window so the reel is "
        f"{max(12, W - 15)}-{min(cfg['render']['max_seconds'], W + 25)} seconds, starts right before the setup "
        f"and ends just after the payoff.\n\n" + "\n".join(lines)
    )
    out = ask_claude(cfg, SCORER_SYSTEM, prompt, MomentScores)
    return {m.candidate: m for m in (out.moments if out else [])}


def _timed_text(words: list[dict], t0: float) -> str:
    return " ".join(f"({w['start'] - t0:.0f}s){w['word']}" if i % 6 == 0 else w["word"]
                    for i, w in enumerate(words))


# ----------------------------------------------------------------- main
def analyze_stream(cfg: dict, stream: dict) -> list[dict]:
    a = cfg["analysis"]
    W = a["window_seconds"]
    audio = fetch_audio(cfg, stream)
    curve = loudness_curve(audio)
    if len(curve) < int(W / HOP):
        raise RuntimeError("audio too short")
    exc = excitement(curve)
    chat = chat_curve(fetch_chat(cfg, stream), len(curve))
    combined = exc + 0.6 * np.clip(chat, 0, None)
    peaks = pick_peaks(combined, a["candidates_to_transcribe"], a["min_gap_seconds"], margin_s=2)
    total = len(curve) * HOP

    cands = []
    for p in peaks:
        t = p * HOP
        start = max(0.0, t - 0.6 * W)
        end = min(total, start + W + 10)  # a little slack so Claude can trim
        wav = work_path(cfg, "tmp", f"{stream['video_id']}_{int(start)}.wav")
        run(["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.2f}", "-t", f"{end - start:.2f}", "-i", str(audio),
             "-ac", "1", "-ar", "16000", str(wav)])
        words = transcribe(cfg, wav, offset=start)
        wav.unlink(missing_ok=True)
        text = words_to_text(words)
        hits = keyword_hits(cfg, text)
        lz = float(exc[max(0, p - 4):p + 5].max())
        cz = float(chat[max(0, p - 4):p + 5].max())
        cands.append(dict(start=start, end=end, peak=t, loud_z=lz, chat_z=cz, hits=hits, text=text,
                          timed_text=_timed_text(words, start),
                          score=heuristic_score(lz, cz, hits, len(words)), category=max(hits, key=hits.get) if hits else "reaction",
                          quote=text[:120], reason="heuristic: loudness/chat/keywords"))

    # Claude re-scores the best heuristic candidates
    cands.sort(key=lambda c: c["score"], reverse=True)
    top = cands[: a["candidates_to_claude"]]
    scored = claude_rescore(cfg, stream, top) if top else {}
    for i, c in enumerate(top):
        m = scored.get(i)
        if not m:
            continue
        s = max(0.0, min(m.start_offset, c["end"] - c["start"] - 8))
        e = max(s + 8, min(m.end_offset, c["end"] - c["start"]))
        c.update(score=float(m.score), category=m.category, quote=m.quote, reason=m.reason,
                 start=c["start"] + s, end=c["start"] + e)
    if not scored:
        for c in cands:  # heuristic-only: default window W seconds around the peak
            c["end"] = min(c["start"] + W, total)

    conn = db(cfg)
    for c in cands:
        conn.execute(
            """INSERT OR IGNORE INTO moments(video_id, start, end, peak, score, category, quote, reason, status, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?)""",
            (stream["video_id"], round(c["start"], 2), round(c["end"], 2), c["peak"], c["score"], c["category"],
             c["quote"], c["reason"], "scored" if c["score"] >= cfg["score_threshold"] else "rejected", time.time()),
        )
    conn.commit()
    good = [c for c in cands if c["score"] >= cfg["score_threshold"]]
    for c in sorted(good, key=lambda c: -c["score"]):
        log.info("%s  %s  score %.0f  %r", fmt_ts(c["start"]), c["category"], c["score"], c["quote"][:60])
    log.info("%s: %d candidates, %d above %d", stream["video_id"], len(cands), len(good), cfg["score_threshold"])
    return good


def analyze_pending(cfg: dict | None = None) -> int:
    cfg = cfg or load_config()
    conn = db(cfg)
    rows = conn.execute(
        """SELECT * FROM streams WHERE status='new' AND live_status IN ('was_live','not_live','local')
           ORDER BY found_at DESC LIMIT ?""", (cfg["max_streams_per_run"],)).fetchall()
    n = 0
    for r in rows:
        stream = dict(r)
        conn.execute("UPDATE streams SET status='analyzing' WHERE video_id=?", (stream["video_id"],)); conn.commit()
        try:
            n += len(analyze_stream(cfg, stream))
            conn.execute("UPDATE streams SET status='analyzed' WHERE video_id=?", (stream["video_id"],))
        except Exception as e:
            log.error("analyze %s failed: %s", stream["video_id"], e)
            conn.execute("UPDATE streams SET status='failed' WHERE video_id=?", (stream["video_id"],))
        conn.commit()
    return n


if __name__ == "__main__":
    analyze_pending()
