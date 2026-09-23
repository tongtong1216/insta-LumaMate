"""Run D's real-model acceptance probe against three representative stage images."""

import argparse
import asyncio
import base64
import json
import time
from dataclasses import dataclass
from pathlib import Path

import httpx
from pydantic import ValidationError

from app.config import Settings
from app.schemas import AnalyzeSceneRequest, Intent, SceneSemantic


@dataclass(frozen=True)
class StageCase:
    name: str
    image: Path
    source_text: str
    exposure_priority: str
    stability_preference: str
    frame_base: int


STAGE_REQUIRED_FIELDS = {
    "stage1_exposure": {"scene", "bright_region_type"},
    "stage2_motion_noise": {"scene"},
    "stage3_color_atmosphere": {"scene", "colored_light"},
}


def detail_blocks_stage(detail: dict, required_fields: set[str]) -> bool:
    affects = set(detail.get("affects", []))
    return (detail.get("severity") == "blocking" or "all" in affects
            or bool(affects & required_fields))


def result_is_stage_usable(result: dict, stage_name: str) -> bool:
    required = STAGE_REQUIRED_FIELDS[stage_name]
    if result.get("status") != "ok" or any(result.get(field) is None for field in required):
        return False
    details = result.get("uncertainty_details")
    # An rc2 warning has no fixed field scope, so it retains the safe whole-frame HOLD.
    if details is None:
        return not result.get("uncertainty")
    return not any(detail_blocks_stage(detail, required) for detail in details)


def build_stage_summary(
    records: list[dict],
    stage_name: str = "stage1_exposure",
    expect_colored_light: bool = False,
) -> dict:
    semantic_fields = ("scene", "subject_type", "bright_region_type", "colored_light")
    contract_records = [record for record in records
                        if record.get("contract_passed", record.get("model_call_passed"))]
    usable = [record for record in contract_records
              if record.get("stage_usable", record.get("policy_usable"))]
    distinct_values = {field: [] for field in semantic_fields}
    for record in contract_records:
        result = record["result"]
        for field in semantic_fields:
            value = result[field]
            if value not in distinct_values[field]:
                distinct_values[field].append(value)
    field_stability = {
        field: len(contract_records) == len(records) and len(values) == 1 and values[0] is not None
        for field, values in distinct_values.items()
    }
    required_fields = STAGE_REQUIRED_FIELDS[stage_name]
    required_field_stability = all(field_stability[field] for field in required_fields)
    colored_light_matched = (
        not expect_colored_light or
        (len(contract_records) == len(records) and all(
            record["result"]["colored_light"] is True for record in contract_records
        ))
    )
    elapsed = [record["elapsed_ms"] for record in records]
    return {
        "attempts": len(records),
        "successful_model_calls": len(contract_records),
        "policy_usable_attempts": len(usable),
        "contract_passed": len(contract_records) == len(records),
        "stage_usable": len(usable) == len(records),
        "field_stability": field_stability,
        "required_fields": sorted(required_fields),
        "required_field_stability": required_field_stability,
        # Compatibility aliases for readers of the previous report shape.
        "stable_fields": field_stability,
        "distinct_values": distinct_values,
        "colored_light_expectation_matched": colored_light_matched,
        "stage_passed": (
            len(contract_records) == len(records)
            and len(usable) == len(records)
            and required_field_stability
            and colored_light_matched
        ),
        "elapsed_ms": {
            "min": min(elapsed) if elapsed else None,
            "max": max(elapsed) if elapsed else None,
            "average": round(sum(elapsed) / len(elapsed)) if elapsed else None,
        },
    }


def make_request(case: StageCase, frame_id: int, intent_revision: int) -> AnalyzeSceneRequest:
    if not case.image.is_file():
        raise ValueError(f"{case.name} 图片不存在或不是普通文件")
    return AnalyzeSceneRequest(
        frame_id=frame_id,
        intent_revision=intent_revision,
        intent=Intent(
            exposure_priority=case.exposure_priority,
            stability_preference=case.stability_preference,
            source_text=case.source_text,
        ),
        image_base64=base64.b64encode(case.image.read_bytes()).decode(),
    )


async def run_case(
    http: httpx.AsyncClient,
    base_url: str,
    case: StageCase,
    repeat: int,
    intent_revision: int,
) -> list[dict]:
    records = []
    for index in range(repeat):
        frame_id = case.frame_base + index
        started = time.monotonic()
        record = {
            "stage": case.name,
            "attempt": index + 1,
            "frame_id": frame_id,
            "protocol_valid": False,
            "contract_passed": False,
            "stage_usable": False,
        }
        try:
            request = make_request(case, frame_id, intent_revision)
            response = await http.post(
                f"{base_url.rstrip('/')}/api/v1/analyze-scene",
                json=request.model_dump(),
            )
            record["http_status"] = response.status_code
            response.raise_for_status()
            result = SceneSemantic.model_validate(response.json())
            identifiers_match = (
                result.frame_id == frame_id and result.intent_revision == intent_revision
            )
            contract_passed = result.status == "ok" and identifiers_match
            stage_usable = contract_passed and result_is_stage_usable(
                result.model_dump(), case.name,
            )
            record.update({
                "protocol_valid": True,
                "contract_passed": contract_passed,
                "stage_usable": stage_usable,
                "model_call_passed": contract_passed,
                "policy_usable": stage_usable,
                "status": result.status,
                "result": result.model_dump(),
                "error_code": (
                    "response_id_mismatch" if not identifiers_match
                    else result.uncertainty[0] if result.status == "unavailable" and result.uncertainty
                    else "stage_semantic_unavailable" if not stage_usable
                    else None
                ),
            })
        except httpx.TimeoutException:
            record.update({"status": "client_timeout", "error_code": "client_timeout"})
        except httpx.HTTPStatusError as exc:
            record.update({"status": "http_error",
                           "error_code": f"http_{exc.response.status_code}"})
        except (OSError, ValueError, ValidationError, httpx.HTTPError) as exc:
            record.update({"status": "probe_error", "error_code": type(exc).__name__})
        record["elapsed_ms"] = round((time.monotonic() - started) * 1000)
        records.append(record)
        print(json.dumps(record, ensure_ascii=False), flush=True)
    return records


async def run(args) -> int:
    settings = Settings.from_env()
    timeout = settings.timeout_seconds + 5
    async with httpx.AsyncClient(timeout=timeout) as http:
        try:
            health_response = await http.get(f"{args.base_url.rstrip('/')}/health")
            health_response.raise_for_status()
            health = health_response.json()
        except (httpx.HTTPError, ValueError, KeyError):
            print("无法连接后端或 /health 响应无效。")
            return 2
        if health.get("mode") != "bailian" or not health.get("model_configured"):
            print("后端不是已配置的百炼真实模式，拒绝把 Mock 当作三阶段验收结果。")
            return 2

        cases = [
            StageCase(
                "stage1_exposure", args.stage1_image,
                "优先拍清楚主体，同时尽量保留背景高光",
                "balanced", "normal", 100,
            ),
            StageCase(
                "stage2_motion_noise", args.stage2_image,
                "优先冻结运动，宁愿有少量噪点也不要明显拖影",
                "subject_detail", "normal", 200,
            ),
            StageCase(
                "stage3_color_atmosphere", args.stage3_image,
                "保留现场彩色灯光和整体色彩氛围",
                "balanced", "high", 300,
            ),
        ]
        reports = {}
        for case in cases:
            records = await run_case(
                http, args.base_url, case, args.repeat, args.intent_revision,
            )
            reports[case.name] = {
                "image_file": case.image.name,
                "intent": {
                    "exposure_priority": case.exposure_priority,
                    "stability_preference": case.stability_preference,
                    "source_text": case.source_text,
                },
                "records": records,
                "summary": build_stage_summary(
                    records,
                    stage_name=case.name,
                    expect_colored_light=(
                        case.name == "stage3_color_atmosphere"
                        and args.expect_stage3_colored_light
                    ),
                ),
            }

    overall_passed = all(stage["summary"]["stage_passed"] for stage in reports.values())
    report = {
        "contract_version": "1.0.0-rc3",
        "backend": {
            "mode": health.get("mode"),
            "model": health.get("model"),
            "model_configured": health.get("model_configured"),
        },
        "d_scope": [
            "real Bailian image call",
            "SceneSemantic schema and identifiers",
            "contract validity, field-scoped stage usability and field stability",
            "colored_light classification",
        ],
        "not_d_scope": [
            "motion score calculation",
            "EV, shutter, ISO or white-balance target selection",
            "temporal confirmation and camera execution",
        ],
        "stages": reports,
        "overall_passed": overall_passed,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({
        "report": str(args.output.resolve()),
        "overall_passed": overall_passed,
        "stage_summaries": {
            name: stage["summary"] for name, stage in reports.items()
        },
    }, ensure_ascii=False))
    return 0 if overall_passed else 1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage1-image", type=Path, required=True,
                        help="Backlit or mixed-light exposure sample")
    parser.add_argument("--stage2-image", type=Path, required=True,
                        help="Moving subject or low-light action sample")
    parser.add_argument("--stage3-image", type=Path, required=True,
                        help="Colored-light or skin-tone sample")
    parser.add_argument("--repeat", type=int, default=3, choices=range(1, 6))
    parser.add_argument("--intent-revision", type=int, default=1)
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--expect-stage3-colored-light", action="store_true",
                        help="Fail Stage 3 unless every result has colored_light=true")
    parser.add_argument("--output", type=Path,
                        default=Path("test-results/d-three-stage-report.json"))
    args = parser.parse_args()
    if args.intent_revision < 0:
        parser.error("--intent-revision must be nonnegative")
    raise SystemExit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
