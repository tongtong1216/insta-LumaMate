from scripts.three_stage_probe import build_stage_summary, result_is_stage_usable


def record(frame_id: int, *, uncertainty=None, colored_light=False):
    return {
        "frame_id": frame_id,
        "elapsed_ms": 100,
        "model_call_passed": True,
        "policy_usable": not uncertainty,
        "result": {
            "scene": "indoor_mixed_light",
            "subject_type": "person",
            "bright_region_type": "lamp",
            "colored_light": colored_light,
            "uncertainty": uncertainty or [],
        },
    }


def test_three_stable_usable_results_pass():
    summary = build_stage_summary([record(1), record(2), record(3)])

    assert summary["stage_passed"] is True
    assert summary["contract_passed"] is True
    assert summary["stage_usable"] is True
    assert summary["successful_model_calls"] == 3
    assert summary["policy_usable_attempts"] == 3
    assert all(summary["stable_fields"].values())


def test_uncertainty_and_colored_light_expectation_fail():
    uncertain = [record(1), record(2, uncertainty=["subject_occluded"]), record(3)]
    assert build_stage_summary(uncertain)["stage_passed"] is False

    no_colored_light = [record(1), record(2), record(3)]
    summary = build_stage_summary(no_colored_light, expect_colored_light=True)
    assert summary["colored_light_expectation_matched"] is False
    assert summary["stage_passed"] is False


def test_semantic_instability_fails():
    records = [record(1), record(2), record(3)]
    records[2]["result"]["scene"] = "outdoor_daylight"

    summary = build_stage_summary(records)
    assert summary["stable_fields"]["scene"] is False
    assert summary["stage_passed"] is False


def test_stage2_ignores_unrelated_colored_light_instability_but_records_it():
    records = [record(1, colored_light=True), record(2, colored_light=False),
               record(3, colored_light=True)]
    summary = build_stage_summary(records, stage_name="stage2_motion_noise")

    assert summary["field_stability"]["colored_light"] is False
    assert summary["required_field_stability"] is True
    assert summary["stage_passed"] is True


def test_field_scoped_uncertainty_only_blocks_dependent_stage():
    result = {
        "status": "ok",
        "scene": "outdoor_daylight",
        "subject_type": "object",
        "bright_region_type": "none",
        "colored_light": False,
        "uncertainty": ["主体被遮挡"],
        "uncertainty_details": [{
            "code": "subject_occluded",
            "severity": "warning",
            "affects": ["subject_type", "subject_roi"],
            "message": "主体被遮挡",
        }],
    }
    assert result_is_stage_usable(result, "stage2_motion_noise") is True

    result["uncertainty_details"][0]["affects"] = ["colored_light"]
    assert result_is_stage_usable(result, "stage3_color_atmosphere") is False
