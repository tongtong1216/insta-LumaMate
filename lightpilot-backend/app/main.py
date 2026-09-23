from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.bailian_client import BailianClient
from app.config import Settings
from app.schemas import (
    MAX_BODY_BYTES, AnalyzeSceneRequest, ParseIntentRequest, ParseIntentResponse, SceneSemantic,
)
from app.service import SceneService


class RequestLimits:
    """Bound the body before JSON/base64 parsing, including chunked uploads."""

    def __init__(self, app: ASGIApp, max_bytes: int = MAX_BODY_BYTES):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http" or scope["method"] != "POST":
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        content_type = headers.get(b"content-type", b"").split(b";", 1)[0].strip().lower()
        if content_type != b"application/json":
            return await JSONResponse({"detail": "application/json required"}, 415)(scope, receive, send)
        try:
            length = int(headers.get(b"content-length", b"0"))
            if length < 0:
                raise ValueError
        except ValueError:
            return await JSONResponse({"detail": "Invalid Content-Length"}, 400)(scope, receive, send)
        chunks = []
        size = 0
        if length <= self.max_bytes:
            while True:
                message = await receive()
                if message["type"] == "http.disconnect":
                    return
                chunk = message.get("body", b"")
                size += len(chunk)
                if size > self.max_bytes:
                    break
                chunks.append(chunk)
                if not message.get("more_body", False):
                    body = b"".join(chunks)
                    delivered = False

                    async def replay():
                        nonlocal delivered
                        if delivered:
                            return await receive()
                        delivered = True
                        return {"type": "http.request", "body": body, "more_body": False}

                    return await self.app(scope, replay, send)
        return await JSONResponse({"detail": "Request body exceeds 6 MiB"}, 413)(scope, receive, send)


def create_app(settings: Settings | None = None, *, client: BailianClient | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        model_client = client
        if model_client is None and settings.mode == "bailian" and settings.model_configured:
            model_client = BailianClient(settings)
        app.state.scene_service = SceneService(settings, model_client)
        try:
            yield
        finally:
            if model_client is not None:
                await model_client.close()

    app = FastAPI(title="LightPilot Backend", version="1.0.0-rc3", lifespan=lifespan,
                  description=("Structured multi-stage intent parsing and scene semantics. "
                               "Mock/unavailable results require Android HOLD."))
    app.add_middleware(RequestLimits)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        # FastAPI's default error includes input, potentially echoing a full private image.
        return JSONResponse(status_code=422, content={"detail": [
            {"loc": list(error["loc"]), "type": error["type"], "msg": "Invalid request field"}
            for error in exc.errors()
        ]})

    @app.get("/health")
    async def health():
        return {"status": "ok", "service": "lightpilot-backend", "mode": settings.mode,
                "model_configured": settings.model_configured,
                "model": settings.model_id if settings.mode == "bailian" else None}

    @app.post("/api/v1/analyze-scene", response_model=SceneSemantic)
    async def analyze_scene(req: AnalyzeSceneRequest, request: Request):
        return await request.app.state.scene_service.analyze(req)

    @app.post("/api/v1/parse-intent", response_model=ParseIntentResponse)
    async def parse_intent(req: ParseIntentRequest, request: Request):
        return await request.app.state.scene_service.parse_intent(req)

    return app


app = create_app()
