# Closure · 可露希尔 AI 桌面助手

> 「博士，你的终端好像又冒红光了哦？」

一个基于 **Java 21、JavaFX 和 Swing** 的非官方同人桌面项目，以《明日方舟》的可露希尔为角色原型，结合 Spine 动画、AI 对话、语音朗读和日常提醒。

目前重点是让人物动作、聊天与语音配合自然。开发环境感知、构建报错分析和代码修改仍属于后续目标。

## 当前进度

**更新于 2026-11-28。** 主分支已包含多轮对话、Fish 语音、时段天气启动问候、工作台 API 余额查询和新版桌面动作，可从本页的启动入口使用。

| 能力 | 已实现的内容 |
| --- | --- |
| 桌面窗口 | 透明、无边框、置顶、鼠标拖动、双击打开通讯窗口 |
| 新版人物 | 十二段透明动画、待机／思考／说话／情绪与互动，高分屏绘制、分页缓存、本地导入 |
| AI 对话 | 兼容 OpenAI Chat Completions、流式显示、独立角色卡、近期多轮问答、停止生成与新对话 |
| Fish 云端语音 | 使用可露希尔社区音源，启用 Fish 时默认选择 `s2.1-pro-free`，可显式选择 `drama-3` |
| 固定触碰语音 | 六条固定台词首次成功生成后缓存，相同台词复用同一份音频，重启后继续使用 |
| 本地语音 | GPT-SoVITS／本地 Qwen 接口、自动朗读、语音服务启动与错误提示 |
| 日常动作 | 思考进出场、TTS 朗读时说话、互动后恢复状态、两分钟长待机后暂停闲聊 |
| 拖动交互 | 普通拖动、整幅图像轻微倾斜与松手回弹，右拖朝左、左拖朝右 |
| 天气与提醒 | macOS 当前定位天气、工作休息提醒、课程与 Deadline 导入、当天定时事项 |

## 快速开始

需要 **JDK 21**。项目使用 Maven Wrapper，无需另外安装 Maven。运行完整桌宠需要图形桌面环境；AI 与语音需分别配置。

### 运行桌宠

```bash
git clone https://github.com/lyclccylcq/Closure.git
cd Closure
./mvnw javafx:run
```

首次使用新版人物，请先按[本机模型导入说明](docs/character/new-model.md)导入资源：`python3 scripts/import_pet_model.py "/本机资源包/assets/video"`。角色动画保存在本机，不随公开仓库分发。

普通 Maven 启动读取本机的 AI 和语音配置。使用 Fish 朗读请配置 API key 后运行对应脚本。[Fish 配置与启动](#fish-云端语音)

macOS 也可以双击 `scripts/launch-desktop.command`。启动时只显示桌宠模型；右键模型，选择「工作台」后才打开工作台。已有本地配置位于其他目录时，可运行 `scripts/launch-desktop.command --runtime-directory "/原运行目录"`。它从当前工程构建应用，并在指定目录读取本机配置。`--pet-only` 保留兼容，启动脚本不提供自动打开工作台的选项。

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

- **默认新版**：待机、思考、说话、开心、庆祝、难过和两种点击互动。右键「动作」可手动触发；思考包含进出场，说话随实际 TTS 开始与停止恢复。
- **按住与拖动**：保留抓取位置和方向翻转，提供整幅图像的轻微倾斜与回弹。拖动不会误触固定台词。
- **安静待机**：长时间未交互时暂停闲聊，仍使用待机画面。新版没有行走或睡眠素材，因此不提供行走和自主走动。
- **旧版保存**：完整旧版保存在 `codex/legacy-spine-pet-20261127` 分支，当前代码也保留显式 Spine 兼容入口。

[新版素材、安装、动作与验证](docs/character/new-model.md) · [旧版骨骼行为](docs/character-behavior.md)

## 工作台

右键桌宠，选择「工作台」。从左侧新建会话或打开本地项目。标题栏的「侧栏」和「项目文件」可以分别展开或收起对应区域。项目面板在本机只读预览 UTF-8 文本，收起后会保留当前文件。工作台关窗重开可继续当前进程中的会话。

打开工作台后，可露的实时模型显示在会话区右下角，桌面模型隐藏。她继续随思考、朗读和点击互动切换动作，输入框和发送按钮为模型留出独立位置。关闭或最小化工作台、或从内置模型右键选择「回到桌面」，会恢复桌面模型。

工作台使用深靛色和像素美术。新会话居中输入，开始对话后输入框移到窗口下方。Enter 发送，Shift+Enter 换行。[主题素材来源与授权](src/main/resources/ui/workbench/NOTICE.md)

聊天和中文界面随应用分发 Noto Sans SC 常规、粗体，避免依赖系统字体集合的字形回退。英语标志保留主题字体。聊天记录与输入框随窗口扩宽，短消息气泡按内容收缩。[字体来源与许可](src/main/resources/ui/fonts/source-manifest.json)

正式启动时，工作台使用运行目录中的 AI 配置，并共享桌宠已有的朗读服务。当前会话回复完成后朗读 `speechText`。切换会话或隐藏工作台会停止该会话的语音，后台完成的回复保留文字。每个会话的「停止」只取消自己正在持有的语音。

点击工作台右上角「API 账户」查询当前模型连接的账户数据，可露会播报结果；「查询并播报」刷新，「再报一次」重播。结果列出平台、币种或额度单位和查询时间。不查询 Fish 语音余额，不把账户数据交给聊天模型，也不在应用启动时自动查询。

OpenAI 官方连接使用独立 **Admin API key**。首次查询会显示管理密钥输入框：在本地粘贴后点「保存并查询」，或点击「管理密钥」修改/清除。macOS 会保存到**系统钥匙串**，退出后保留，下次打开账户工具自动读取；输入以密码形式显示，提交后立即清空，已保存的密钥不回填。保存、读取与清除在后台完成，钥匙串不可用时会明确提示，不降级为明文文件。非 macOS 当前保留会话模式，界面会标注。

密钥读取顺序为当前会话、已保存的钥匙串值、`CLOSURE_OPENAI_ADMIN_KEY` 启动环境变量。点击「清除管理密钥」会删除保存的凭据并停止本次运行回退环境变量；外部设置的环境变量仍由启动环境管理。普通退出只清内存，不删除钥匙串条目，也不覆盖普通对话 key。密钥通过私有标准输入传给系统钥匙串工具，不写入配置、余额记录、临时脚本、命令参数或日志，也不送入聊天/语音。升级前仅在内存中的密钥需在本地再保存一次；不要把密钥发送到聊天或提交到仓库。

OpenAI 日常只显示和播报**今日消耗**，金额保留一位小数，内部计算仍保留精度。消费按所配置 Admin key 的**组织**汇总，包含其他应用的调用；日期以 UTC 划分，官方数据可能延迟。不再朗读月统计、tokens、请求数和整段说明。查询只读取官方 Costs 接口；管理密钥不会用于对话、TTS 或兼容中转。

充值后，在面板的「OpenAI 账单」确认官方当前余额，填入美元余额输入框并点击「同步余额」。应用记录这次余额和当时的消费基准，后续按新增美元消耗计算**估算余额**，跨日期和月份继续跟踪。充值、退款、赠款或额度过期后可再次同步校准；官方消费延迟也可能造成偏差，这份估算不代表官方实时余额。未同步时只报消耗，不猜余额。

估算余额低于 5 美元时，可露会在消费短报后追加“采购费不足”等台词；耗尽时使用不同提醒。判断使用未舍入数值，其他币种和令牌额度不会套用美元阈值。这里只在用户查询/重播时汇报，不自动充值或后台轮询。

同步记录保存在本机 `~/.closure/billing/openai-funds.json`，只含余额、消费基准、时间和不可逆账户指纹，不保存密钥。重启后沿用相同管理密钥即可继续估算；更换密钥需重新同步。「清除同步」删除当前余额记录，与删除钥匙串凭据分别管理。

[创建 Admin API key](https://platform.openai.com/settings/organization/admin-keys)需相应组织权限；接口参考：[Costs](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/costs)、[Completions usage](https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage/methods/completions)、[Administration](https://developers.openai.com/api/reference/administration/overview)。

当前支持 DeepSeek 官方余额接口、SiliconFlow 账户接口及 New API / One API 兼容额度接口。平台识别依据当前 API 地址和同一站点的实际接口响应，不能仅凭 token 格式识别。令牌额度与账户余额分别标明；令牌未设上限不表示账户资金无限，未提供币种时保留原始额度单位。未开放余额接口、权限不足或网络失败会明确提示，不能把查询失败当作零余额。

协议参考：[DeepSeek 余额](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)、[New API 令牌额度](https://doc.newapi.pro/en/api/token-usage/)、[New API 兼容接口](https://github.com/QuantumNous/new-api/blob/main/controller/billing.go)、[SiliconFlow 账户信息](https://github.com/siliconflow/siliconcloud/blob/main/openapi.yaml)。

工作台的可露形象支持本地透明动画。安装 FFmpeg 后，使用 `python3 scripts/import_workbench_mascot.py "/本机资源包/assets/video/idle.webm"` 导入。动画存放于 `~/.closure/assets/workbench/`，随工作台显示播放、隐藏暂停。桌宠右键的「工作台」可直接进入，菜单与工作台共用深靛配色。

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

Fish 路径会朗读一次时段天气启动问候，替换旧固定开场录音；后台闲聊和提醒仍只显示文字。主动聊天、余额汇报及未缓存的固定触碰台词使用当前语音服务。六条固定触碰台词首次成功生成后保存在被 Git 忽略的 `.runtime/fish-voice/fixed-lines/`，之后复用同一份音频。普通聊天、天气问候与余额汇报实时合成，不写入固定台词缓存；相同文字重新合成时可能有不同读法。

### GPT-SoVITS 与本地 Qwen

本地语音路径继续保留。公开模板在 [voice.properties.example](src/main/resources/voice.properties.example)，本地服务安装与部署见 [TTS 说明](deploy/tts/README.md)。

```bash
cp -n src/main/resources/voice.properties.example config/local-voice.properties
```

GPT-SoVITS 需要配置服务地址、权重路径和参考音频／文本；服务自动启动可通过 `gptSovits.server.*` 配置。切换本地模型需使用对应配置与服务，不能只修改 Fish 的型号字符串。

## 天气与日常提醒

- **天气**：macOS 默认通过系统定位获取当前位置，再向 Open-Meteo 查询当天预报；只在启动时查询，不持续跟踪或保存定位历史。启动时按系统本地时间问候，简报天气和当天温度范围，接一句关心，不念地名。定位不可用时明确说明天气未取得，不继续播报旧城市天气。静音时不朗读；天气回来前若已发起其他语音，启动问候不再打断它。
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
