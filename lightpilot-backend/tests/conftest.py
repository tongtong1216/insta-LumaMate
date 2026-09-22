import base64
import io
import json

import httpx
import pytest
from PIL import Image
from fastapi.testclient import TestClient

from app.bailian_client import BailianClient
from app.config import Settings
from app.main import create_app


@pytest.fixture
def payload():
    image = io.BytesIO()
    Image.new("RGB", (32, 32), (80, 100, 120)).save(image, format="PNG")
    return {
        "frame_id": 42, "intent_revision": 7, "intent": "保留夜景氛围",
        "image_base64": base64.b64encode(image.getvalue()).decode(),
        "metrics": {"subject_brightness": 0.25, "highlight_ratio": 0.05, "dark_ratio": 0.4},
    }


@pytest.fixture
def semantic():
    return {
        "scene": "night_low_light", "subject_type": "person",
        "bright_region_type": "lamp", "colored_light": True,
        "uncertainty": ["主体部分遮挡"], "reason": "霓虹灯形成彩色照明",
    }


def completion(content, finish_reason="stop"):
    return {
        "id": "test-response", "object": "chat.completion", "created": 0,
        "model": "test-vision", "choices": [{
            "index": 0, "finish_reason": finish_reason,
            "message": {"role": "assistant", "content": content},
        }],
    }


@pytest.fixture
def mock_app():
    with TestClient(create_app(Settings())) as client:
        yield client


@pytest.fixture
def live_app():
    """The real OpenAI adapter with a local HTTP transport; never calls the cloud."""
    clients = []

    def factory(handler, **overrides):
        settings = Settings(mode="bailian", api_key="test-only-secret",
                            base_url="https://bailian.example.invalid/v1", **overrides)
        adapter = BailianClient(settings, http_client=httpx.AsyncClient(
            transport=httpx.MockTransport(handler)))
        client = TestClient(create_app(settings, client=adapter))
        client.__enter__()
        clients.append(client)
        return client

    yield factory
    for client in reversed(clients):
        client.__exit__(None, None, None)
