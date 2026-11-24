# Fish Drama3 本地接入预览

本接入使用 Fish 云端的 `drama-3` 和用户指定的[可露希尔社区音源](https://fish.audio/m/4632f51d7f524e6da1952135e498a6a3)，固定 `reference_id=4632f51d7f524e6da1952135e498a6a3`。音源 ID 来自用户提供的链接；页面未能正常读取，音源的账号访问权限、真实 API 合成和实际听感尚待验证。此处不下载模型、参考录音，也不继续训练当前 Qwen。

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

## 启动与试听

准备好 API key 和额度后，先退出正在运行的桌宠，避免同时打开两个实例，再在项目根目录运行：

```bash
bash deploy/tts/start-fish-preview.sh
```

预览入口仅为这次进程设置 `CLOSURE_VOICE_BACKEND=fish-drama3`，并关闭本地 Qwen/GPT-SoVITS 自动启动；它不会修改原启动入口或现有语音配置。需要 Java 21；脚本优先使用已选中的 Java 21，macOS 上也可自动查找已安装版本。

启动和就绪检查不合成云端预热语音；内置开场录音保留，后台闲聊、提醒和天气仅显示文字。主动聊天及点击桌宠触发的朗读会发送文字到 Fish，并消耗 API 额度。每段回复固定一个音源、一次请求，不自动重试或换模型。本版先完整接收 WAV 再播放，不承诺首音延迟。

先用几条短句检查音色相似、整段声线稳定、语气自然和响应时间。停止朗读会取消本地请求和播放，但不能撤销服务端已经完成的计费。账号、额度或音源权限错误会显示提示，文字聊天继续可用。

退出预览后，用原启动入口恢复原有语音。若在终端手动设置过后端，先执行 `unset CLOSURE_VOICE_BACKEND`，确保后续进程不再选择 Fish。

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

离线检查只验证请求、WAV、错误、取消和播放生命周期，使用回环假服务与合成测试音频；不证明账号已认证、音源可调用或听感已验收。当前接入没有执行真实 Fish 合成，也未切换日常运行版本。

- [Drama3 官方文档](https://docs.fish.audio/features/text-to-speech/drama-3)
- [Text to Speech API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech)
