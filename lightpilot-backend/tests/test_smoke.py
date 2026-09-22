import argparse
import asyncio
import base64
import json

import httpx
import pytest

from app.config import Settings
from app.schemas import SceneSemantic
from scripts import smoke


@pytest.mark.parametrize("mode,configured,code", [
    ("mock", False, "backend_is_mock"),
    ("bailian", False, "model_not_configured"),
])
def test_require_live_rejects_false_positive(monkeypatch, capsys, mode, configured, code):
    real_client = httpx.AsyncClient
    requests = []

    def server(request):
        requests.append(request.url.path)
        return httpx.Response(200, json={"status": "ok", "mode": mode, "model_configured": configured})

    monkeypatch.setattr(smoke.Settings, "from_env", lambda: Settings())
    monkeypatch.setattr(smoke.httpx, "AsyncClient", lambda **kwargs: real_client(
        transport=httpx.MockTransport(server), **kwargs))
    args = argparse.Namespace(kind="backend", image=None, intent="测试", repeat=3,
                              base_url="http://backend.test", require_live=True)
    assert asyncio.run(smoke.run(args)) == 1
    assert requests == ["/health"]
    assert code in capsys.readouterr().out


@pytest.mark.parametrize("status,code", [(401, "authentication_failed"), (429, "rate_limited"),
                                         (404, "model_or_endpoint_not_found")])
def test_diagnostic_codes_do_not_echo_raw_response(status, code):
    response = httpx.Response(status, text="private secret", request=httpx.Request("GET", "https://test.invalid"))
    error = httpx.HTTPStatusError("private secret", request=response.request, response=response)
    assert smoke.error_code(error) == code


def test_live_http_check_rejects_wrong_ids(monkeypatch, capsys, tmp_path, payload, semantic):
    image = tmp_path / "frame.png"
    image.write_bytes(base64.b64decode(payload["image_base64"]))
    real_client = httpx.AsyncClient

    def server(request):
        if request.url.path == "/health":
            return httpx.Response(200, json={"status": "ok", "mode": "bailian", "model_configured": True,
                                            "model": "qwen3.8-flash"})
        return httpx.Response(200, json=dict(semantic, frame_id=999, intent_revision=1, status="ok"))

    monkeypatch.setattr(smoke.Settings, "from_env", lambda: Settings())
    monkeypatch.setattr(smoke.httpx, "AsyncClient", lambda **kwargs: real_client(
        transport=httpx.MockTransport(server), **kwargs))
    args = argparse.Namespace(kind="backend", image=image, intent="测试", repeat=1,
                              base_url="http://backend.test", require_live=True)
    assert asyncio.run(smoke.run(args)) == 1
    result = json.loads(capsys.readouterr().out.splitlines()[-1])
    assert result["passed"] is False
    assert result["error_code"] == "response_id_mismatch"


def test_show_result_prints_validated_semantics_and_summary(
        monkeypatch, capsys, tmp_path, payload, semantic):
    image = tmp_path / "frame.png"
    image.write_bytes(base64.b64decode(payload["image_base64"]))
    real_client = httpx.AsyncClient

    def server(request):
        if request.url.path == "/health":
            return httpx.Response(200, json={"status": "ok", "mode": "bailian",
                                            "model_configured": True, "model": "qwen3.8-flash"})
        body = json.loads(request.content)
        return httpx.Response(200, json=dict(
            semantic, frame_id=body["frame_id"], intent_revision=body["intent_revision"], status="ok"))

    monkeypatch.setattr(smoke.Settings, "from_env", lambda: Settings())
    monkeypatch.setattr(smoke.httpx, "AsyncClient", lambda **kwargs: real_client(
        transport=httpx.MockTransport(server), **kwargs))
    args = argparse.Namespace(kind="backend", image=image, intent="测试", repeat=2,
                              base_url="http://backend.test", require_live=True,
                              show_result=True)

    assert asyncio.run(smoke.run(args)) == 0
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert lines[1]["http_status"] == 200
    assert lines[1]["result"]["scene"] == "night_low_light"
    assert lines[1]["result"]["reason"] == "霓虹灯形成彩色照明"
    assert lines[-1]["summary"]["successful"] == 2
    assert all(lines[-1]["summary"]["stable_fields"].values())
    assert "image_base64" not in lines[1]["result"]


def test_failed_results_are_never_reported_as_stable():
    failed = SceneSemantic(frame_id=1, intent_revision=1, status="unavailable",
                           uncertainty=["timeout"], reason="模型请求超时")
    summary = smoke.build_summary([failed, failed, failed], [12001, 12002, 12003])

    assert summary["successful"] == 0
    assert summary["integration_passed"] is False
    assert not any(summary["stable_fields"].values())
