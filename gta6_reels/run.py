#!/usr/bin/env python3
"""Orchestrator: `python run.py` is the only thing the scheduled task runs.

Each run:
  1. Agent 1 (hourly)            find new GTA 6 streams
  2. Agent 2                     analyse finished streams -> scored moments
  3. if a reel is due (every reel_interval_minutes):
       best unused moment >= threshold
       Agent 3 clip -> Agent 4 edit -> Agent 5 captions/thumbnail -> Agent 6 QA
       (QA failure -> re-render, up to max_qa_attempts; duplicate -> next moment)
       Agent 7 publish (or dry-run to work/outbox/)
  4. refresh Instagram stats
"""
from __future__ import annotations

import argparse
import fcntl
import json
import sys
import time
from pathlib import Path

from common import db, fmt_ts, load_config, log

log = log.getChild("run")


def _last_ok(conn, stage: str) -> float:
    r = conn.execute("SELECT MAX(finished) FROM runs WHERE stage=? AND ok=1", (stage,)).fetchone()
    return r[0] or 0.0


def _record(conn, stage: str, started: float, ok: bool, note: str = "") -> None:
    conn.execute("INSERT INTO runs(stage, started, finished, ok, note) VALUES (?,?,?,?,?)",
                 (stage, started, time.time(), int(ok), note[:500]))
    conn.commit()


def stage(conn, name: str, fn, *args):
    t0 = time.time()
    try:
        out = fn(*args)
        _record(conn, name, t0, True, str(out))
        return out
    except Exception as e:
        log.exception("%s failed", name)
        _record(conn, name, t0, False, str(e))
        return None


def next_moment(cfg: dict, conn) -> dict | None:
    prio = {s["name"]: s.get("priority", 99) for s in cfg["streamers"]}
    rows = [dict(r) for r in conn.execute(
        "SELECT m.*, s.streamer FROM moments m JOIN streams s USING(video_id) "
        "WHERE m.status IN ('scored','clipped','rendered','captioned','qa_failed') AND m.score >= ? "
        "AND m.attempts < ?", (cfg["score_threshold"], cfg["max_qa_attempts"]))]
    rows.sort(key=lambda m: (-m["score"], prio.get(m["streamer"], 99)))
    return rows[0] if rows else None


def reel_for(conn, moment_id: int) -> dict:
    r = conn.execute("SELECT * FROM reels WHERE moment_id=?", (moment_id,)).fetchone()
    return dict(r) if r else {}


def produce_one(cfg: dict) -> dict | None:
    from agent3_clip_generator import make_clip
    from agent4_video_editor import render
    from agent5_caption_writer import package
    from agent6_quality_checker import check
    from agent7_instagram_publisher import publish

    conn = db(cfg)
    while True:
        m = next_moment(cfg, conn)
        if not m:
            log.info("no unused moment >= %d yet", cfg["score_threshold"])
            return None
        log.info("producing m%d  %s @ %s  score %.0f (%s)", m["id"], m["streamer"], fmt_ts(m["start"]),
                 m["score"], m["category"])
        try:
            if not reel_for(conn, m["id"]).get("clip_path"):
                make_clip(cfg, m)
            for attempt in range(m["attempts"], cfg["max_qa_attempts"]):
                conn.execute("UPDATE moments SET attempts=? WHERE id=?", (attempt + 1, m["id"])); conn.commit()
                render(cfg, m, reel_for(conn, m["id"]), attempt)
                package(cfg, m, reel_for(conn, m["id"]))
                ok, report = check(cfg, m, reel_for(conn, m["id"]))
                if ok:
                    conn.execute("UPDATE moments SET status='qa_passed' WHERE id=?", (m["id"],)); conn.commit()
                    publish(cfg, m, reel_for(conn, m["id"]))
                    return reel_for(conn, m["id"])
                if not report.get("no_duplicate", {}).get("ok", True):
                    log.info("m%d is a duplicate, skipping", m["id"])
                    conn.execute("UPDATE moments SET status='skipped' WHERE id=?", (m["id"],)); conn.commit()
                    break
                log.info("QA failed (attempt %d), recreating", attempt + 1)
                conn.execute("UPDATE moments SET status='qa_failed' WHERE id=?", (m["id"],)); conn.commit()
            else:
                conn.execute("UPDATE moments SET status='skipped' WHERE id=?", (m["id"],)); conn.commit()
        except Exception:
            log.exception("m%d failed", m["id"])
            conn.execute("UPDATE moments SET status='qa_failed', attempts=attempts+1 WHERE id=?", (m["id"],))
            conn.commit()


def status(cfg: dict) -> None:
    conn = db(cfg)
    print("streams:", dict(conn.execute("SELECT status, COUNT(*) FROM streams GROUP BY status").fetchall()))
    print("moments:", dict(conn.execute("SELECT status, COUNT(*) FROM moments GROUP BY status").fetchall()))
    for r in conn.execute("SELECT m.id, s.streamer, m.score, m.status, r.title, r.ig_url, r.views, r.likes "
                          "FROM reels r JOIN moments m ON m.id=r.moment_id JOIN streams s USING(video_id) "
                          "ORDER BY r.updated_at DESC LIMIT 10"):
        print(dict(r))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config")
    ap.add_argument("--force", action="store_true", help="make a reel now, ignoring reel_interval_minutes")
    ap.add_argument("--skip-search", action="store_true")
    ap.add_argument("--local", help="register a local video file as a source (testing / own recordings)")
    ap.add_argument("--streamer", default="Local", help="streamer name for --local")
    ap.add_argument("--status", action="store_true", help="print a summary of memory.db and exit")
    a = ap.parse_args(argv)

    cfg = load_config(a.config)
    if a.status:
        status(cfg)
        return 0
    lock = open(Path(cfg["work_dir"]) / ".run.lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        log.info("another run is in progress; exiting")
        return 0

    conn = db(cfg)
    from agent1_trend_finder import add_local_source, find_streams
    from agent2_stream_analyzer import analyze_pending
    from agent7_instagram_publisher import refresh_stats

    if a.local:
        add_local_source(cfg, a.local, a.streamer)
    if not a.skip_search and time.time() - _last_ok(conn, "trend") > 55 * 60:
        stage(conn, "trend", find_streams, cfg)
    stage(conn, "analyze", analyze_pending, cfg)

    due = time.time() - _last_ok(conn, "reel") >= cfg["reel_interval_minutes"] * 60
    if due or a.force:
        reel = stage(conn, "reel", produce_one, cfg)
        if reel:
            print(json.dumps({k: reel.get(k) for k in ("moment_id", "reel_path", "thumb_path", "title", "ig_url")},
                             ensure_ascii=False, indent=2))
        else:  # don't burn the 2h slot when nothing was made
            conn.execute("UPDATE runs SET ok=0, note='nothing produced' WHERE id=(SELECT MAX(id) FROM runs WHERE stage='reel')")
            conn.commit()
    else:
        log.info("next reel in %.0f min", (cfg["reel_interval_minutes"] * 60 - (time.time() - _last_ok(conn, "reel"))) / 60)
    stage(conn, "stats", refresh_stats, cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
