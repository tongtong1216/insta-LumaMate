"""The Android contract. Model output never supplies IDs or actions."""

import base64
import binascii
import io
import warnings
from typing import Annotated, Literal

from PIL import Image, ImageCms, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, PrivateAttr, field_validator, model_validator

MAX_IMAGE_BYTES = 4 * 1024 * 1024
MAX_IMAGE_PIXELS = 16_000_000
MODEL_MAX_IMAGE_EDGE = 1280
MODEL_JPEG_QUALITY = 85
MAX_BODY_BYTES = 6 * 1024 * 1024
MAX_BASE64_LENGTH = 4 * ((MAX_IMAGE_BYTES + 2) // 3) + 32
Counter = Annotated[int, Field(strict=True, ge=0, le=2**63 - 1)]
Ratio = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False, strict=True)]
Note = Annotated[str, Field(min_length=1, max_length=500)]
SceneLabel = Literal[
    "indoor_even_light", "indoor_mixed_light", "indoor_low_light", "indoor_backlit",
    "outdoor_daylight", "outdoor_backlit", "night_low_light", "stage_colored_light",
    "high_contrast_other", "other",
]
SubjectType = Literal[
    "person", "group", "display", "document", "object", "landscape", "none", "other",
]
BrightRegionType = Literal[
    "none", "sky", "window", "display", "lamp", "specular_reflection", "mixed", "other",
]
ExposurePriority = Literal["subject_detail", "highlight_detail", "balanced"]
StabilityPreference = Literal["normal", "high"]
MotionPriority = Literal["motion_clarity", "low_noise", "brightness_priority", "motion_balanced"]
ColorPriority = Literal[
    "color_accuracy", "natural_skin", "atmosphere_preservation",
    "colored_light_preservation", "color_stability",
]
UncertaintyCode = Literal[
    "scene_uncertain", "subject_type_uncertain", "subject_occluded",
    "bright_region_uncertain", "colored_light_uncertain", "image_blur",
    "image_too_dark", "image_quality_uncertain", "other",
    "model_not_configured", "model_not_connected", "timeout", "rate_limited",
    "authentication_failed", "connection_failed", "invalid_model_response",
    "model_unavailable", "backend_busy", "internal_error",
]
ModelUncertaintyCode = Literal[
    "scene_uncertain", "subject_type_uncertain", "subject_occluded",
    "bright_region_uncertain", "colored_light_uncertain", "image_blur",
    "image_too_dark", "image_quality_uncertain", "other",
]
AffectedField = Literal[
    "scene", "subject_type", "subject_roi", "bright_region_type",
    "colored_light", "image_quality", "all",
]
StageWeight = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False, strict=True)]
RequestId = Annotated[str, Field(
    min_length=36, max_length=36,
    pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
)]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)


class Intent(ContractModel):
    exposure_priority: ExposurePriority
    stability_preference: StabilityPreference
    source_text: str | None = Field(default=None, min_length=1, max_length=1000)


class Metrics(ContractModel):
    subject_brightness: Ratio | None = None
    background_brightness: Ratio | None = None
    highlight_clipping_ratio: Ratio | None = None
    dark_ratio: Ratio | None = None


class AnalyzeSceneRequest(ContractModel):
    frame_id: Counter
    intent_revision: Counter
    intent: Intent
    image_base64: str = Field(min_length=1, max_length=MAX_BASE64_LENGTH, repr=False)
    metrics: Metrics | None = None
    _image_mime: str = PrivateAttr(default="")
    _image_payload: str = PrivateAttr(default="")

    @model_validator(mode="after")
    def validate_image(self) -> "AnalyzeSceneRequest":
        payload = self.image_base64
        declared_mime = None
        if payload.startswith("data:"):
            header, separator, payload = payload.partition(",")
            if not separator or header not in (
                "data:image/jpeg;base64", "data:image/png;base64", "data:image/webp;base64"
            ):
                raise ValueError("Only JPEG, PNG and WebP base64 data URLs are supported")
            declared_mime = header[5:-7]
        try:
            raw = base64.b64decode(payload, validate=True)
        except (ValueError, binascii.Error):
            raise ValueError("Invalid image base64") from None
        if not raw or len(raw) > MAX_IMAGE_BYTES:
            raise ValueError("Image must be nonempty and at most 4 MiB")
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(raw)) as img:
                    mime = {"JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}.get(img.format)
                    if mime is None or img.width * img.height > MAX_IMAGE_PIXELS:
                        raise ValueError("Unsupported image format or more than 16 million pixels")
                    if getattr(img, "n_frames", 1) != 1:
                        raise ValueError("A single representative frame is required")
                    img.verify()
                # verify() alone does not decode JPEG pixel data.
                with Image.open(io.BytesIO(raw)) as img:
                    img.load()
                    should_normalize = (max(img.size) > MODEL_MAX_IMAGE_EDGE
                                        or bool(img.info.get("icc_profile")))
                    if should_normalize:
                        model_image = img.copy()
                        model_image.thumbnail((MODEL_MAX_IMAGE_EDGE, MODEL_MAX_IMAGE_EDGE),
                                              Image.Resampling.LANCZOS)
                        if "A" in model_image.getbands():
                            background = Image.new("RGB", model_image.size, "white")
                            background.paste(model_image, mask=model_image.getchannel("A"))
                            model_image = background
                        icc_profile = img.info.get("icc_profile")
                        if icc_profile:
                            try:
                                model_image = ImageCms.profileToProfile(
                                    model_image,
                                    ImageCms.ImageCmsProfile(io.BytesIO(icc_profile)),
                                    ImageCms.createProfile("sRGB"),
                                    outputMode="RGB",
                                )
                            except (OSError, ImageCms.PyCMSError):
                                model_image = model_image.convert("RGB")
                        else:
                            model_image = model_image.convert("RGB")
                        normalized = io.BytesIO()
                        model_image.save(normalized, format="JPEG", quality=MODEL_JPEG_QUALITY,
                                         optimize=True)
                        payload = base64.b64encode(normalized.getvalue()).decode()
                        mime = "image/jpeg"
        except (UnidentifiedImageError, OSError, SyntaxError, Image.DecompressionBombError,
                Image.DecompressionBombWarning):
            raise ValueError("Invalid or oversized image") from None
        if declared_mime and declared_mime != mime:
            raise ValueError("Image MIME type does not match its content")
        self._image_mime = mime
        self._image_payload = payload
        return self

    @property
    def image_data_url(self) -> str:
        return f"data:{self._image_mime};base64,{self._image_payload}"


class ModelUncertaintyDetail(ContractModel):
    code: ModelUncertaintyCode
    severity: Literal["warning", "blocking"]
    affects: list[AffectedField] = Field(min_length=1, max_length=7)
    message: Note


class ModelSemantic(ContractModel):
    # Required keys with nullable values: an empty JSON object is not a successful result.
    scene: SceneLabel | None
    subject_type: SubjectType | None
    bright_region_type: BrightRegionType | None
    colored_light: bool | None
    uncertainty_details: list[ModelUncertaintyDetail] = Field(max_length=12)
    reason: Note | None


class UncertaintyDetail(ContractModel):
    code: UncertaintyCode
    severity: Literal["warning", "blocking"]
    affects: list[AffectedField] = Field(min_length=1, max_length=7)
    message: Note


class StageWeights(ContractModel):
    exposure: StageWeight
    motion_noise: StageWeight
    color_atmosphere: StageWeight

    @field_validator("exposure", "motion_noise", "color_atmosphere", mode="before")
    @classmethod
    def accept_json_numbers_but_not_booleans(cls, value):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("Weight must be a JSON number")
        return float(value)


class ParsedIntent(ContractModel):
    weights: StageWeights
    exposure_priority: ExposurePriority | None
    motion_priority: MotionPriority | None
    color_priority: ColorPriority | None
    stability_preference: StabilityPreference


class ParseIntentRequest(ContractModel):
    request_id: RequestId
    source_text: str = Field(min_length=1, max_length=1000)


class ModelIntentParse(ContractModel):
    intent: ParsedIntent
    ambiguities: list[Note] = Field(max_length=12)
    reason: Note

    @model_validator(mode="after")
    def require_ambiguity_for_missing_active_priority(self) -> "ModelIntentParse":
        weights = self.intent.weights
        missing = (
            (weights.exposure >= 0.5 and self.intent.exposure_priority is None)
            or (weights.motion_noise >= 0.5 and self.intent.motion_priority is None)
            or (weights.color_atmosphere >= 0.5 and self.intent.color_priority is None)
        )
        if missing and not self.ambiguities:
            raise ValueError("An active stage with no priority requires an ambiguity")
        return self


class ParseIntentResponse(ContractModel):
    request_id: RequestId
    status: Literal["ok", "mock", "unavailable"]
    intent: ParsedIntent | None = None
    ambiguities: list[Note] = Field(default_factory=list, max_length=12)
    reason: Note


class SceneSemantic(ContractModel):
    frame_id: Counter
    intent_revision: Counter
    status: Literal["ok", "mock", "unavailable"]
    scene: SceneLabel | None = None
    subject_type: SubjectType | None = None
    bright_region_type: BrightRegionType | None = None
    colored_light: bool | None = None
    uncertainty: list[Note] = Field(default_factory=list, max_length=12)
    uncertainty_details: list[UncertaintyDetail] = Field(default_factory=list, max_length=12)
    reason: Note | None = None
