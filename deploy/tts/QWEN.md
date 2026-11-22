# Mac 本地 Qwen3-TTS

Closure 可使用 Qwen3-TTS 0.6B Base 8-bit 在 Apple Silicon 上通过 Metal 本地合成。MLX-Audio 0.6.5 修复了整句解码误裁句尾的问题。语速默认 0.88 倍，FFmpeg 保持音高。

准备 Apple Silicon 版 Python 3.11–3.13、FFmpeg、Java 21，以及匹配 `prepare-qwen.py` 中参考文本的 `neutral.wav`、`question.wav` 和 `teasing.wav`。

```sh
bash deploy/tts/install-macos-qwen.sh --reference-dir "/path/to/reference-wavs" --activate
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw javafx:run
```

安装脚本只下载固定版本的公开模型，参考音频及语音配置保存在本机。已有模型和参考音频时，可以直接在独立运行目录安装 `qwen-requirements.txt`，然后运行 `prepare-qwen.py --activate` 来配置该副本。配置与模型文件不进入 Git。

Qwen 由桌宠自动启动，默认仅监听 `127.0.0.1:19882`。在 `/closure/health` 显示 `ready: true` 前，应用等待服务完成三种声音的预热。第一条 TTS 会等整个 WAV 合成完成后再播，之后恢复逐段播放，使用同一条 PCM 输出线。桌宠内置开场录音独立播放。

使用 `bash deploy/tts/start-qwen.sh` 可单独启动服务。冷启动时用 `curl http://127.0.0.1:19882/closure/health` 查询预热状态。如果需要播放排查，在启动日志中先确认已完成预热，再区分生成音频的句尾和播放器的实际输出。

本地微调适配器可通过 `.runtime/qwen3-tts/active-adapter.json` 启用。将适配参数导出为该目录下的 safetensors 文件，并提供以下清单；`sha256` 必须替换为该文件真实的 64 位小写校验值，模型版本必须与训练使用的基础模型一致：

```json
{
  "format": "closure-qwen-lora-v1",
  "id": "local-voice-v1",
  "base_model_id": "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-8bit",
  "base_model_revision": "50f45ef0047cde7e84c2ef04326acb8ada2436a7",
  "rank": 8,
  "alpha": 16,
  "targets": ["q_proj", "v_proj"],
  "weights": "adapters/local-voice/adapter.safetensors",
  "sha256": "replace_with_the_exported_file_sha256"
}
```

适配器在三个声音预热前加载；预热完成后 health 会显示 `adapter_loaded: true`、`adapter_id` 和校验值。清单及权重只保存在本机，不能提交到 Git。此接口仅支持 talker 主干 q/v 层的 LoRA；不合并或重新量化基础模型。启用文件损坏、版本不符或参数不完整时，服务会启动失败并记录原因，避免无声切换音色。

删除本地 `active-adapter.json` 并重启桌宠即可恢复原模型。单独排查时也可运行 `bash deploy/tts/start-qwen.sh --no-adapter`；指定其他本地清单使用 `--adapter-config /path/to/manifest.json`。
