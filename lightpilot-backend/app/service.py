import asyncio
import logging
import time

from openai import APIConnectionError, APIStatusError, APITimeoutError, AuthenticationError, RateLimitError
from pydantic import ValidationError

from app.bailian_client import BailianClient, InvalidModelResponse
from app.config import Settings
from app.schemas import AnalyzeSceneRequest, SceneSemantic

logger = logging.getLogger("lightpilot")
FAILURE_REASONS = {
    "model_not_configured": "百炼密钥或地址尚未配置",
    "timeout": "模型请求超时，请保持当前相机设置",
    "rate_limited": "模型服务限流，请稍后再试",
    "authentication_failed": "模型服务鉴权失败",
    "connection_failed": "无法连接模型服务",
    "invalid_model_response": "模型未返回有效的场景语义",
    "model_unavailable": "模型服务暂不可用",
    "backend_busy": "后端繁忙，请稍后再试",
    "internal_error": "场景分析暂不可用",
}


class SceneService:
    def __init__(self, settings: Settings, client: BailianClient | None):
        self.settings = settings
        self.client = client
        self.slots = asyncio.Semaphore(settings.max_concurrent)

    def unavailable(self, req: AnalyzeSceneRequest, code: str) -> SceneSemantic:
        # Never log raw exceptions, API credentials, prompts, images or provider responses.
        logger.warning("scene_unavailable frame_id=%s intent_revision=%s code=%s",
                       req.frame_id, req.intent_revision, code)
        return SceneSemantic(
            frame_id=req.frame_id, intent_revision=req.intent_revision,
            status="unavailable", uncertainty=[code], reason=FAILURE_REASONS[code],
        )

    async def analyze(self, req: AnalyzeSceneRequest) -> SceneSemantic:
        if self.settings.mode == "mock":
            return SceneSemantic(
                frame_id=req.frame_id, intent_revision=req.intent_revision,
                status="mock", uncertainty=["model_not_connected"],
                reason="Mock 联调数据：未调用百炼，不可用于相机控制",
            )
        if self.client is None:
            return self.unavailable(req, "model_not_configured")
        try:
            await asyncio.wait_for(self.slots.acquire(), timeout=0.05)
        except TimeoutError:
            return self.unavailable(req, "backend_busy")
        started = time.monotonic()
        try:
            async with asyncio.timeout(self.settings.timeout_seconds):
                semantic = await self.client.analyze(req)
            result = SceneSemantic(
                **semantic.model_dump(), frame_id=req.frame_id,
                intent_revision=req.intent_revision, status="ok",
            )
            logger.info("scene_ok frame_id=%s intent_revision=%s elapsed_ms=%d",
                        req.frame_id, req.intent_revision, (time.monotonic() - started) * 1000)
            return result
        except (TimeoutError, APITimeoutError):
            code = "timeout"
        except RateLimitError:
            code = "rate_limited"
        except AuthenticationError:
            code = "authentication_failed"
        except APIConnectionError:
            code = "connection_failed"
        except APIStatusError as exc:
            code = "authentication_failed" if exc.status_code == 403 else "model_unavailable"
        except (InvalidModelResponse, ValidationError):
            code = "invalid_model_response"
        except Exception:
            code = "internal_error"
        finally:
            self.slots.release()
        return self.unavailable(req, code)
