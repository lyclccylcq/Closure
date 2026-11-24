# Fish Drama3 本地接入预览

本接入使用 Fish 云端的 `drama-3` 和用户指定的[可露希尔社区音源](https://fish.audio/m/4632f51d7f524e6da1952135e498a6a3)，固定 `reference_id=4632f51d7f524e6da1952135e498a6a3`。音源 ID 来自用户提供的链接；账号访问权限需实际合成验证，听感由用户验收。此处不下载模型、参考录音，也不继续训练当前 Qwen。

## 准备 API key

1. 登录 [Fish 开发者页面](https://fish.audio/developers/)，使用其 **Get API key** 入口创建密钥；操作参考[官方 API 入门](https://docs.fish.audio/api-reference/introduction)。密钥只保存在本机，不发到聊天、截图或 Git。
2. 在 Fish 账号中确认可用的 **API 额度**。网页试听或订阅额度与 API 计费分开；在网页听到这个音源，不代表账号已经能通过 API 调用它。[官方计费说明](https://fish.audio/plan/)
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

预览入口仅为这次进程设置 `CLOSURE_VOICE_BACKEND=fish-drama3`，并关闭本地 Qwen/GPT-SoVITS 自动启动；它不会修改原启动入口或现有语音配置。需要 Java 21；脚本优先使用已选中的 Java 21，macOS 上也可自动查找已安装版本。

启动和就绪检查不合成云端预热语音；内置开场录音保留，后台闲聊、提醒和天气仅显示文字。主动聊天及点击桌宠触发的朗读会发送文字到 Fish，并消耗 API 额度。每段回复固定一个音源、一次请求，不自动重试或换模型。本版先完整接收 WAV 再播放，不承诺首音延迟。

先用几条短句检查音色相似、整段声线稳定、语气自然和响应时间。停止朗读会取消本地请求和播放，但不能撤销服务端已经完成的计费。账号、额度或音源权限错误会显示提示，文字聊天继续可用。

退出预览后，用原启动入口恢复原有语音。若在终端手动设置过后端，先执行 `unset CLOSURE_VOICE_BACKEND`，确保后续进程不再选择 Fish。

## 日常入口与回退

完成固定文本的真实合成和原生播放校验后，可将日常运行副本的 `启动可露.command` 设置为 Fish 后端，原桌面入口名称保持不变。启用时保留基于原启动脚本的 `启动可露-本地语音.command`，清除 Fish 后端的进程环境选择。需要回到此前 Qwen/GPT-SoVITS 时，先退出当前桌宠，再运行本地语音入口；原 `local-voice.properties`、适配器和参考音频不作替换。

启用会正常重启桌宠，进程内临时对话随重启清空。代码、启动器和配置的保护记录保存在本次 `.ai-workflow/fish-activation-local/` 证据中，不以离线测试代替真实账号或听音验收。

若固定请求返回 HTTP 402，应在 Fish 的 API 控制台检查可用额度；仅创建 API key 或拥有网页套餐不代表 API 余额可用。此时仍可安装和检查接入代码，但保持原本地语音入口，不启用 Fish，也不自动重复请求。额度可用后再继续真实试听与切换。

## 配置与验证范围

公开模板是 `config/fish-voice.example.properties`；应用只在显式选择 Fish 时读取 `config/local-fish-voice.properties`。可选环境变量如下：

| 环境变量 | 配置项 | 默认值 |
| --- | --- | --- |
| `CLOSURE_FISH_API_KEY` | `fish.apiKey` | 必填 |
| `CLOSURE_FISH_REFERENCE_ID` | `fish.referenceId` | 本页的社区音源 ID |
| `CLOSURE_FISH_BASE_URL` | `fish.baseUrl` | `https://api.fish.audio` |
| `CLOSURE_VOICE_AUTO_SPEAK` | `voice.autoSpeak` | `true` |
| `CLOSURE_FISH_CONNECT_TIMEOUT_SECONDS` | `fish.connectTimeoutSeconds` | `10` |
| `CLOSURE_FISH_REQUEST_TIMEOUT_SECONDS` | `fish.requestTimeoutSeconds` | `60` |

客户端始终明确发送 `model: drama-3`；官方说明省略或拼错会静默回退到 `s2.1-pro`。本版不添加表演标签，先评估所选音源的原始效果，不混入本地参考录音或旧情绪 profile。

离线检查只验证请求、WAV、错误、取消和播放生命周期，使用回环假服务与合成测试音频；不证明账号已认证、音源可调用或听感已验收。

`FishVoiceSmoke.java` 是仅供手动联调的独立诊断：`--check` 只通过应用正常配置读取器检查格式，不联网；`--render output.wav` 使用固定合成测试文字调用一次所选音源，不自动重试；`--playback generated.wav` 只用已保存的合成结果验证本机停止和完整重播，不再次调用云端。真实调用与主观听音状态分别记录，CI 不执行该诊断。控制台的 `CLOSURE_VOICE_CONFIGURED` 和 `CLOSURE_FISH_SYNTHESIS_REQUEST` 只标识后端配置完成与请求开始，不包含密钥或对话内容。

- [Drama3 官方文档](https://docs.fish.audio/features/text-to-speech/drama-3)
- [Text to Speech API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)
