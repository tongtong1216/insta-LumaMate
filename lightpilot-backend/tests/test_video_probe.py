from pathlib import Path
import shutil
import subprocess

import pytest
from PIL import Image

from scripts.video_probe import build_video_summary, extract_frame, sample_timestamps


def result(scene, subject, bright, colored=False):
    return {
        "frame_id": 1, "intent_revision": 1, "status": "ok",
        "scene": scene, "subject_type": subject, "bright_region_type": bright,
        "colored_light": colored, "uncertainty": [], "reason": "测试",
    }


def test_sample_timestamps_are_even_and_bounded():
    assert sample_timestamps(12, 3) == [2, 6, 10]
    assert sample_timestamps(0.2, 6) == [0.1]
    assert len(sample_timestamps(20, 10)) == 10


def test_video_summary_records_enum_transitions():
    first = result("outdoor_daylight", "group", "sky")
    second = result("outdoor_backlit", "group", "mixed")
    frames = [
        {"frame_id": 10, "protocol_valid": True, "status": "ok", "elapsed_ms": 100,
         "result": first},
        {"frame_id": 11, "protocol_valid": True, "status": "ok", "elapsed_ms": 200,
         "result": second},
    ]

    summary = build_video_summary(frames)
    assert summary["all_frames_ok"] is True
    assert summary["successful_frames"] == 2
    assert summary["distinct_values"]["scene"] == ["outdoor_daylight", "outdoor_backlit"]
    assert summary["transitions"] == [{
        "from_frame_id": 10,
        "to_frame_id": 11,
        "changed": {
            "scene": {"from": "outdoor_daylight", "to": "outdoor_backlit"},
            "bright_region_type": {"from": "sky", "to": "mixed"},
        },
    }]


def test_ffmpeg_frame_extraction(tmp_path: Path):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        pytest.skip("ffmpeg is not installed")
    video = tmp_path / "synthetic.mp4"
    subprocess.run([
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=1",
        "-pix_fmt", "yuv420p", "-y", str(video),
    ], check=True, timeout=30)
    frame = tmp_path / "frame.jpg"

    extract_frame(video, 0.5, frame, ffmpeg)

    with Image.open(frame) as image:
        assert image.format == "JPEG"
        assert image.size == (320, 240)
