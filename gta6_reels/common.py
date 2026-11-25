"""Shared helpers: config, SQLite memory, logging, subprocess, ffprobe, Claude."""
from __future__ import annotations

import json
import logging
import os
import sqlite3
import subprocess
import time
from pathlib import Path
from typing import Any, Optional, Type, TypeVar

ROOT = Path(__file__).resolve().parent
CONFIG_PATH = Path(os.environ.get("REELS_CONFIG", ROOT / "config.json"))

log = logging.getLogger("reels")
if not log.handlers:
    logging.basicConfig(
        level=os.environ.get("REELS_LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )


# --------------------------------------------------------------------------- config
def load_config(path: Path | str | None = None) -> dict:
    cfg = json.loads(Path(path or CONFIG_PATH).read_text())
    work = Path(cfg.get("work_dir", "work"))
    cfg["work_dir"] = str(work if work.is_absolute() else ROOT / work)
    db = Path(cfg.get("db_path", "memory.db"))
    cfg["db_path"] = str(db if db.is_absolute() else ROOT / db)
    for sub in ("audio", "clips", "reels", "thumbs", "subs", "tmp"):
        (Path(cfg["work_dir"]) / sub).mkdir(parents=True, exist_ok=True)
    return cfg


def work_path(cfg: dict, sub: str, name: str) -> Path:
    return Path(cfg["work_dir"]) / sub / name


def streamer_cfg(cfg: dict, name: str) -> dict:
    for s in cfg.get("streamers", []):
        if s["name"].lower() == (name or "").lower():
            return s
    return cfg.get("default_streamer", {})


# --------------------------------------------------------------------------- database
SCHEMA = """
CREATE TABLE IF NOT EXISTS streams (
    video_id     TEXT PRIMARY KEY,
    streamer     TEXT,
    url          TEXT,
    title        TEXT,
    duration     REAL,
    viewers      INTEGER,
    live_status  TEXT,          -- is_live | was_live | not_live | local
    found_at     REAL,
    status       TEXT DEFAULT 'new'   -- new | analyzing | analyzed | failed
);
CREATE TABLE IF NOT EXISTS moments (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    video_id     TEXT,
    start        REAL,
    end          REAL,
    peak         REAL,
    score        REAL,
    category     TEXT,
    quote        TEXT,
    reason       TEXT,
    status       TEXT DEFAULT 'scored', -- scored | rejected | clipped | rendered | captioned | qa_passed | qa_failed | published | skipped
    attempts     INTEGER DEFAULT 0,
    created_at   REAL,
    UNIQUE(video_id, start)
);
CREATE TABLE IF NOT EXISTS reels (
    moment_id     INTEGER PRIMARY KEY,
    clip_path     TEXT,
    words_path    TEXT,
    reel_path     TEXT,
    subs_path     TEXT,
    thumb_path    TEXT,
    title         TEXT,
    caption       TEXT,
    hashtags      TEXT,
    pinned_comment TEXT,
    seo_keywords  TEXT,
    qa_report     TEXT,
    sha256        TEXT,
    ig_container_id TEXT,
    ig_media_id   TEXT,
    ig_url        TEXT,
    views         INTEGER,
    likes         INTEGER,
    comments      INTEGER,
    published_at  REAL,
    updated_at    REAL
);
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stage TEXT, started REAL, finished REAL, ok INTEGER, note TEXT
);
"""


def db(cfg: dict) -> sqlite3.Connection:
    conn = sqlite3.connect(cfg["db_path"], timeout=30)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    return conn


def upsert_reel(conn: sqlite3.Connection, moment_id: int, **fields: Any) -> None:
    fields["updated_at"] = time.time()
    conn.execute("INSERT OR IGNORE INTO reels(moment_id) VALUES (?)", (moment_id,))
    sets = ", ".join(f"{k}=?" for k in fields)
    conn.execute(f"UPDATE reels SET {sets} WHERE moment_id=?", (*fields.values(), moment_id))
    conn.commit()


def set_moment_status(conn: sqlite3.Connection, moment_id: int, status: str) -> None:
    conn.execute("UPDATE moments SET status=? WHERE id=?", (status, moment_id))
    conn.commit()


# --------------------------------------------------------------------------- processes
def run(cmd: list[str], timeout: Optional[float] = None, capture: bool = True) -> subprocess.CompletedProcess:
    log.debug("$ %s", " ".join(map(str, cmd)))
    p = subprocess.run(
        [str(c) for c in cmd], capture_output=capture, text=True, timeout=timeout
    )
    if p.returncode != 0:
        tail = (p.stderr or "")[-2000:]
        raise RuntimeError(f"command failed ({p.returncode}): {' '.join(map(str, cmd[:6]))} ...\n{tail}")
    return p


def ffprobe(path: Path | str) -> dict:
    p = run(["ffprobe", "-v", "error", "-print_format", "json",
             "-show_format", "-show_streams", str(path)])
    return json.loads(p.stdout)


def media_duration(path: Path | str) -> float:
    return float(ffprobe(path)["format"]["duration"])


def video_size(path: Path | str) -> tuple[int, int]:
    for s in ffprobe(path)["streams"]:
        if s.get("codec_type") == "video":
            return int(s["width"]), int(s["height"])
    raise RuntimeError(f"no video stream in {path}")


def fmt_ts(sec: float) -> str:
    sec = max(0.0, sec)
    h, rem = divmod(int(sec), 3600)
    m, s = divmod(rem, 60)
    return f"{h:02d}:{m:02d}:{s:02d}"


# --------------------------------------------------------------------------- Claude
T = TypeVar("T")


def claude_enabled(cfg: dict) -> bool:
    if not cfg.get("claude", {}).get("enabled", True):
        return False
    return bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")
                or os.environ.get("ANTHROPIC_PROFILE") or Path.home().joinpath(".config/anthropic").exists())


def ask_claude(cfg: dict, system: str, prompt: str, schema: Type[T]) -> Optional[T]:
    """Structured-output call to Claude. Returns None if Claude is unavailable or refuses."""
    if not claude_enabled(cfg):
        return None
    try:
        import anthropic
    except ImportError:
        log.warning("anthropic package not installed; using heuristics")
        return None
    c = cfg.get("claude", {})
    client = anthropic.Anthropic()
    try:
        resp = client.beta.messages.parse(
            model=c.get("model", "claude-opus-5-5"),
            max_tokens=c.get("max_tokens", 16000),
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            output_config={"effort": c.get("effort", "medium")},
            system=system,
            messages=[{"role": "user", "content": prompt}],
            output_format=schema,
        )
    except anthropic.RateLimitError as e:
        log.warning("Claude rate limited: %s", e)
        return None
    except anthropic.APIStatusError as e:
        log.warning("Claude API error %s: %s", e.status_code, e.message)
        return None
    except anthropic.APIConnectionError as e:
        log.warning("Claude unreachable: %s", e)
        return None
    if resp.stop_reason == "refusal":
        log.warning("Claude declined the request")
        return None
    return resp.parsed_output
