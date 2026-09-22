import asyncio
import base64
import io
import json

import httpx
import pytest
from PIL import Image

from app.config import Settings
from app.schemas import MAX_BODY_BYTES, AnalyzeSceneRequest, ModelSemantic
from app.service import SceneService
from conftest import completion

PATH = "/api/v1/analyze-scene"


def test_mock_preserves_ids_and_never_claims_real_analysis(mock_app, payload):
    response = mock_app.post(PATH, json=payload)
    assert response.status_code == 200
    data = response.json()
    assert (data["frame_id"], data["intent_revision"]) == (42, 7)
    assert data["status"] == "mock"
    assert data["uncertainty"] == ["model_not_connected"]
    assert data["colored_light"] is None
    assert not {"ev", "action", "sdk_method"} & data.keys()


def test_live_adapter_and_server_owned_ids(live_app, payload, semantic):
    requests = []

    def upstream(request):
        requests.append(request)
        return httpx.Response(200, json=completion(json.dumps(semantic)))

    client = live_app(upstream)
    data = client.post(PATH, json=payload).json()
    assert data == dict(semantic, frame_id=42, intent_revision=7, status="ok")
    assert len(requests) == 1
    sent = json.loads(requests[0].content)
    assert requests[0].url.path == "/v1/chat/completions"
    assert sent["response_format"] == {"type": "json_object"}
    assert sent["enable_thinking"] is False
    assert sent["messages"][1]["content"][0]["image_url"]["url"].startswith("data:image/png;base64,")
    assert "frame_id" not in json.loads(sent["messages"][1]["content"][1]["text"])


@pytest.mark.parametrize("status,code", [
    (401, "authentication_failed"), (403, "authentication_failed"),
    (429, "rate_limited"), (404, "model_unavailable"),
    (400, "model_unavailable"), (500, "model_unavailable"),
])
def test_provider_errors_are_redacted_and_not_retried(live_app, payload, caplog, status, code):
    calls = []

    def upstream(request):
        calls.append(request)
        return httpx.Response(status, json={"error": {"message": "private-provider-response test-only-secret"}})

    response = live_app(upstream).post(PATH, json=payload)
    assert response.status_code == 200
    assert response.json()["status"] == "unavailable"
    assert response.json()["uncertainty"] == [code]
    assert response.json()["scene"] is None
    assert response.json()["frame_id"] == payload["frame_id"]
    assert len(calls) == 1
    assert "private-provider-response" not in response.text + caplog.text
    assert "test-only-secret" not in response.text + caplog.text


@pytest.mark.parametrize("content", [
    "not JSON", "{}", "```json\n{}\n```", "[]",
    '{"scene":"night","subject_type":null,"bright_region_type":null,"colored_light":"false","uncertainty":[],"reason":null}',
])
def test_invalid_model_json_fails_closed(live_app, payload, content):
    client = live_app(lambda request: httpx.Response(200, json=completion(content)))
    data = client.post(PATH, json=payload).json()
    assert data["status"] == "unavailable"
    assert data["uncertainty"] == ["invalid_model_response"]


def test_model_cannot_invent_semantic_enum_values(live_app, payload, semantic):
    invented = dict(semantic, scene="户外草地", subject_type="人物群像")
    client = live_app(lambda request: httpx.Response(200, json=completion(json.dumps(invented))))

    data = client.post(PATH, json=payload).json()
    assert data["status"] == "unavailable"
    assert data["uncertainty"] == ["invalid_model_response"]


@pytest.mark.parametrize("extra", [{"frame_id": 99}, {"ev": 2}, {"status": "ok"}, {"sdk_method": "setEV"}])
def test_model_cannot_inject_ids_or_actions(live_app, payload, semantic, extra):
    result = json.dumps(dict(semantic, **extra))
    client = live_app(lambda request: httpx.Response(200, json=completion(result)))
    data = client.post(PATH, json=payload).json()
    assert data["status"] == "unavailable"
    assert data["frame_id"] == 42


@pytest.mark.parametrize("finish", ["length", "content_filter"])
def test_incomplete_or_filtered_response(live_app, payload, semantic, finish):
    client = live_app(lambda request: httpx.Response(200, json=completion(json.dumps(semantic), finish)))
    assert client.post(PATH, json=payload).json()["uncertainty"] == ["invalid_model_response"]


@pytest.mark.parametrize("error,code", [(httpx.ReadTimeout, "timeout"), (httpx.ConnectError, "connection_failed")])
def test_network_failure(live_app, payload, error, code):
    def upstream(request):
        raise error("private upstream error", request=request)

    data = live_app(upstream).post(PATH, json=payload).json()
    assert data["status"] == "unavailable"
    assert data["uncertainty"] == [code]


def test_wall_clock_timeout(live_app, payload, semantic):
    async def upstream(request):
        await asyncio.sleep(0.15)
        return httpx.Response(200, json=completion(json.dumps(semantic)))

    client = live_app(upstream, timeout_seconds=0.02)
    assert client.post(PATH, json=payload).json()["uncertainty"] == ["timeout"]


@pytest.mark.parametrize("changes", [
    {"frame_id": -1}, {"frame_id": True}, {"frame_id": "42"},
    {"intent_revision": -1}, {"intent": "旧版自由文本"},
    {"intent": {"exposure_priority": "portrait", "stability_preference": "normal"}},
    {"intent": {"exposure_priority": "balanced", "stability_preference": "fast"}},
    {"intent": {"exposure_priority": "balanced", "stability_preference": "normal",
                "source_text": "x" * 1001}},
    {"image_base64": "not base64"}, {"image_base64": base64.b64encode(b"not an image").decode()},
    {"metrics": {"dark_ratio": 1.1}}, {"metrics": {"subject_brightness": -0.1}},
    {"metrics": {"highlight_clipping_ratio": "0.2"}},
    {"metrics": {"highlight_ratio": 0.2}}, {"extra": "unsupported"},
])
def test_invalid_requests_do_not_echo_private_data(mock_app, payload, changes):
    data = dict(payload, **changes)
    response = mock_app.post(PATH, json=data)
    assert response.status_code == 422
    assert payload["image_base64"] not in response.text
    assert payload["intent"]["source_text"] not in response.text
    assert all("input" not in error for error in response.json()["detail"])


def test_image_data_url_and_mime_mismatch(mock_app, payload):
    payload["image_base64"] = "data:image/png;base64," + payload["image_base64"]
    assert mock_app.post(PATH, json=payload).status_code == 200
    payload["image_base64"] = payload["image_base64"].replace("image/png", "image/jpeg")
    assert mock_app.post(PATH, json=payload).status_code == 422


def test_large_image_is_normalized_for_model_without_changing_request_contract():
    source = io.BytesIO()
    Image.new("RGB", (1600, 800), (90, 120, 150)).save(source, format="PNG")
    request = AnalyzeSceneRequest(frame_id=1, intent_revision=1, intent={
                                      "exposure_priority": "balanced",
                                      "stability_preference": "normal",
                                      "source_text": "测试",
                                  },
                                  image_base64=base64.b64encode(source.getvalue()).decode())

    assert request.image_data_url.startswith("data:image/jpeg;base64,")
    normalized = base64.b64decode(request.image_data_url.partition(",")[2])
    with Image.open(io.BytesIO(normalized)) as image:
        assert image.size == (1280, 640)
        assert image.mode == "RGB"


def test_limits_and_invalid_json(mock_app):
    assert mock_app.post(PATH, content="{}", headers={"Content-Type": "text/plain"}).status_code == 415
    headers = {"Content-Type": "application/json"}
    assert mock_app.post(PATH, content="{invalid", headers=headers).status_code == 422
    assert mock_app.post(PATH, content=b"x" * (MAX_BODY_BYTES + 1), headers=headers).status_code == 413
    chunks = (b"x" * (1024 * 1024) for _ in range(7))
    assert mock_app.post(PATH, content=chunks, headers=headers).status_code == 413


def test_busy_requests_and_capacity_recovery(payload, semantic):
    class SlowClient:
        async def analyze(self, req):
            await asyncio.sleep(0.12)
            return ModelSemantic(**semantic)

    async def run():
        service = SceneService(Settings(mode="bailian", max_concurrent=1), SlowClient())
        req = AnalyzeSceneRequest(**payload)
        first, second = await asyncio.gather(service.analyze(req), service.analyze(req))
        assert first.status == "ok"
        assert second.uncertainty == ["backend_busy"]
        assert (await service.analyze(req)).status == "ok"

    asyncio.run(run())


def test_unexpected_failure_does_not_leak_or_lose_capacity(payload, caplog):
    class BrokenClient:
        async def analyze(self, req):
            raise RuntimeError("secret should never reach a log")

    async def run():
        service = SceneService(Settings(mode="bailian", max_concurrent=1), BrokenClient())
        for _ in range(2):
            result = await service.analyze(AnalyzeSceneRequest(**payload))
            assert result.status == "unavailable"
            assert result.uncertainty == ["internal_error"]

    asyncio.run(run())
    assert "secret should never" not in caplog.text
