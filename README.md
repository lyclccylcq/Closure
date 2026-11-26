# Closure · 可露希尔 AI 桌面助手

> 「博士，你的终端好像又冒红光了哦？」

一个基于 **Java 21、JavaFX 和 Swing** 的非官方同人桌面项目，以《明日方舟》的可露希尔为角色原型，结合 Spine 动画、AI 对话、语音朗读和日常提醒。

目前重点是让人物动作、聊天与语音配合自然。开发环境感知、构建报错分析和代码修改仍属于后续目标。

## 当前进度

**更新于 2026-11-25。** 主分支已包含多轮对话、Fish 语音、固定触碰台词缓存和新版桌面动作，可从本页的启动入口使用。

| 能力 | 已实现的内容 |
| --- | --- |
| 桌面窗口 | 透明、无边框、置顶、鼠标拖动、双击打开通讯窗口 |
| Spine 人物 | 原始骨骼与网格播放、六套源动作、PNG 回退、透明窗口闪烁修复 |
| AI 对话 | 兼容 OpenAI Chat Completions、流式显示、独立角色卡、近期多轮问答、停止生成与新对话 |
| Fish 云端语音 | 使用可露希尔社区音源，启用 Fish 时默认选择 `s2.1-pro-free`，可显式选择 `drama-3` |
| 固定触碰语音 | 六条固定台词首次成功生成后缓存，相同台词复用同一份音频，重启后继续使用 |
| 本地语音 | GPT-SoVITS／本地 Qwen 接口、自动朗读、语音服务启动与错误提示 |
| 日常动作 | 自主左右走动、屏幕边缘转身、对话期间保持思考坐姿、两分钟长待机后睡觉 |
| 拖动交互 | 后颈提起、四肢放松与轻摆、落地回弹，右拖朝左、左拖朝右 |
| 天气与提醒 | macOS 当前定位天气、工作休息提醒、课程与 Deadline 导入、当天定时事项 |

## 快速开始

需要 **JDK 21**。项目使用 Maven Wrapper，无需另外安装 Maven。运行完整桌宠需要图形桌面环境；AI 与语音需分别配置。

### 运行桌宠

```bash
git clone https://github.com/lyclccylcq/Closure.git
cd Closure
./mvnw javafx:run
```

普通 Maven 启动读取本机的 AI 和语音配置。使用 Fish 朗读请配置 API key 后运行对应脚本。[Fish 配置与启动](#fish-云端语音)

### 只看人物和动作

```bash
./docs/character/preview-spine.command
```

这个独立预览不启动 AI、TTS、天气和提醒；其中的说话、思考等控件是状态模拟。资源来源、动作说明和回退方式见 [Spine 说明](docs/character/spine-upgrade.md)。

如果本机有多个 JDK，可在当前终端设置 `JAVA_HOME` 指向 JDK 21 后再运行。macOS 可使用：

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
```

## 人物与桌面交互

- **自主行走**：清醒且空闲时停留约 20–35 秒，再左右走动 4–7 秒；在当前屏幕可用区域内活动，到边缘转身。右键 `动作 → 行走` 可手动触发，`动作 → 自主走动` 可切换本次运行的自动行为。
- **对话与睡眠**：打开通讯窗口后保持思考坐姿，朗读时保留坐姿并带基础口型；长时间没有人物交互时默认睡觉。自主走动不重置两分钟睡眠计时。
- **按住与拖动**：按住后进入受力姿态，拖动时呈现后颈提起效果；身体与四肢轻摆，松手有落地回弹。向右拖时朝左，向左拖时朝右；轻微抖动和上下移动不会反复翻面。
- **动作协调**：聊天、说话、触碰、拖动、鼠标悬停及右键菜单会暂停走动。恢复空闲后重新等待；拖动不会误触固定台词。

提起、落地和基础口型由现有骨骼驱动。音素级口型同步仍在路线图上。[日常动作与拖放说明](docs/character-behavior.md)

## 工作台

右键桌宠，选择「工作台」。从左侧新建会话或打开本地项目。标题栏的「侧栏」和「项目文件」可以分别展开或收起对应区域。项目面板在本机只读预览 UTF-8 文本，收起后会保留当前文件。工作台关窗重开可继续当前进程中的会话。

工作台使用深靛色和像素美术。新会话居中输入，开始对话后输入框移到窗口下方。Enter 发送，Shift+Enter 换行。[主题素材来源与授权](src/main/resources/ui/workbench/NOTICE.md)

## AI 对话与记忆

复制公开配置模板，编辑新文件填写自己的端点、密钥和模型名：

```bash
cp -n config/local-ai.example.properties config/local-ai.properties
```

```properties
CLOSURE_AI_ENDPOINT=https://api.openai.com/v1/chat/completions
CLOSURE_AI_API_KEY=replace-with-your-api-key
CLOSURE_AI_MODEL=your-model-name
```

支持兼容 OpenAI Chat Completions 的服务，具体模型由配置决定。环境变量优先于本地文件；项目不绑定某个 GPT 型号。

角色卡与输出协议独立维护，日常接话按语境组织。模型返回的 `displayText` 用于显示，`speechText` 用于简短口语朗读，`dominantEmotion` 用于情绪与动作反馈。技术长文可以完整显示，同时朗读摘要。

多轮上下文的当前边界：

- 请求最多携带最近 **12 轮完整问答**，历史预算为 **24,000 个 Unicode 码点**，不是精确 token 数。
- 只回放助手的可见正文，不把朗读摘要和情绪标签重复注入历史；失败或取消的回复不作为成功问答保存。
- 关窗再打开，当前进程中的对话仍在；点“新对话”或重启应用会清空。尚无跨重启长期记忆、滚动摘要或偏好数据库。
- “停止”会取消当前及排队回复并停止朗读；“新对话”同时清空本次会话。

角色与对话相关资料包括 [角色卡](docs/character/closure-role-card.zh-CN.txt)、[对话验证说明](docs/character/dialogue-local-validation.md)和[评估场景](docs/character/dialogue-evaluation/README.md)。

## 语音朗读

### Fish 云端语音

使用[可露希尔社区音源](https://fish.audio/m/4632f51d7f524e6da1952135e498a6a3)，无需下载模型权重或准备本地参考录音。需要自己的 Fish API key；账号权限、限额和计费以服务商为准，应用不会自动更换型号。

在项目根目录复制配置模板。

```bash
cp -n config/fish-voice.example.properties config/local-fish-voice.properties
chmod 600 config/local-fish-voice.properties
```

只在本机编辑 `config/local-fish-voice.properties`，填写 `fish.apiKey`。默认配置为：

```properties
fish.model=s2.1-pro-free
fish.referenceId=4632f51d7f524e6da1952135e498a6a3
voice.autoSpeak=true
```

`s2.1-pro-free` 是完整型号名。配置完成后退出已有桌宠，再用下方脚本启动。这个入口为当前进程选择 Fish，默认使用 `s2.1-pro-free`，并关闭本地语音服务自动启动。

```bash
bash deploy/tts/start-fish-preview.sh
```

可通过 `CLOSURE_FISH_MODEL=drama-3` 显式选择 Drama3。型号、环境变量、错误处理和本地语音入口见 [Fish 接入说明](deploy/tts/FISH.md)。

Fish 路径保留内置开场录音，后台闲聊、天气和提醒只显示文字；主动聊天及未缓存的固定触碰台词才触发云端合成。六条固定触碰台词首次成功生成后保存在被 Git 忽略的 `.runtime/fish-voice/fixed-lines/`，之后复用同一份音频。普通聊天仍实时合成，不写入这个缓存；相同文字重新合成时可能有不同读法。

### GPT-SoVITS 与本地 Qwen

本地语音路径继续保留。公开模板在 [voice.properties.example](src/main/resources/voice.properties.example)，本地服务安装与部署见 [TTS 说明](deploy/tts/README.md)。

```bash
cp -n src/main/resources/voice.properties.example config/local-voice.properties
```

GPT-SoVITS 需要配置服务地址、权重路径和参考音频／文本；服务自动启动可通过 `gptSovits.server.*` 配置。切换本地模型需使用对应配置与服务，不能只修改 Fish 的型号字符串。

## 天气与日常提醒

- **天气**：macOS 默认通过系统定位获取当前位置，再向 Open-Meteo 查询天气；只在启动时查询，不持续跟踪或保存定位历史。定位不可用时明确提示，不继续播报旧城市天气。
- **工作休息**：右键 `定时提醒...` 可开启、暂停、重置及调整工作提醒间隔，也可添加当天定时事项。
- **课程与 Deadline**：右键 `课程表提醒...` 导入 `.csv`、`.txt`、`.tsv`、`.ics`、`.ical` 或 `.xlsx`。默认上课前 15 分钟、Deadline 前 24 小时提醒，课程保存在 `~/.closure/course-schedule.csv`。

macOS 定位组件需先安装一次（需要 Xcode Command Line Tools 的 Swift 编译器）：

```bash
bash deploy/location/install-macos-location.sh
```

如需固定地点，在 `config/local-weather.properties` 中设置 `weather.locationMode=fixed`、地点名及经纬度；`auto` 恢复系统定位。其他平台可使用 `fixed`。配置模板见 [天气示例](config/local-weather.example.properties)。

课程表先由本地解析器处理；格式无法识别且 AI 配置可用时，文件内容会发送给所选 AI 服务转换，再校验保存。聊天会发送输入与选中的近期上下文；Fish 合成会发送需要朗读的文本。真实密钥、本地路径、对话、日程与运行缓存均不应提交到仓库。

## 开发与验证

```bash
./mvnw -B -Djava.awt.headless=true test
./mvnw -B -DskipTests package
python3 scripts/verify_startup.py
```

启动检查需要图形环境，验证实际透明窗口与人物资源，跳过 AI、TTS、天气、提醒和声音服务。CI 使用离线测试与模拟服务，不消费真实模型额度，也不能代替回复效果和听感验收。

```text
src/main/java/app/ai          模型客户端、请求与配置
src/main/java/app/character   人物状态、动作调度与 Spine 渲染
src/main/java/app/service     对话、上下文、朗读与服务编排
src/main/java/app/ui          桌宠、聊天与提醒窗口
src/main/java/app/voice       本地与云端语音适配
src/main/java/app/reminder    课程、Deadline 和定时提醒
src/main/java/app/weather     定位天气与启动问候
src/main/resources           人物资源、动画、音频与提示词
docs                         配置、设计、评估与开发记录
```

开发约定见 [CLAUDE.md](CLAUDE.md) 和 [自动化指南](docs/ai/automation.md)。当前由 Codex 直接实现与复核；具体任务通过版本化 Spec、检查与 PR 记录进度。

## 后续方向

- 持续评估文本角色感、技术回答质量、语音一致性和响应时间。
- 在近期会话基础上设计可控的长期记忆与摘要。
- 增加独立创作的动作、情绪反馈及更细的口型同步。
- 完善课程表编辑、删除、重复规则与自然语言提醒。
- 接入开发环境上下文、Maven／Gradle／JUnit 失败分析及代码定位；代码修改需用户授权。
- 打包为可安装应用，减少首次配置步骤。

## 资源与声明

本项目为非官方、非商业的同人学习与技术研究项目，与《明日方舟》官方及鹰角网络无关联。角色形象、美术与语音相关权利归各自权利方，使用社区音源不等于取得其商业授权。

Spine 资源来自固定版本的 [Ark-Models](https://github.com/isHarryh/Ark-Models/tree/b799ffe8ee30bbb1b04f3c551234edfab8d5549a/models/4228_closur)，运行时为 Spine 3.8。素材来源、原始声明和运行时许可链接见 [Spine 说明](docs/character/spine-upgrade.md)。当前 Spine Editor 许可证持有状态尚未确认，使用与分发需按对应许可处理；本仓库不把预览通过视为许可已解决。
