# GTA 6 Reels Factory

Seven small agents that turn Indian GTA 6 streams into ready-to-post 1080×1920
Instagram Reels. A Claude Code scheduled task runs `python run.py`; everything
else is automatic. All state lives in `memory.db` (SQLite).

```
gta6_reels/
├── run.py                       orchestrator (the only thing the scheduler runs)
├── agent1_trend_finder.py       find GTA 6 streams (yt-dlp metadata)
├── agent2_stream_analyzer.py    audio-only download → loudness + chat curve → Whisper on peaks → score
├── agent3_clip_generator.py     download only the chosen section, word-level transcript
├── agent4_video_editor.py       68/32 layout, zooms, karaoke subtitles, loudnorm
├── agent5_caption_writer.py     title, caption, hashtags, pinned comment, SEO, thumbnail
├── agent6_quality_checker.py    duplicate / subs / 1080x1920 / <90s / loudness / thumbnail
├── agent7_instagram_publisher.py  Graph API Reels upload + stats refresh
├── common.py  transcribe.py  facecam.py
├── config.json                  streamers, thresholds, layout, API settings
├── prompt.md                    prompt for the Claude Code scheduled task
└── tests/test_pipeline.py       offline end-to-end test
```

## How a run works

| Step | What happens | Cost-saving trick |
|---|---|---|
| 1 Trend hunter | Reads each priority channel's `/streams` + `/videos` tabs and a few searches, keeps titles matching GTA 6, stores url/title/duration/viewers. Live streams are recorded and analysed once the VOD exists. | metadata only |
| 2 Moment finder | Downloads **audio only**, builds a loudness-excitement curve (vs a rolling 5-min baseline) plus live-chat velocity from the chat replay, picks the top 25 peaks, runs Whisper **only on those windows**, scores them with keyword heuristics, then Claude re-scores the best 15 with the transcript and trims start/end. Only `score >= 90` continue. | never transcribes 5 hours |
| 3 Clip generator | `yt-dlp --download-sections` fetches just those ~35 s, then a word-level transcript. | never downloads the full VOD |
| 4 Editor | Gameplay crop → 1080×1306 with punch-in zooms on reaction peaks; facecam crop → 1080×614 (from `config.json` or OpenCV auto-detect); stacked; karaoke subtitles on the seam; streamer credit; two-pass EBU R128 loudnorm to −14 LUFS; H.264 + faststart. | |
| 5 Caption writer | Claude (structured output) writes title / caption / 8-12 hashtags / pinned comment / SEO keywords / thumbnail text; template fallback without Claude. Always credits the streamer. Thumbnail = peak frame + big title. | |
| 6 Quality checker | All checks must pass; a failure re-renders (attempt 3 drops the zoom), a duplicate moves to the next moment. | |
| 7 Publisher | Resumable Reels upload, waits for processing, publishes, posts the first comment, saves media id / permalink; each run refreshes views / likes / comments. | |

A reel is made at most every `reel_interval_minutes` (120). Hourly runs in between only search and analyse.

## Setup

```bash
sudo apt-get install -y ffmpeg fontconfig fonts-dejavu-core
```

```bash
pip install -r requirements.txt
```

```bash
export ANTHROPIC_API_KEY=...          # optional: Claude scoring + captions (heuristics/templates without it)
export IG_USER_ID=...                 # Instagram professional account id
export IG_ACCESS_TOKEN=...            # token with instagram_content_publish (+ instagram_manage_comments, instagram_manage_insights)
```

Then edit `config.json`:

* **Check the channel URLs** for each streamer — the `@handles` are best guesses; point them at the channel that actually hosts their GTA 6 streams.
* **Facecam position**: `"layout": {"facecam": {"x":0.72,"y":0.68,"w":0.27,"h":0.30}}` (fractions of the frame) per streamer. Leave `"auto"` to let OpenCV find it; if no stable face is found the bottom panel shows a gameplay close-up so the layout never changes.
* `"focus_x"` in the gameplay box shifts the 68% crop left/right (0.5 = centre).

## Publishing is off by default — on purpose

Two gates in `config.json`:

1. `instagram.enabled: false` → dry run. Finished reels land in `work/reels/`, `work/thumbs/` and `work/outbox/m<ID>.json` (caption + pinned comment) for you to review or post by hand.
2. `instagram.require_permission: true` → a reel is only posted if that streamer's `permission` is `"granted"` (they agreed to clips being reposted on your page) or `"own_channel"`.

Reposting someone else's stream footage without permission is copyright infringement on both YouTube and Instagram: Meta removes such Reels, issues strikes, and can disable the account. Many Indian creators *do* allow clip pages, sometimes with credit or revenue-share terms, so message them or their managers, keep the reply, and only then flip `permission` for that streamer. The caption always carries a `🎥 Credit: @handle` line. Credit alone isn't permission, though.

## Commands

```bash
python run.py                    # normal scheduled run
```

```bash
python run.py --force            # make a reel now, ignoring the 2-hour interval
```

```bash
python run.py --local my_stream.mp4 --streamer "Mythpat" --skip-search --force   # process a local recording
```

```bash
python run.py --status           # what's in memory.db
```

```bash
python -m unittest discover -s tests -v   # offline end-to-end test (no network/Claude/Whisper needed)
```

## Scheduling

Create a Claude Code scheduled task that runs hourly, with the prompt in `prompt.md`.
Or skip Claude and use plain cron: `0 * * * * cd /path/gta6_reels && python run.py >> work/run.log 2>&1`.
A lock file stops runs from overlapping.

## Notes / limits

* Whisper `small` on CPU transcribes the ~25 short windows in a few minutes; use `medium` on a GPU for better Hindi.
* Instagram doesn't let the API pin comments. The "pinned comment" is posted as the first comment, so pin it in the app.
* A custom cover image needs a public URL (`instagram.cover_base_url`); otherwise the cover is the frame at the reaction peak (`thumb_offset`). The generated thumbnail is still saved for manual use.
* yt-dlp breaks when YouTube changes things, so keep it updated (`pip install -U yt-dlp`).
