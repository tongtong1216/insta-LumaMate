"""Run with python -m scripts.smoke. Optionally prints validated scene semantics."""

import argparse
import asyncio
import base64
import io
import json
import time
from pathlib import Path
from uuid import uuid4

import httpx
from PIL import Image
from openai import APIConnectionError, APIStatusError, APITimeoutError

from app.bailian_client import BailianClient
from app.config import Settings
from app.schemas import (MAX_IMAGE_BYTES, AnalyzeSceneRequest, Intent, ParseIntentResponse,
                         SceneSemantic)
from app.service import FAILURE_REASONS, SceneService


def error_code(exc: Exception) -> str:
    if isinstance(exc, (TimeoutError, APITimeoutError, httpx.TimeoutException)):
        return "timeout"
    if isinstance(exc, (APIConnectionError, httpx.NetworkError)):
        return "connection_failed"
    if isinstance(exc, (APIStatusError, httpx.HTTPStatusError)):
        status = exc.status_code if isinstance(exc, APIStatusError) else exc.response.status_code
        return {401: "authentication_failed", 403: "authentication_failed",
                429: "rate_limited", 404: "model_or_endpoint_not_found"}.get(status, "http_error")
    return "check_failed"


def make_request(image_path: Path | None, intent: str, exposure_priority: str,
                 stability_preference: str) -> AnalyzeSceneRequest:
    if image_path:
        if image_path.stat().st_size > MAX_IMAGE_BYTES:
            raise ValueError("Image must be at most 4 MiB")
        raw = image_path.read_bytes()
    else:
        buffer = io.BytesIO()
        Image.new("RGB", (32, 32), (80, 100, 120)).save(buffer, format="PNG")
        raw = buffer.getvalue()
    return AnalyzeSceneRequest(frame_id=1, intent_revision=1, intent=Intent(
        exposure_priority=exposure_priority,
        stability_preference=stability_preference,
        source_text=intent or None,
    ),
                               image_base64=base64.b64encode(raw).decode())


def build_summary(results: list[SceneSemantic], elapsed_values: list[int]) -> dict:
    successful = sum(result.status == "ok" for result in results)
    stable_fields = {}
    distinct_values = {}
    for field in ("scene", "subject_type", "bright_region_type", "colored_light"):
        values = []
        for result in results:
            value = getattr(result, field)
            if value not in values:
                values.append(value)
        stable_fields[field] = (successful == len(results) and len(values) == 1
                                and values[0] is not None)
        distinct_values[field] = values
    return {
        "attempts": len(results),
        "successful": successful,
        "integration_passed": successful == len(results) and all(stable_fields.values()),
        "stable_fields": stable_fields,
        "distinct_values": distinct_values,
        "elapsed_ms": {
            "min": min(elapsed_values),
            "max": max(elapsed_values),
            "average": round(sum(elapsed_values) / len(elapsed_values)),
        },
    }


async def run(args) -> int:
    settings = Settings.from_env()
    if args.kind in ("text", "vision") and not settings.model_configured:
        print("请先在后端 .env 或环境变量中填写 BAILIAN_API_KEY 与 BAILIAN_BASE_URL。")
        return 1
    if args.kind == "vision" and not args.image:
        print("真实图片冒烟需要 --image /path/to/your/photo.jpg。")
        return 1
    req = make_request(
        args.image,
        args.intent,
        getattr(args, "exposure_priority", "balanced"),
        getattr(args, "stability_preference", "normal"),
    ) if args.kind in ("backend", "vision") else None
    model = BailianClient(settings) if args.kind in ("text", "vision") else None
    passed = True
    observed_results = []
    observed_elapsed_ms = []
    try:
        async with httpx.AsyncClient(timeout=settings.timeout_seconds + 5) as http:
            if args.kind in ("backend", "intent"):
                health = await http.get(f"{args.base_url.rstrip('/')}/health")
                health.raise_for_status()
                info = health.json()
                print(json.dumps({"health": info["status"], "mode": info["mode"],
                                  "model": info.get("model"),
                                  "model_configured": info.get("model_configured", False)}))
                if (args.require_live or args.kind == "intent") and info["mode"] != "bailian":
                    print(json.dumps({"passed": False, "error_code": "backend_is_mock"}))
                    return 1
                if (args.require_live or args.kind == "intent") and not info.get("model_configured", False):
                    print(json.dumps({"passed": False, "error_code": "model_not_configured"}))
                    return 1
                if args.kind == "backend" and info["mode"] == "bailian" and not args.image:
                    print("真实模型模式需要 --image；内置纯色测试帧仅用于 Mock 协议检查。")
                    return 1
            for index in range(args.repeat):
                started = time.monotonic()
                failure = None
                result = None
                http_status = None
                if args.kind == "text":
                    async with asyncio.timeout(settings.timeout_seconds):
                        response = await model.client.chat.completions.create(
                            model=settings.model_id, messages=[{"role": "user", "content": "只回复 LIGHTPILOT_OK"}],
                            max_tokens=32, extra_body={"enable_thinking": False},
                        )
                    ok = bool(response.choices and response.choices[0].message.content
                              and response.choices[0].finish_reason == "stop"
                              and not response.choices[0].message.refusal
                              and response.choices[0].message.content.strip() == "LIGHTPILOT_OK")
                    status = "ok" if ok else "unexpected_text"
                elif args.kind == "intent":
                    request_id = str(uuid4())
                    response = await http.post(
                        f"{args.base_url.rstrip('/')}/api/v1/parse-intent",
                        json={"request_id": request_id, "source_text": args.intent},
                    )
                    http_status = response.status_code
                    response.raise_for_status()
                    parsed = ParseIntentResponse.model_validate(response.json())
                    result = parsed
                    status = parsed.status
                    all_active = bool(parsed.intent and all(weight >= 0.5 for weight in (
                        parsed.intent.weights.exposure,
                        parsed.intent.weights.motion_noise,
                        parsed.intent.weights.color_atmosphere,
                    )))
                    ok = (status == "ok" and parsed.request_id == request_id
                          and (not args.expect_all_stages or all_active))
                    if not ok:
                        failure = "expected_three_active_stages" if args.expect_all_stages else "invalid_intent"
                else:
                    req.frame_id = index + 1
                    if args.kind == "backend":
                        response = await http.post(f"{args.base_url.rstrip('/')}/api/v1/analyze-scene",
                                                   json=req.model_dump())
                        http_status = response.status_code
                        response.raise_for_status()
                        result = SceneSemantic.model_validate(response.json())
                        expected = "mock" if info["mode"] == "mock" else "ok"
                    else:
                        live_settings = settings.model_copy(update={"mode": "bailian"})
                        result = await SceneService(live_settings, model).analyze(req)
                        expected = "ok"
                    status = result.status
                    ok = (status == expected and result.frame_id == req.frame_id
                          and result.intent_revision == req.intent_revision)
                    if status == "unavailable":
                        failure = next((code for code in result.uncertainty if code in FAILURE_REASONS),
                                       "unknown_failure")
                    elif result.frame_id != req.frame_id or result.intent_revision != req.intent_revision:
                        failure = "response_id_mismatch"
                passed = passed and ok
                elapsed_ms = round((time.monotonic() - started) * 1000)
                record = {"attempt": index + 1, "status": status, "passed": ok,
                          "error_code": failure, "elapsed_ms": elapsed_ms}
                if http_status is not None:
                    record["http_status"] = http_status
                if getattr(args, "show_result", False) and result is not None:
                    record["result"] = result.model_dump()
                    if isinstance(result, SceneSemantic):
                        observed_results.append(result)
                        observed_elapsed_ms.append(elapsed_ms)
                print(json.dumps(record, ensure_ascii=False))
            if getattr(args, "show_result", False) and observed_results:
                print(json.dumps({"summary": build_summary(
                    observed_results, observed_elapsed_ms)}, ensure_ascii=False))
    except Exception as exc:
        # Class names only: SDK exception messages may contain provider payloads.
        print(json.dumps({"passed": False, "error_type": type(exc).__name__,
                          "error_code": error_code(exc)}))
        return 1
    finally:
        if model:
            await model.close()
    return 0 if passed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=["backend", "text", "vision", "intent"], default="backend")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--image", type=Path)
    parser.add_argument("--intent", default="保留现场光照氛围")
    parser.add_argument("--exposure-priority",
                        choices=["subject_detail", "highlight_detail", "balanced"],
                        default="balanced")
    parser.add_argument("--stability-preference", choices=["normal", "high"], default="normal")
    parser.add_argument("--repeat", type=int, default=3, choices=range(1, 11))
    parser.add_argument("--require-live", action="store_true",
                        help="Backend mode must be bailian; never accept Mock as an integration pass")
    parser.add_argument("--show-result", action="store_true",
                        help="Print validated semantic fields and a stability summary; never print the image or key")
    parser.add_argument("--expect-all-stages", action="store_true",
                        help="For --kind intent, require all three weights to be >= 0.50")
    args = parser.parse_args()
    try:
        code = asyncio.run(run(args))
    except (OSError, ValueError):
        print("配置或图片无效，请检查 .env、图片格式、大小和路径。")
        code = 1
    raise SystemExit(code)


if __name__ == "__main__":
    main()
