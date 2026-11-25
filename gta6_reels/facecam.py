"""Locate the streamer's webcam overlay with OpenCV when the layout isn't configured.

Facecams are static overlays, so we sample frames across the clip, detect faces,
and keep the location that is consistent across frames. The overlay box is then
estimated by expanding the face box to a 16:9 rectangle and snapping it to the
frame edges it is close to.
Returns normalised (x, y, w, h) or None.
"""
from __future__ import annotations

from pathlib import Path

from common import log

log = log.getChild("facecam")


def detect_facecam(video: Path | str, samples: int = 12) -> tuple[float, float, float, float] | None:
    try:
        import cv2
        import numpy as np
    except ImportError:
        log.warning("opencv not installed; cannot auto-detect facecam")
        return None
    cap = cv2.VideoCapture(str(video))
    n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    W, H = cap.get(cv2.CAP_PROP_FRAME_WIDTH), cap.get(cv2.CAP_PROP_FRAME_HEIGHT)
    cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    faces = []
    for k in range(samples):
        cap.set(cv2.CAP_PROP_POS_FRAMES, int((k + 0.5) * n / samples))
        ok, frame = cap.read()
        if not ok:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        for (x, y, w, h) in cascade.detectMultiScale(gray, 1.1, 6, minSize=(int(H * 0.05), int(H * 0.05))):
            faces.append((x + w / 2, y + h / 2, w, h))
    cap.release()
    if not faces:
        return None
    f = np.array(faces)
    # most consistent cluster: the face centre with the most neighbours within 6% of frame width
    d = np.hypot(f[:, None, 0] - f[None, :, 0], f[:, None, 1] - f[None, :, 1])
    support = (d < 0.06 * W).sum(axis=1)
    best = support.argmax()
    if support[best] < max(3, samples // 3):
        log.info("faces found but not stable across frames (gameplay NPCs?)")
        return None
    cl = f[d[best] < 0.06 * W]
    cx, cy, fw, fh = np.median(cl, axis=0)
    bw = min(W * 0.45, fw * 3.4)
    bh = bw * 9 / 16
    x0, y0 = cx - bw / 2, cy - bh * 0.45
    # snap to edges/corners that are close (overlays usually sit in a corner)
    if x0 < W * 0.06: x0 = 0
    if x0 + bw > W * 0.94: x0 = W - bw
    if y0 < H * 0.06: y0 = 0
    if y0 + bh > H * 0.94: y0 = H - bh
    x0, y0 = max(0, min(x0, W - bw)), max(0, min(y0, H - bh))
    box = (x0 / W, y0 / H, bw / W, bh / H)
    log.info("facecam auto-detected at x=%.2f y=%.2f w=%.2f h=%.2f", *box)
    return box
