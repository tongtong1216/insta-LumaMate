"""Load only backend settings, without changing the process environment."""

import os
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from dotenv import dotenv_values
from pydantic import BaseModel, Field, SecretStr, field_validator

BACKEND_ROOT = Path(__file__).resolve().parent.parent


class Settings(BaseModel):
    mode: Literal["mock", "bailian"] = "mock"
    api_key: SecretStr = SecretStr("")
    base_url: str = ""
    model_id: str = Field(default="qwen3.8-flash", min_length=1)
    timeout_seconds: float = Field(default=30, gt=0, le=60, allow_inf_nan=False)
    max_concurrent: int = Field(default=4, ge=1, le=32)

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        value = value.strip().rstrip("/")
        if value:
            url = urlsplit(value)
            if (url.scheme != "https" or not url.hostname or url.username
                    or url.password or url.query or url.fragment):
                raise ValueError("BAILIAN_BASE_URL must be an HTTPS endpoint without credentials")
        return value

    @property
    def model_configured(self) -> bool:
        return bool(self.api_key.get_secret_value().strip() and self.base_url)

    @classmethod
    def from_env(cls, env_file: Path = BACKEND_ROOT / ".env") -> "Settings":
        values = dotenv_values(env_file)
        names = {
            "mode": "LIGHTPILOT_MODE", "api_key": "BAILIAN_API_KEY",
            "base_url": "BAILIAN_BASE_URL", "model_id": "BAILIAN_MODEL_ID",
            "timeout_seconds": "BAILIAN_TIMEOUT_SECONDS",
            "max_concurrent": "LIGHTPILOT_MAX_CONCURRENT",
        }
        return cls(**{
            field: os.environ.get(name, values.get(name))
            for field, name in names.items()
            if os.environ.get(name, values.get(name)) is not None
        })
