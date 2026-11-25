"""Whisper wrapper. Prefers faster-whisper, falls back to openai-whisper.

Returns word-level timestamps: [{"start": float, "end": float, "word": str}, ...]
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from common import log

log = log.getChild("whisper")


@lru_cache(maxsize=2)
def _faster_model(name: str, device: str, compute_type: str):
    from faster_whisper import WhisperModel
    return WhisperModel(name, device=device, compute_type=compute_type)


@lru_cache(maxsize=2)
def _openai_model(name: str):
    import whisper
    return whisper.load_model(name)


def transcribe(cfg: dict, audio_path: Path | str, offset: float = 0.0) -> list[dict]:
    w = cfg.get("whisper", {})
    name, lang = w.get("model", "small"), w.get("language")
    words: list[dict] = []
    try:
        model = _faster_model(name, w.get("device", "auto"), w.get("compute_type", "int8"))
        segments, _ = model.transcribe(str(audio_path), language=lang, word_timestamps=True, vad_filter=True)
        for seg in segments:
            for wd in seg.words or []:
                words.append({"start": wd.start + offset, "end": wd.end + offset, "word": wd.word.strip()})
        return words
    except ImportError:
        pass
    try:
        model = _openai_model(name)
        res = model.transcribe(str(audio_path), language=lang, word_timestamps=True)
        for seg in res.get("segments", []):
            for wd in seg.get("words", []):
                words.append({"start": wd["start"] + offset, "end": wd["end"] + offset, "word": wd["word"].strip()})
        return words
    except ImportError:
        log.warning("no whisper backend installed (pip install faster-whisper); transcript empty")
        return []


def words_to_text(words: list[dict]) -> str:
    return " ".join(w["word"] for w in words).strip()
