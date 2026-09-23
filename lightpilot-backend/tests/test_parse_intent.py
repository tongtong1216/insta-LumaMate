import json
from uuid import uuid4

import httpx
import pytest

from conftest import completion


PATH = "/api/v1/parse-intent"


@pytest.fixture
def request_payload():
    return {
        "request_id": str(uuid4()),
        "source_text": "夜间跑步时拍清楚人物，同时保留霓虹灯颜色",
    }


@pytest.fixture
def parsed_intent():
    return {
        "intent": {
            "weights": {
                "exposure": 0.8,
                "motion_noise": 0.95,
                "color_atmosphere": 0.85,
            },
            "exposure_priority": "subject_detail",
            "motion_priority": "motion_clarity",
            "color_priority": "colored_light_preservation",
            "stability_preference": "high",
        },
        "ambiguities": [],
        "reason": "用户同时强调人物清晰、运动冻结和霓虹氛围",
    }


def test_parse_intent_echoes_server_owned_request_id(live_app, request_payload, parsed_intent):
    requests = []

    def upstream(request):
        requests.append(request)
        return httpx.Response(200, json=completion(json.dumps(parsed_intent)))

    data = live_app(upstream).post(PATH, json=request_payload).json()
    assert data == dict(parsed_intent, request_id=request_payload["request_id"], status="ok")
    assert len(requests) == 1
    sent = json.loads(requests[0].content)
    assert sent["response_format"] == {"type": "json_object"}
    assert sent["enable_thinking"] is False
    assert request_payload["request_id"] not in requests[0].content.decode()
    assert request_payload["source_text"] in requests[0].content.decode()


def test_three_weights_are_independent(live_app, request_payload, parsed_intent):
    result = json.loads(json.dumps(parsed_intent))
    result["intent"]["weights"] = {
        "exposure": 1.0, "motion_noise": 1.0, "color_atmosphere": 1.0,
    }
    response = live_app(lambda request: httpx.Response(
        200, json=completion(json.dumps(result))
    )).post(PATH, json=request_payload)
    assert response.json()["status"] == "ok"


def test_integer_weight_boundaries_are_valid_json_numbers(
        live_app, request_payload, parsed_intent):
    result = json.loads(json.dumps(parsed_intent))
    result["intent"]["weights"] = {
        "exposure": 0, "motion_noise": 1, "color_atmosphere": 0.5,
    }
    response = live_app(lambda request: httpx.Response(
        200, json=completion(json.dumps(result))
    )).post(PATH, json=request_payload)
    assert response.json()["status"] == "ok"


@pytest.mark.parametrize("mutation", [
    lambda value: value["intent"]["weights"].update(exposure=-0.01),
    lambda value: value["intent"]["weights"].update(exposure=1.01),
    lambda value: value["intent"]["weights"].update(exposure=True),
    lambda value: value["intent"]["weights"].update(exposure="0.8"),
    lambda value: value["intent"]["weights"].pop("exposure"),
    lambda value: value["intent"]["weights"].update(extra=0.5),
    lambda value: value["intent"].update(exposure_priority="portrait"),
    lambda value: value.update(ev=1.0),
    lambda value: value.update(request_id="model-must-not-own-this"),
])
def test_invalid_or_injected_model_intent_fails_closed(
        live_app, request_payload, parsed_intent, mutation):
    result = json.loads(json.dumps(parsed_intent))
    mutation(result)
    data = live_app(lambda request: httpx.Response(
        200, json=completion(json.dumps(result))
    )).post(PATH, json=request_payload).json()
    assert data["status"] == "unavailable"
    assert data["intent"] is None
    assert data["request_id"] == request_payload["request_id"]


def test_active_stage_may_be_ambiguous_only_with_manual_choice_message(
        live_app, request_payload, parsed_intent):
    result = json.loads(json.dumps(parsed_intent))
    result["intent"]["motion_priority"] = None
    result["ambiguities"] = ["请手动选择运动清晰或低噪优先"]
    data = live_app(lambda request: httpx.Response(
        200, json=completion(json.dumps(result))
    )).post(PATH, json=request_payload).json()
    assert data["status"] == "ok"
    assert data["intent"]["motion_priority"] is None
    assert data["ambiguities"]

    result["ambiguities"] = []
    data = live_app(lambda request: httpx.Response(
        200, json=completion(json.dumps(result))
    )).post(PATH, json=request_payload).json()
    assert data["status"] == "unavailable"


@pytest.mark.parametrize("changes", [
    {"request_id": "not-a-uuid"}, {"request_id": 1},
    {"source_text": ""}, {"source_text": "x" * 1001}, {"extra": "x"},
])
def test_invalid_parse_requests_return_422(mock_app, request_payload, changes):
    response = mock_app.post(PATH, json=dict(request_payload, **changes))
    assert response.status_code == 422
    assert request_payload["source_text"] not in response.text


def test_mock_parse_requires_manual_selection(mock_app, request_payload):
    data = mock_app.post(PATH, json=request_payload).json()
    assert data["status"] == "mock"
    assert data["intent"] is None
    assert data["ambiguities"]
