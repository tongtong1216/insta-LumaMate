from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def test_health_and_openapi(mock_app):
    assert mock_app.get("/health").json() == {
        "status": "ok", "service": "lightpilot-backend", "mode": "mock", "model_configured": False,
        "model": None,
    }
    spec = mock_app.get("/openapi.json").json()
    assert "/api/v1/analyze-scene" in spec["paths"]
    assert spec["components"]["schemas"]["SceneSemantic"]["properties"]["status"]["enum"] == [
        "ok", "mock", "unavailable"]
    assert mock_app.get("/docs").status_code == 200


def test_missing_live_config_is_not_mock_or_success(payload):
    with TestClient(create_app(Settings(mode="bailian"))) as client:
        assert client.get("/health").json()["model_configured"] is False
        assert client.get("/health").json()["model"] == "qwen3.8-flash"
        data = client.post("/api/v1/analyze-scene", json=payload).json()
    assert data["status"] == "unavailable"
    assert data["uncertainty"] == ["model_not_configured"]
    assert data["scene"] is None


def test_environment_overrides_only_its_own_dotenv(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text("LIGHTPILOT_MODE=mock\nBAILIAN_API_KEY=local-test-secret\nBAILIAN_TIMEOUT_SECONDS=4\n")
    for name in ("BAILIAN_API_KEY", "BAILIAN_BASE_URL", "BAILIAN_MODEL_ID", "LIGHTPILOT_MAX_CONCURRENT"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("LIGHTPILOT_MODE", "bailian")
    monkeypatch.setenv("BAILIAN_TIMEOUT_SECONDS", "2")
    settings = Settings.from_env(env)
    assert settings.mode == "bailian"
    assert settings.timeout_seconds == 2
    assert settings.api_key.get_secret_value() == "local-test-secret"
    assert "local-test-secret" not in repr(settings)
