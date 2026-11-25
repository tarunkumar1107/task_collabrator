"""Offline end-to-end test: synthetic 'stream' -> moment -> clip -> reel -> QA -> dry-run publish.

No network, no Whisper model, no Claude: transcription is stubbed and Claude disabled.
Run:  python -m unittest discover -s tests -v
"""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import agent2_stream_analyzer  # noqa: E402
import agent3_clip_generator  # noqa: E402
import run as runner  # noqa: E402
from common import db, ffprobe, load_config  # noqa: E402

FAKE = "arey bhai what the hell police aa gayi haha bhaag bhaag crash ho gaya".split()


def fake_transcribe(cfg, path, offset=0.0):
    dur = float(ffprobe(path)["format"]["duration"])
    step = dur / (len(FAKE) + 1)
    return [{"start": offset + (i + 0.5) * step, "end": offset + (i + 1.2) * step, "word": w}
            for i, w in enumerate(FAKE)]


def make_stream(path: Path, seconds: int = 240, burst_at: int = 150):
    # 1920x1080 gameplay-ish video with a 'facecam' box bottom-right, quiet audio + one loud scream burst
    vf = ("drawbox=x=1400:y=740:w=480:h=300:color=0xd08060:t=fill,"
          "drawbox=x=1580:y=800:w=120:h=150:color=0x503020:t=fill")
    af = (f"anoisesrc=d={seconds}:c=pink:a=0.02[n];"
          f"sine=f=440:d={seconds},volume='if(between(t,{burst_at},{burst_at + 4}),0.9,0.01)':eval=frame[s];"
          f"[n][s]amix=inputs=2:normalize=0")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=s=1920x1080:r=30:d={seconds}",
                    "-filter_complex", af, "-vf", vf, "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                    "-shortest", str(path)], check=True)


class PipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cfg = json.loads((ROOT / "config.json").read_text())
        cfg.update(work_dir=str(cls.tmp / "work"), db_path=str(cls.tmp / "memory.db"), score_threshold=60)
        cfg["claude"]["enabled"] = False
        cfg["analysis"]["use_live_chat"] = False
        cfg["render"]["preset"] = "ultrafast"
        cfg["streamers"].append({"name": "TestStreamer", "channel": "", "permission": "granted",
                                 "layout": {"facecam": {"x": 0.729, "y": 0.685, "w": 0.25, "h": 0.278}}})
        cls.cfg_path = cls.tmp / "config.json"
        cls.cfg_path.write_text(json.dumps(cfg))
        cls.video = cls.tmp / "stream.mp4"
        make_stream(cls.video)
        agent2_stream_analyzer.transcribe = fake_transcribe
        agent3_clip_generator.transcribe = fake_transcribe

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_end_to_end(self):
        rc = runner.main(["--config", str(self.cfg_path), "--skip-search", "--force",
                          "--local", str(self.video), "--streamer", "TestStreamer"])
        self.assertEqual(rc, 0)
        cfg = load_config(self.cfg_path)
        conn = db(cfg)
        best = conn.execute("SELECT * FROM moments ORDER BY score DESC LIMIT 1").fetchone()
        self.assertIsNotNone(best, "no moments found")
        self.assertTrue(best["start"] <= 150 <= best["end"], f"burst at 150s not in {best['start']}-{best['end']}")
        reel = conn.execute("SELECT * FROM reels WHERE moment_id=?", (best["id"],)).fetchone()
        self.assertEqual(best["status"], "ready", reel["qa_report"])  # dry run => ready, not published
        qa = json.loads(reel["qa_report"])
        self.assertTrue(all(v["ok"] for v in qa.values()), qa)
        info = ffprobe(reel["reel_path"])
        v = [s for s in info["streams"] if s["codec_type"] == "video"][0]
        self.assertEqual((v["width"], v["height"]), (1080, 1920))
        self.assertTrue(Path(reel["thumb_path"]).exists())
        self.assertIn("#GTA6", reel["caption"])
        self.assertTrue((Path(cfg["work_dir"]) / "outbox" / f"m{best['id']}.json").exists())

        # second forced run must not reuse an overlapping moment
        runner.main(["--config", str(self.cfg_path), "--skip-search", "--force"])
        made = conn.execute("SELECT m.video_id, m.start, m.end FROM moments m WHERE status='ready'").fetchall()
        for i, a in enumerate(made):
            for b in made[i + 1:]:
                self.assertFalse(a["start"] < b["end"] and a["end"] > b["start"], "overlapping reels")


class UnitTests(unittest.TestCase):
    def test_gameplay_crop_aspect(self):
        from agent4_video_editor import gameplay_crop
        w, h, x, y = gameplay_crop(1920, 1080, {"x": 0, "y": 0, "w": 1, "h": 1, "focus_x": 0.5}, 1080, 1306)
        self.assertAlmostEqual(w / h, 1080 / 1306, places=2)
        self.assertLessEqual(x + w, 1920)

    def test_permission_gate(self):
        from agent7_instagram_publisher import can_publish
        cfg = load_config(ROOT / "config.json")
        self.assertFalse(can_publish(cfg, "CarryMinati")[0])


if __name__ == "__main__":
    unittest.main()
