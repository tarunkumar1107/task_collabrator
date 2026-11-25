"""Agent 7 - Instagram Publisher (Meta Instagram Graph API, Reels).

Flow (resumable upload, so the file doesn't need a public URL):
  1. POST /{ig-user-id}/media  media_type=REELS upload_type=resumable  -> container id + upload uri
  2. POST rupload.facebook.com/...  (file bytes)
  3. poll  GET /{container}?fields=status_code   until FINISHED
  4. POST /{ig-user-id}/media_publish creation_id=...
  5. save post id / permalink, optionally post the first comment
`refresh_stats()` pulls views / likes / comments back into memory.db.

Safety gates (both on by default):
  * instagram.enabled=false  -> dry run: writes work/outbox/<id>.json instead of posting
  * instagram.require_permission=true -> only posts clips whose streamer has
    "permission": "granted" or "own_channel" in config.json
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

import requests

from common import db, load_config, log, set_moment_status, streamer_cfg, upsert_reel

log = log.getChild("instagram")
GRAPH = "https://graph.facebook.com"


class IG:
    def __init__(self, cfg: dict):
        c = cfg["instagram"]
        self.ver = c["graph_version"]
        self.user = os.environ.get(c["ig_user_id_env"], "")
        self.token = os.environ.get(c["access_token_env"], "")
        if not (self.user and self.token):
            raise RuntimeError(f"set {c['ig_user_id_env']} and {c['access_token_env']} to publish")

    def _url(self, path: str) -> str:
        return f"{GRAPH}/{self.ver}/{path.lstrip('/')}"

    def _check(self, r: requests.Response) -> dict:
        try:
            data = r.json()
        except ValueError:
            data = {"raw": r.text[:500]}
        if r.status_code >= 400 or "error" in data:
            raise RuntimeError(f"Graph API {r.status_code}: {data.get('error', data)}")
        return data

    def get(self, path: str, **params) -> dict:
        return self._check(requests.get(self._url(path), params={**params, "access_token": self.token}, timeout=60))

    def post(self, path: str, **data) -> dict:
        return self._check(requests.post(self._url(path), data={**data, "access_token": self.token}, timeout=120))

    def publish_reel(self, video: Path, caption: str, thumb_offset_ms: int, share_to_feed: bool,
                     cover_url: str | None = None) -> dict:
        params = dict(media_type="REELS", upload_type="resumable", caption=caption,
                      share_to_feed=str(share_to_feed).lower())
        if cover_url:
            params["cover_url"] = cover_url
        else:
            params["thumb_offset"] = str(thumb_offset_ms)
        cont = self.post(f"{self.user}/media", **params)
        cid, uri = cont["id"], cont.get("uri") or f"https://rupload.facebook.com/ig-api-upload/{self.ver}/{cont['id']}"
        size = video.stat().st_size
        with open(video, "rb") as f:
            self._check(requests.post(uri, data=f, timeout=900, headers={
                "Authorization": f"OAuth {self.token}", "offset": "0", "file_size": str(size)}))
        for _ in range(60):  # up to ~10 minutes
            st = self.get(cid, fields="status_code,status")
            if st.get("status_code") == "FINISHED":
                break
            if st.get("status_code") in ("ERROR", "EXPIRED"):
                raise RuntimeError(f"container {cid} failed: {st}")
            time.sleep(10)
        else:
            raise RuntimeError(f"container {cid} still processing")
        media = self.post(f"{self.user}/media_publish", creation_id=cid)
        info = self.get(media["id"], fields="permalink,like_count,comments_count")
        return {"container_id": cid, "media_id": media["id"], **info}


def can_publish(cfg: dict, streamer: str) -> tuple[bool, str]:
    if not cfg["instagram"].get("require_permission", True):
        return True, "permission check disabled"
    perm = streamer_cfg(cfg, streamer).get("permission", "none")
    if perm in ("granted", "own_channel"):
        return True, perm
    return False, (f"no permission on record for {streamer} (set \"permission\": \"granted\" in config.json "
                   f"once they have agreed to clips being reposted)")


def last_publish_time(cfg: dict) -> float:
    row = db(cfg).execute("SELECT MAX(published_at) FROM reels").fetchone()
    return row[0] or 0.0


def publish(cfg: dict, moment: dict, reel: dict) -> dict | None:
    conn = db(cfg)
    stream = conn.execute("SELECT streamer FROM streams WHERE video_id=?", (moment["video_id"],)).fetchone()
    ok, why = can_publish(cfg, stream["streamer"])
    payload = {"moment_id": moment["id"], "video": reel["reel_path"], "thumbnail": reel["thumb_path"],
               "caption": reel["caption"], "pinned_comment": reel["pinned_comment"]}
    if not cfg["instagram"].get("enabled") or not ok:
        outbox = Path(cfg["work_dir"]) / "outbox"
        outbox.mkdir(exist_ok=True)
        (outbox / f"m{moment['id']}.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2))
        reason = "instagram.enabled=false (dry run)" if ok else why
        log.info("m%d ready, not posted: %s -> %s", moment["id"], reason, outbox / f"m{moment['id']}.json")
        set_moment_status(conn, moment["id"], "ready")
        return None

    ig = IG(cfg)
    peak = (moment["peak"] - moment["start"]) if moment.get("peak") else 1.0
    base = cfg["instagram"].get("cover_base_url")
    cover = f"{base.rstrip('/')}/{Path(reel['thumb_path']).name}" if base else None
    res = ig.publish_reel(Path(reel["reel_path"]), reel["caption"], int(max(0, peak) * 1000),
                          cfg["instagram"].get("share_to_feed", True), cover)
    if cfg["instagram"].get("post_pinned_comment") and reel.get("pinned_comment"):
        try:
            ig.post(f"{res['media_id']}/comments", message=reel["pinned_comment"])
        except Exception as e:
            log.warning("first comment failed: %s", e)
    upsert_reel(conn, moment["id"], ig_container_id=res["container_id"], ig_media_id=res["media_id"],
                ig_url=res.get("permalink"), likes=res.get("like_count"), comments=res.get("comments_count"),
                published_at=time.time())
    set_moment_status(conn, moment["id"], "published")
    log.info("published m%d -> %s", moment["id"], res.get("permalink"))
    return res


def refresh_stats(cfg: dict | None = None, days: int = 14) -> int:
    cfg = cfg or load_config()
    if not cfg["instagram"].get("enabled"):
        return 0
    ig, conn = IG(cfg), db(cfg)
    rows = conn.execute("SELECT moment_id, ig_media_id FROM reels WHERE ig_media_id IS NOT NULL AND published_at > ?",
                        (time.time() - days * 86400,)).fetchall()
    for r in rows:
        try:
            info = ig.get(r["ig_media_id"], fields="like_count,comments_count")
            ins = ig.get(f"{r['ig_media_id']}/insights", metric="views")
            views = next((d["values"][0]["value"] for d in ins.get("data", []) if d["name"] == "views"), None)
            upsert_reel(conn, r["moment_id"], views=views, likes=info.get("like_count"),
                        comments=info.get("comments_count"))
        except Exception as e:
            log.warning("stats for %s: %s", r["ig_media_id"], e)
    return len(rows)


if __name__ == "__main__":
    refresh_stats()
