# D 的三阶段测试指南

## 测试边界

D 的三个阶段不是三套不同的图片模型接口。rc3 使用两个职责分离的入口：

```text
POST /api/v1/parse-intent
→ qwen3.8-flash
→ 三个独立权重与固定意图枚举

POST /api/v1/analyze-scene
→ qwen3.8-flash
→ SceneSemantic + uncertainty_details
```

D 负责验证真实图片调用、固定枚举、请求编号、语义稳定性、不确定性和彩色光判断。
C 负责运动指标、EV/快门/ISO/白平衡策略和连续帧控制；A 负责相机写入。

因此 D 的阶段二测试可以证明“运动场景的代表帧能够得到可用场景语义”，但不能证明
运动分数、快门或 ISO 建议正确。

## 1. 准备三份素材

建议使用不同的真实素材：

| 阶段 | 素材 |
|---|---|
| 阶段一 | 暗主体与亮背景、逆光人物或人物与亮屏 |
| 阶段二 | 运动主体、夜间运动或室内低光动作的清晰代表帧 |
| 阶段三 | 舞台彩灯、霓虹灯、暖色室内人物或混合色温场景 |

JPEG、PNG 或 WebP 均可，单张不超过 4 MiB。阶段三素材确实包含明显彩色光时，测试命令
加入 `--expect-stage3-colored-light`。

## 2. 启动真实后端

终端一：

```bash
cd /Users/kugua/insta360-autu_adjust/lightpilot-backend
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

如果出现 `address already in use`，说明 8000 端口已有服务。先访问
<http://127.0.0.1:8000/health>，确认正在运行的是当前项目。

终端二先检查：

```bash
curl -s http://127.0.0.1:8000/health
```

必须看到：

```json
{
  "status": "ok",
  "mode": "bailian",
  "model": "qwen3.8-flash",
  "model_configured": true
}
```

这只能证明配置已加载，下一步的真实图片请求才证明大模型可用。

## 3. 先验证多阶段文本解析

```bash
.venv/bin/python -m scripts.smoke \
  --kind intent \
  --intent "夜间跑步时拍清楚人物，同时保留霓虹灯颜色" \
  --expect-all-stages \
  --show-result \
  --repeat 3
```

三次必须为 `status=ok`、`passed=true`，并且三个权重均 `>=0.50`。输出必须只有固定枚举，
不能包含 EV、ISO、快门、白平衡目标或 SDK 方法。

## 4. 运行 D 的三阶段真实图片验收

```bash
cd /Users/kugua/insta360-autu_adjust/lightpilot-backend

.venv/bin/python -m scripts.three_stage_probe \
  --stage1-image "/绝对路径/阶段一_逆光人物.jpg" \
  --stage2-image "/绝对路径/阶段二_运动低光.jpg" \
  --stage3-image "/绝对路径/阶段三_彩色灯光.jpg" \
  --repeat 3 \
  --expect-stage3-colored-light \
  --output "test-results/d-three-stage-report.json"
```

如果阶段三测试的是普通肤色而不是彩色灯光，删除
`--expect-stage3-colored-light`。

脚本总共调用模型 9 次。每次输出完整的、已经通过 Pydantic 校验的 `SceneSemantic`，最终
生成统一 JSON 报告。脚本不会打印 API Key、图片 Base64 或未经校验的上游响应。

## 5. 判定结果

最后一行应包含：

```json
{
  "overall_passed": true
}
```

报告中每个阶段分别观察：

```text
contract_passed = true
stage_usable = true
required_field_stability = true
stage_passed = true
```

字段含义：

- `contract_passed`：HTTP、状态、编号和 rc3 结构正确；
- `stage_usable`：该阶段依赖字段非空，且没有影响这些字段的 blocking/warning；
- `field_stability`：独立记录场景、主体、亮区和彩色光是否稳定；
- `required_field_stability`：只检查该阶段实际依赖的字段；
- `colored_light_expectation_matched`：启用彩色光断言时，三次都必须为 `true`。

非空 `uncertainty` 不再自动阻断 rc3 的所有阶段。脚本只读固定的
`uncertainty_details.severity/affects`：例如主体遮挡不阻断阶段二全局运动策略，
`colored_light` 不确定会阻断依赖它的阶段三策略。rc2 客户端仍安全地整帧 HOLD。

## 6. 阶段二视频补充测试

D 的视频测试仍然是抽帧后逐帧调用同一个接口：

```bash
cd /Users/kugua/insta360-autu_adjust/lightpilot-backend

.venv/bin/python -m scripts.video_probe \
  --video "/Users/kugua/Downloads/IMG_1665.MOV" \
  --samples 6 \
  --intent "优先冻结运动，宁愿有少量噪点也不要明显拖影" \
  --exposure-priority subject_detail \
  --stability-preference normal \
  --intent-revision 1 \
  --output "test-results/d-stage2-video-report.json"
```

预期 `contract_passed=true` 且 `stage_usable.stage2_motion_noise=true`。`colored_light` 或
`subject_type` 波动会记录到 `field_stability`，不会单独让阶段二失败。这个命令不测试 C 的
运动分数，也不把连续六帧当成 C 的三帧确认。

## 7. D 通过后再测试 C

D 的 `overall_passed=true` 后，再运行 Android 本地策略测试：

```bash
cd /Users/kugua/insta360-autu_adjust
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

模拟器页面底部可以分别验证阶段二和阶段三。阶段二/三当前必须显示
`inputSource=MOCK`、`executionMode=MOCK`，点击执行验证后必须得到
`MOCK_EXECUTION`。

## 8. 常见失败

| 结果 | 含义与处理 |
|---|---|
| 无法连接 `/health` | 后端未启动、地址错误或端口被其他程序占用 |
| 后端不是真实模式 | 检查 `.env` 的 `LIGHTPILOT_MODE=bailian` 并重启 |
| `authentication_failed` | Key、工作空间、地域或 Base URL 不匹配 |
| `timeout` | 模型响应超过设置时间；检查网络并适当提高超时 |
| `stage_semantic_unavailable` | 当前阶段依赖字段不可用；查看结构化 `affects` |
| 字段不稳定 | 同一素材三次分类发生变化；保存报告并调整提示或枚举边界 |
| 阶段三彩色光断言失败 | 画面中的彩色光不明显，或模型分类需要修正 |
