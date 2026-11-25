"""Agent 4 - Editor.

Every reel uses the same 1080x1920 layout:

    +--------------------------+
    |                          |
    |   GAMEPLAY  (68%, 1306px)|   punch-in zooms on reaction peaks
    |                          |
    +--------------------------+   <- karaoke subtitles sit on this seam
    |   STREAMER FACECAM (32%) |
    +--------------------------+

Audio is two-pass loudness normalised (EBU R128, default -14 LUFS).
"""
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

import numpy as np

from agent2_stream_analyzer import HOP, excitement, loudness_curve
from common import (db, load_config, log, run, set_moment_status, streamer_cfg,
                    upsert_reel, video_size, work_path)
from facecam import detect_facecam

log = log.getChild("editor")


def font_file(family: str) -> str:
    try:
        out = subprocess.run(["fc-match", "-f", "%{file}", f"{family}:bold"], capture_output=True, text=True)
        if out.stdout:
            return out.stdout
    except FileNotFoundError:
        pass
    return "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


# ----------------------------------------------------------------- subtitles
def _ass_time(t: float) -> str:
    t = max(0.0, t)
    h, r = divmod(t, 3600)
    m, s = divmod(r, 60)
    return f"{int(h)}:{int(m):02d}:{s:05.2f}"


def _clean(w: str) -> str:
    return re.sub(r"[{}\\]", "", w).upper()


def write_ass(cfg: dict, words: list[dict], out: Path, seam_y: int) -> int:
    r = cfg["render"]
    per = r["subtitle_words_per_line"]
    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {r['width']}
PlayResY: {r['height']}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Cap,{r['subtitle_font']},{r['subtitle_size']},&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,-1,0,0,0,100,100,1,0,1,6,2,5,40,40,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    words = [w for w in words if w["word"].strip()]
    events = []
    for i in range(0, len(words), per):
        chunk = words[i:i + per]
        for j, w in enumerate(chunk):
            start = w["start"]
            end = chunk[j + 1]["start"] if j + 1 < len(chunk) else max(w["end"], start + 0.25)
            if j + 1 == len(chunk) and i + per < len(words):
                end = min(end + 0.4, words[i + per]["start"])
            parts = []
            for k, x in enumerate(chunk):
                txt = _clean(x["word"])
                parts.append("{\\c&H00FFFF&\\fscx112\\fscy112}" + txt + "{\\c&HFFFFFF&\\fscx100\\fscy100}" if k == j else txt)
            text = "{\\pos(%d,%d)}" % (r["width"] // 2, seam_y) + " ".join(parts)
            events.append(f"Dialogue: 0,{_ass_time(start)},{_ass_time(end)},Cap,,0,0,0,,{text}")
    out.write_text(header + "\n".join(events) + "\n", encoding="utf-8")
    return len(events)


# ----------------------------------------------------------------- geometry
def _even(x: float) -> int:
    return max(2, int(round(x / 2)) * 2)


def gameplay_crop(src_w: int, src_h: int, box: dict, out_w: int, out_h: int) -> tuple[int, int, int, int]:
    """Crop of the gameplay box with the target aspect ratio, centred on focus_x."""
    bx, by, bw, bh = box["x"] * src_w, box["y"] * src_h, box["w"] * src_w, box["h"] * src_h
    target = out_w / out_h
    if bw / bh > target:
        ch, cw = bh, bh * target
    else:
        cw, ch = bw, bw / target
    cx = bx + box.get("focus_x", 0.5) * bw
    x = min(max(bx, cx - cw / 2), bx + bw - cw)
    y = by + (bh - ch) / 2
    return _even(cw), _even(ch), _even(x) if x > 1 else 0, _even(y) if y > 1 else 0


def zoom_expr(peaks: list[float], amount: float, dur: float) -> str:
    if not peaks:
        return "1"
    # triangular punch-in around each peak: quick in, slower out
    terms = [f"max(0,1-abs(it-{p:.2f})/{dur:.2f})" for p in peaks]
    return f"1+{amount}*min(1,{'+'.join(terms)})"


def reaction_peaks(clip: Path, moment_peak_rel: float | None, n: int = 3) -> list[float]:
    curve = loudness_curve(clip)
    if len(curve) < 8:
        return []
    exc = excitement(curve)
    peaks: list[float] = []
    if moment_peak_rel is not None and 0 < moment_peak_rel < len(curve) * HOP:
        peaks.append(moment_peak_rel)
    for i in np.argsort(exc)[::-1]:
        t = i * HOP
        if len(peaks) >= n or exc[i] < 2.0:
            break
        if 1.0 < t < len(curve) * HOP - 1 and all(abs(t - p) > 4 for p in peaks):
            peaks.append(float(t))
    return sorted(peaks)


# ----------------------------------------------------------------- render
def measure_loudness(clip: Path, target: float) -> dict:
    p = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(clip), "-vn", "-af",
                        f"loudnorm=I={target}:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"],
                       capture_output=True, text=True)
    m = re.search(r"\{[^{}]*\"input_i\"[^{}]*\}", p.stderr, re.S)
    return json.loads(m.group(0)) if m else {}


def render(cfg: dict, moment: dict, reel: dict, attempt: int = 0) -> Path:
    r = cfg["render"]
    W, H = r["width"], r["height"]
    gh = _even(H * r["gameplay_ratio"])
    ch = H - gh
    clip = Path(reel["clip_path"])
    sw, sh = video_size(clip)
    conn = db(cfg)
    stream = conn.execute("SELECT * FROM streams WHERE video_id=?", (moment["video_id"],)).fetchone()
    st = streamer_cfg(cfg, stream["streamer"])
    layout = {**cfg["default_streamer"]["layout"], **st.get("layout", {})}

    gx = gameplay_crop(sw, sh, layout["gameplay"], W, gh)
    fc = layout.get("facecam", "auto")
    if fc == "auto":
        box = detect_facecam(clip)
        fc = dict(zip("xywh", box)) if box else None
    if fc:
        cam = (_even(fc["w"] * sw), _even(fc["h"] * sh), _even(fc["x"] * sw), _even(fc["y"] * sh))
    else:  # no facecam: bottom panel shows a tighter crop of the action so the layout stays identical
        log.info("no facecam found for m%d; using gameplay close-up for the bottom panel", moment["id"])
        cw = _even(sw * 0.5); chh = _even(cw * ch / W)
        cam = (cw, chh, _even((sw - cw) / 2), _even((sh - chh) / 2))

    words = json.loads(Path(reel["words_path"]).read_text()) if reel.get("words_path") else []
    subs = work_path(cfg, "subs", f"m{moment['id']}.ass")
    write_ass(cfg, words, subs, seam_y=gh)

    peak_rel = (moment["peak"] - moment["start"]) if moment.get("peak") else None
    zoom = zoom_expr(reaction_peaks(clip, peak_rel), r["zoom_amount"] * (0 if attempt >= 2 else 1), r["zoom_seconds"])
    credit = r["credit_text"].format(streamer=stream["streamer"]).replace(":", r"\:").replace("'", "")
    font = font_file(r["subtitle_font"])
    fps = r["fps"]

    vf = (
        f"[0:v]fps={fps},split=2[g][f];"
        f"[g]crop={gx[0]}:{gx[1]}:{gx[2]}:{gx[3]},scale={W * 2}:{gh * 2}:flags=lanczos,"
        f"zoompan=z='{zoom}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s={W}x{gh}:fps={fps}[game];"
        f"[f]crop={cam[0]}:{cam[1]}:{cam[2]}:{cam[3]},scale={W}:{ch}:force_original_aspect_ratio=increase:flags=lanczos,"
        f"crop={W}:{ch},setsar=1[cam];"
        f"[game][cam]vstack=inputs=2,"
        f"drawbox=x=0:y={gh - 3}:w={W}:h=6:color=black@0.9:t=fill,"
        f"ass='{subs}',"
        f"drawtext=fontfile='{font}':text='{credit}':x=36:y=48:fontsize=40:fontcolor=white:"
        f"box=1:boxcolor=black@0.45:boxborderw=12,format=yuv420p[v]"
    )
    m = measure_loudness(clip, r["loudness_lufs"])
    af = f"loudnorm=I={r['loudness_lufs']}:TP=-1.5:LRA=11"
    if m and m.get("input_i") not in (None, "-inf"):
        af += (f":measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}"
               f":measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true")
    af += ",aresample=48000"

    out = work_path(cfg, "reels", f"m{moment['id']}.mp4")
    run(["ffmpeg", "-v", "error", "-y", "-i", str(clip), "-filter_complex", vf, "-map", "[v]", "-map", "0:a:0?",
         "-af", af, "-t", str(r["max_seconds"] - 0.5),
         "-c:v", "libx264", "-preset", r["preset"], "-crf", str(r["crf"]), "-profile:v", "high",
         "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-movflags", "+faststart", str(out)], timeout=3600)
    upsert_reel(conn, moment["id"], reel_path=str(out), subs_path=str(subs))
    set_moment_status(conn, moment["id"], "rendered")
    log.info("rendered m%d -> %s", moment["id"], out)
    return out


if __name__ == "__main__":
    cfg = load_config()
    conn = db(cfg)
    for row in conn.execute("SELECT m.*, r.clip_path, r.words_path FROM moments m JOIN reels r ON r.moment_id=m.id "
                            "WHERE m.status='clipped' LIMIT 1"):
        d = dict(row)
        render(cfg, d, d)
