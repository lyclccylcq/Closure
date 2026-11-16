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
