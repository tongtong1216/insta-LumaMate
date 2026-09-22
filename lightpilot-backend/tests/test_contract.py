from typing import get_args

from app.bailian_client import SYSTEM_PROMPT
from app.schemas import (BrightRegionType, ExposurePriority, SceneLabel,
                         StabilityPreference, SubjectType)


EXPECTED_SCENES = {
    "indoor_even_light", "indoor_mixed_light", "indoor_low_light", "indoor_backlit",
    "outdoor_daylight", "outdoor_backlit", "night_low_light", "stage_colored_light",
    "high_contrast_other", "other",
}
EXPECTED_SUBJECTS = {
    "person", "group", "display", "document", "object", "landscape", "none", "other",
}
EXPECTED_BRIGHT_REGIONS = {
    "none", "sky", "window", "display", "lamp", "specular_reflection", "mixed", "other",
}


def test_candidate_v1_enums_are_fixed_and_present_in_model_prompt():
    assert set(get_args(SceneLabel)) == EXPECTED_SCENES
    assert set(get_args(SubjectType)) == EXPECTED_SUBJECTS
    assert set(get_args(BrightRegionType)) == EXPECTED_BRIGHT_REGIONS
    for value in EXPECTED_SCENES | EXPECTED_SUBJECTS | EXPECTED_BRIGHT_REGIONS:
        assert value in SYSTEM_PROMPT


def test_rc2_intent_enums_are_fixed():
    assert set(get_args(ExposurePriority)) == {"subject_detail", "highlight_detail", "balanced"}
    assert set(get_args(StabilityPreference)) == {"normal", "high"}


def test_openapi_exposes_fixed_response_enums(mock_app):
    schema = mock_app.get("/openapi.json").json()["components"]["schemas"]["SceneSemantic"]

    def enum_values(node):
        values = set(node.get("enum", [])) if isinstance(node, dict) else set()
        if isinstance(node, dict):
            for value in node.values():
                values.update(enum_values(value))
        elif isinstance(node, list):
            for value in node:
                values.update(enum_values(value))
        return values

    properties = schema["properties"]
    assert enum_values(properties["scene"]) == EXPECTED_SCENES
    assert enum_values(properties["subject_type"]) == EXPECTED_SUBJECTS
    assert enum_values(properties["bright_region_type"]) == EXPECTED_BRIGHT_REGIONS

    request_schema = mock_app.get("/openapi.json").json()["components"]["schemas"]
    intent = request_schema["Intent"]["properties"]
    metrics = request_schema["Metrics"]["properties"]
    assert enum_values(intent["exposure_priority"]) == {
        "subject_detail", "highlight_detail", "balanced"}
    assert enum_values(intent["stability_preference"]) == {"normal", "high"}
    assert "highlight_clipping_ratio" in metrics
    assert "background_brightness" in metrics
    assert "highlight_ratio" not in metrics
