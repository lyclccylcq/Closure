# Fish 云端语音：默认免费 S2.1

本接入默认使用 Fish 云端的 `s2.1-pro-free` 和用户指定的[可露希尔社区音源](https://fish.audio/m/4632f51d7f524e6da1952135e498a6a3)，固定 `reference_id=4632f51d7f524e6da1952135e498a6a3`。`drama-3` 保留为明确选择的付费备选。此处不下载模型、参考录音，也不继续训练当前 Qwen；听感由用户验收。

按当前[官方型号说明](https://docs.fish.audio/developer-guide/models-pricing/models-overview)，`s2.1-pro-free` 与付费 `s2.1-pro` 使用同一模型，免费版受公平使用限制，不保证首音延迟。[官方计价](https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits)中前者为 $0，后者仍收费。选择免费版必须使用完整的 `s2.1-pro-free`，不能省略 `-free`。

## 准备 API key

1. 登录 [Fish 开发者页面](https://fish.audio/developers/)，使用其 **Get API key** 入口创建密钥；操作参考[官方 API 入门](https://docs.fish.audio/api-reference/introduction)。密钥只保存在本机，不发到聊天、截图或 Git。
2. 默认免费型号仍需要有效 API key，并受免费使用限制。只有明确选择付费 Drama3 时才检查对应 API 额度；网页试听不代表账号已经能通过 API 调用该音源。
3. 在项目根目录执行以下命令，填写新文件里的 `fish.apiKey`。`cp -n` 保留已有文件；不要替换原来的 `local-voice.properties`。

```bash
cp -n config/fish-voice.example.properties config/local-fish-voice.properties
chmod 600 config/local-fish-voice.properties
open -e config/local-fish-voice.properties
```

新文件已被 Git 忽略。也可以通过 `CLOSURE_FISH_API_KEY` 提供密钥；不要把包含真实密钥的 `export` 命令留在终端历史中。环境变量优先于配置文件。

日常运行副本与开发副本的配置相互独立。代理协助启用时，先安装受审代码，并在日常运行目录准备该文件，由用户在本机编辑器中私下填写；代理只核对文件是否存在和权限，不打开密钥内容。原 AI、天气和本地语音配置保留原位，不需要重新提供。

## 启动与试听

准备好 API key 和额度后，先退出正在运行的桌宠，避免同时打开两个实例，再在项目根目录运行：

```bash
bash deploy/tts/start-fish-preview.sh
```

预览入口仅为这次进程设置 `CLOSURE_VOICE_BACKEND=fish`，默认显式设置 `CLOSURE_FISH_MODEL=s2.1-pro-free`，并关闭本地 Qwen/GPT-SoVITS 自动启动；它不会修改原启动入口或现有语音配置。需要 Java 21；脚本优先使用已选中的 Java 21，macOS 上也可自动查找已安装版本。

启动和就绪检查不合成云端预热语音；内置开场录音保留，后台闲聊、提醒和天气仅显示文字。主动聊天及点击桌宠触发的朗读会发送文字到 Fish；默认使用免费型号，明确选择 Drama3 时按 API 计费。每段回复固定一个音源、一次请求，不自动重试或换模型，免费接口报错也不会转到付费型号。本版先完整接收 WAV 再播放，不承诺首音延迟。

先用几条短句检查音色相似、整段声线稳定、语气自然和响应时间。停止朗读会取消本地请求和播放，但不能撤销服务端已经完成的计费。账号、额度或音源权限错误会显示提示，文字聊天继续可用。

退出预览后，原启动入口使用已安装的日常默认型号。若在终端手动设置过后端，先执行 `unset CLOSURE_VOICE_BACKEND CLOSURE_FISH_MODEL`，清除这次预览的进程选择。

## 日常入口与回退

完成固定文本的真实合成和原生播放校验后，日常运行副本的 `启动可露.command` 显式选择 `fish` 和 `s2.1-pro-free`，原桌面入口名称保持不变。`启动可露-Drama3付费.command` 明确选择付费 Drama3；原 `启动可露-本地语音.command` 保留本地 Qwen/GPT-SoVITS。更换入口前先退出当前桌宠；原密钥配置、`local-voice.properties`、适配器和参考音频不作替换。

启用会正常重启桌宠，进程内临时对话随重启清空。免费型号的安装与试听记录保存在 `.ai-workflow/fish-free-local/`；此前 Drama3 的记录保留在 `.ai-workflow/fish-activation-local/`。离线测试不代替真实账号或听音验收。

付费型号返回 HTTP 402 时应检查 API 额度。免费型号失败时先确认完整型号、账号权限和使用限制，不自动充值、重试或切换付费服务。

## 配置与验证范围

公开模板是 `config/fish-voice.example.properties`；应用只在显式选择 Fish 时读取 `config/local-fish-voice.properties`。可选环境变量如下：

| 环境变量 | 配置项 | 默认值 |
| --- | --- | --- |
| `CLOSURE_FISH_API_KEY` | `fish.apiKey` | 必填 |
| `CLOSURE_FISH_MODEL` | `fish.model` | `s2.1-pro-free` |
| `CLOSURE_FISH_REFERENCE_ID` | `fish.referenceId` | 本页的社区音源 ID |
| `CLOSURE_FISH_BASE_URL` | `fish.baseUrl` | `https://api.fish.audio` |
| `CLOSURE_VOICE_AUTO_SPEAK` | `voice.autoSpeak` | `true` |
| `CLOSURE_FISH_CONNECT_TIMEOUT_SECONDS` | `fish.connectTimeoutSeconds` | `10` |
| `CLOSURE_FISH_REQUEST_TIMEOUT_SECONDS` | `fish.requestTimeoutSeconds` | `60` |

客户端始终明确发送所选的完整 `model` 值。仅允许 `s2.1-pro-free` 与 `drama-3`，拼写错误及付费 `s2.1-pro` 会在本机拒绝，因为官方接口省略或拼错型号可能静默回退到付费 `s2.1-pro`。通用 `fish` 后端默认免费；旧 `fish-drama3` 后端别名保留原来的 Drama3 默认，显式模型环境变量优先。不混入本地参考录音或旧情绪 profile。

免费 S2.1 的整段请求在原朗读正文之前增加一次 `[natural conversational delivery, steady tone]`，使用 `temperature=0.5`、`top_p=0.7`，并显式发送 `chunk_length=300`、`min_chunk_length=100`、`condition_on_previous_chunks=true`。原正文、标点和显示文字保持原样，客户端仍只请求一次、完整接收后播放。付费 Drama3 保留原参数，不添加这一提示。

这些设置依据 [S2 口吻提示说明](https://docs.fish.audio/developer-guide/core-features/emotions)及 [TTS 参数说明](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)，用于减少采样变化和过早分段；`condition_on_previous_chunks` 本来就默认开启，此处明确声明。服务端仍可能分段，语气也会受文本、音源和生成模型影响，单个提示及较低随机性不等于锁定声线或保证逐次完全相同。对照试听保存在 `.ai-workflow/fish-tone-local/`，听感需由用户确认。

离线检查只验证请求、WAV、错误、取消和播放生命周期，使用回环假服务与合成测试音频；不证明账号已认证、音源可调用或听感已验收。

Fish 的[官方兼容性说明](https://docs.fish.audio/developer-guide/compat/capabilities#audio-formats)记录了流式 WAV 的固定占位长度（RIFF `4294967076`、data `4294967040`）。客户端在 HTTP 音频完整接收后，按实际字节数修复这一对规范占位值，再验证 PCM 格式和长度；普通有限长度不匹配、空音频、半个 PCM 样本和 HTTP 截断仍报错。格式诊断标记只记录头部长度值，不包含音频内容或密钥。

`FishVoiceSmoke.java` 是仅供手动联调的独立诊断：`--check` 只通过应用正常配置读取器检查格式，不联网；`--render output.wav` 使用固定合成测试文字调用一次所选音源，不自动重试；`--playback generated.wav` 只用已保存的合成结果验证本机停止和完整重播，不再次调用云端。真实调用与主观听音状态分别记录，CI 不执行该诊断。控制台的 `CLOSURE_VOICE_CONFIGURED` 和 `CLOSURE_FISH_SYNTHESIS_REQUEST` 只标识后端配置完成与请求开始，不包含密钥或对话内容。

- [Drama3 官方文档](https://docs.fish.audio/features/text-to-speech/drama-3)
- [Text to Speech API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)
