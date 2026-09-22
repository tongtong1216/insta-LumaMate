"""Sample a local video and evaluate the existing per-frame scene API."""

import argparse
import asyncio
import base64
import json
import math
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import httpx
from pydantic import ValidationError

from app.config import Settings
from app.schemas import AnalyzeSceneRequest, SceneSemantic


def video_duration(path: Path, ffprobe: str) -> float:
    command = [
        ffprobe, "-v", "error", "-show_entries", "format=duration",
        "-of", "json", str(path),
    ]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=20, check=True)
        duration = float(json.loads(completed.stdout)["format"]["duration"])
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, KeyError, ValueError,
            json.JSONDecodeError) as exc:
        raise ValueError("无法读取视频时长或视频格式不受支持") from exc
    if not math.isfinite(duration) or duration <= 0:
        raise ValueError("视频时长无效")
    return duration


def sample_timestamps(duration: float, requested_samples: int) -> list[float]:
    # At most two samples per second prevents near-duplicate frames in very short clips.
    count = min(requested_samples, max(1, math.ceil(duration * 2)))
    return [duration * (index + 0.5) / count for index in range(count)]


def extract_frame(video: Path, timestamp: float, output: Path, ffmpeg: str) -> None:
    command = [
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
        "-ss", f"{timestamp:.3f}", "-i", str(video), "-map", "0:v:0",
        "-frames:v", "1", "-vf",
        "scale=w='if(gt(iw,1280),1280,iw)':h='if(gt(ih,1280),1280,ih)'"
        ":force_original_aspect_ratio=decrease:force_divisible_by=2",
        "-q:v", "3", "-map_metadata", "-1", "-y", str(output),
    ]
    try:
        subprocess.run(command, capture_output=True, timeout=30, check=True)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise ValueError(f"无法抽取 {timestamp:.3f} 秒处的视频帧") from exc


def build_video_summary(frames: list[dict]) -> dict:
    valid = [frame for frame in frames if frame.get("protocol_valid")]
    successful = [frame for frame in valid if frame.get("status") == "ok"]
    fields = ("scene", "subject_type", "bright_region_type", "colored_light")
    distinct_values = {field: [] for field in fields}
    transitions = []
    previous = None
    for frame in successful:
        result = frame["result"]
        for field in fields:
            value = result[field]
            if value not in distinct_values[field]:
                distinct_values[field].append(value)
        if previous is not None:
            changed = {
                field: {"from": previous["result"][field], "to": result[field]}
                for field in fields if previous["result"][field] != result[field]
            }
            if changed:
                transitions.append({
                    "from_frame_id": previous["frame_id"],
                    "to_frame_id": frame["frame_id"],
                    "changed": changed,
                })
        previous = frame
    elapsed = [frame["elapsed_ms"] for frame in frames]
    return {
        "sampled_frames": len(frames),
        "protocol_valid_frames": len(valid),
        "successful_frames": len(successful),
        "all_frames_ok": len(successful) == len(frames),
        "distinct_values": distinct_values,
        "transitions": transitions,
        "elapsed_ms": {
            "min": min(elapsed) if elapsed else None,
            "max": max(elapsed) if elapsed else None,
            "average": round(sum(elapsed) / len(elapsed)) if elapsed else None,
        },
    }


async def run(args) -> int:
    if not args.video.is_file():
        print("视频文件不存在或不是普通文件。")
        return 2
    ffmpeg = shutil.which("ffmpeg")
    ffprobe = shutil.which("ffprobe")
    if not ffmpeg or not ffprobe:
        print("缺少 ffmpeg/ffprobe，请先安装 FFmpeg。")
        return 2
    try:
        duration = video_duration(args.video, ffprobe)
        timestamps = sample_timestamps(duration, args.samples)
    except ValueError as exc:
        print(str(exc))
        return 2

    settings = Settings.from_env()
    frames = []
    async with httpx.AsyncClient(timeout=settings.timeout_seconds + 5) as http:
        try:
            health_response = await http.get(f"{args.base_url.rstrip('/')}/health")
            health_response.raise_for_status()
            health = health_response.json()
        except (httpx.HTTPError, ValueError, KeyError):
            print("无法连接后端或 /health 响应无效。")
            return 2
        if health.get("mode") != "bailian" or not health.get("model_configured"):
            print("后端不是已配置的百炼真实模式，拒绝把 Mock 当作视频验收结果。")
            return 2

        with tempfile.TemporaryDirectory(prefix="lightpilot-video-") as temp_dir:
            for index, timestamp in enumerate(timestamps):
                frame_id = args.frame_start + index
                frame_path = Path(temp_dir) / f"frame-{frame_id}.jpg"
                started = time.monotonic()
                record = {
                    "frame_id": frame_id,
                    "timestamp_ms": round(timestamp * 1000),
                    "protocol_valid": False,
                }
                try:
                    extract_frame(args.video, timestamp, frame_path, ffmpeg)
                    request = AnalyzeSceneRequest(
                        frame_id=frame_id,
                        intent_revision=args.intent_revision,
                        intent=args.intent,
                        image_base64=base64.b64encode(frame_path.read_bytes()).decode(),
                    )
                    response = await http.post(
                        f"{args.base_url.rstrip('/')}/api/v1/analyze-scene",
                        json=request.model_dump(),
                    )
                    record["http_status"] = response.status_code
                    response.raise_for_status()
                    result = SceneSemantic.model_validate(response.json())
                    record.update({
                        "protocol_valid": True,
                        "status": result.status,
                        "error_code": (result.uncertainty[0]
                                       if result.status == "unavailable" and result.uncertainty else None),
                        "result": result.model_dump(),
                    })
                except httpx.TimeoutException:
                    record.update({"status": "client_timeout", "error_code": "client_timeout"})
                except httpx.HTTPStatusError as exc:
                    record.update({"status": "http_error",
                                   "error_code": f"http_{exc.response.status_code}"})
                except (OSError, ValueError, ValidationError, httpx.HTTPError) as exc:
                    record.update({"status": "probe_error", "error_code": type(exc).__name__})
                record["elapsed_ms"] = round((time.monotonic() - started) * 1000)
                frames.append(record)
                print(json.dumps(record, ensure_ascii=False), flush=True)

    report = {
        "contract_version": "1.0.0-rc1",
        "video_file": args.video.name,
        "duration_ms": round(duration * 1000),
        "intent_revision": args.intent_revision,
        "intent": args.intent,
        "backend": {
            "mode": health.get("mode"),
            "model": health.get("model"),
            "model_configured": health.get("model_configured"),
        },
        "frames": frames,
        "summary": build_video_summary(frames),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"report": str(args.output.resolve()),
                      "summary": report["summary"]}, ensure_ascii=False))
    return 0 if report["summary"]["all_frames_ok"] else 1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=6, choices=range(1, 11),
                        help="Evenly sample 1-10 representative frames (default: 6)")
    parser.add_argument("--intent", default="保持主体清晰，同时保留现场光照氛围")
    parser.add_argument("--intent-revision", type=int, default=1)
    parser.add_argument("--frame-start", type=int, default=1)
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--output", type=Path,
                        default=Path("test-results/video-probe-report.json"))
    args = parser.parse_args()
    if args.intent_revision < 0 or args.frame_start < 0:
        parser.error("--intent-revision and --frame-start must be nonnegative")
    raise SystemExit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
