# 可露希尔 · AI 桌面工程助手

> 「博士，你的终端好像又冒红光了哦？」
> 「别急别急，让可露希尔大人来看看。当然，维修费我们之后再谈。」

一个基于 **Java 21 / JavaFX / Swing** 开发的 AI 桌面陪伴与编程辅助项目。

本项目以《明日方舟》中的可露希尔为角色原型。现在的目标不是只做一个会动的桌宠，而是让她逐步变成常驻桌面的工程助手：能聊天、能朗读、能提醒课程和 Deadline，也能在之后观察开发环境、分析报错、给出修复建议。

---

## 当前进度

截至 2026-08-02，已经完成或接入的能力：

- 透明桌宠窗口：支持无边框显示、置顶、拖动、双击打开聊天窗口。
- 角色资源：加载可露希尔图片与开场动画帧，启动时播放提示音。
- 聊天窗口：支持博士与可露希尔的对话气泡、头像、流式回复显示。
- AI 接入：支持 OpenAI-compatible Chat Completions，本地配置缺失时只提示配置不可用，不再使用聊天假回复冒充 AI。
- Prompt 环境信息：每次请求都会附带当前日期、时间、时区、南半球季节和当前时段，方便理解“今天、明天、今晚、现在”等相对表达。
- GPT-SoVITS 朗读：支持本地 GPT-SoVITS HTTP API、多个情绪 VoiceProfile、自动朗读开关和失败降级提示。
- 语音模型自启动：桌宠启动时可以按本地配置拉起 GPT-SoVITS 后台服务；如果服务已经可连接，会跳过重复启动。
- 朗读策略：聊天长回复只朗读简短版本；桌宠气泡显示的文字会完整朗读，避免气泡显示一整句但语音只念一半。
- 天气问候：启动提示音结束并且语音模型暖机完成后，根据当前时间和堪培拉天气生成关心口吻的问候，例如提醒加衣、带伞或防晒。
- 工作休息提醒：右键菜单可以开启、暂停、重置和设置工作提醒间隔。
- 课程表提醒：支持上传课程表并保存到本地，在上课或 Deadline 前通过桌宠气泡提醒。
- 闲置随机气泡：过去用于聊天的假回复已移到桌宠闲置气泡里，桌面空闲时偶尔有声显示，不再进入聊天回复。
- 单元测试：课程表解析、提醒、天气问候、GPT-SoVITS 客户端、语音控制、Prompt 环境信息等核心逻辑已有测试覆盖。

---

## 快速启动

需要 Java 21。

```bash
./mvnw javafx:run
```

运行测试：

```bash
./mvnw test
```

如果本机有多个 JDK，可以显式指定 Java 21：

```bash
JAVA_HOME=/path/to/jdk-21 ./mvnw javafx:run
JAVA_HOME=/path/to/jdk-21 ./mvnw test
```

---

## 本地配置

真实密钥和本地路径不要提交到仓库。下面这些文件都已加入 `.gitignore`：

- `config/local-ai.properties`
- `config/local-voice.properties`
- `config/local-weather.properties`

### AI 聊天

复制示例文件：

```bash
cp config/local-ai.example.properties config/local-ai.properties
```

填写：

```properties
CLOSURE_AI_ENDPOINT=https://api.openai.com/v1/chat/completions
CLOSURE_AI_API_KEY=replace-with-your-api-key
CLOSURE_AI_MODEL=gpt-4.1-mini
```

也可以使用其他兼容 OpenAI Chat Completions 的服务端点。

### 语音朗读

语音配置示例在：

```text
src/main/resources/voice.properties.example
```

复制为：

```bash
cp src/main/resources/voice.properties.example config/local-voice.properties
```

至少需要填写：

```properties
gptSovits.baseUrl=http://127.0.0.1:9880
gptSovits.gptWeightPath=<absolute-path-to-gpt-ckpt>
gptSovits.sovitsWeightPath=<absolute-path-to-sovits-pth>
voice.autoSpeak=true

gptSovits.profile.neutral.refAudioPath=<absolute-path-to-neutral-wav>
gptSovits.profile.neutral.promptText=<exact-neutral-reference-line>
gptSovits.profile.neutral.promptLang=zh
```

当前可配置的情绪 Profile 包括：

- `neutral`
- `question`
- `cheerful`
- `teasing`
- `serious`

如果要让桌宠启动时自动拉起 GPT-SoVITS API，可以添加：

```properties
gptSovits.server.command=/absolute-path-to/start-gpt-sovits-api.sh
gptSovits.server.workingDir=/absolute-path-to/GPT-SoVITS
gptSovits.server.autoStart=true
```

如果启动命令需要激活 conda、venv 或设置复杂环境变量，推荐写成一个独立脚本，再把脚本路径填到 `gptSovits.server.command`。

### 天气问候

默认按堪培拉配置。需要修改城市时，复制：

```bash
cp config/local-weather.example.properties config/local-weather.properties
```

示例：

```properties
weather.locationName=Canberra
weather.latitude=-35.2809
weather.longitude=149.1300
```

天气模块使用当天概况生成短提醒，不会把湿度、风速等详细数据堆到气泡里。启动问候会等待语音模型暖机完成后再显示和朗读。

---

## 课程表提醒

右键桌宠，选择 `课程表提醒...`，可以上传课程表或触发测试提醒。

导入后课程表会保存到：

```text
~/.closure/course-schedule.csv
```

支持的文件类型：

- `.csv`
- `.txt`
- `.tsv`
- `.ics`

CSV / TSV 推荐字段：

```csv
title,start,end,type,remindBeforeMinutes,location,notes
COMP6710 Lecture,2026-08-05 09:00,2026-08-05 11:00,class,20,Room A,
Assignment 1,2026-08-09 23:59,,deadline,1440,,Submit before midnight
```

简单文本也可以用逗号、Tab 或 `|` 分隔：

```text
2026-08-05 09:00,COMP6710 Lecture,class,20,Room A
2026-08-09 23:59,Assignment 1,deadline,1440
```

默认提醒时间：

- 上课：提前 20 分钟
- Deadline：提前 24 小时

---

## 对话与朗读策略

可露希尔的系统 Prompt 要求模型输出一个 JSON 对象：

```json
{
  "displayText": "聊天窗口显示的完整自然回复",
  "speechText": "适合自然朗读的简短口语版本",
  "dominantEmotion": "NEUTRAL"
}
```

设计原则：

- `displayText` 给聊天窗口完整显示。
- `speechText` 给 TTS 朗读，长技术说明只念摘要。
- 桌宠气泡不是聊天长文，气泡里显示多少就朗读多少。
- Markdown、链接、代码块等会在送入 TTS 前清洗，避免把格式符号念出来。

---

## 项目结构

```text
src/main/java/app/ai              OpenAI-compatible AI 客户端与配置
src/main/java/app/chat            聊天消息模型
src/main/java/app/reminder        工作提醒与课程表提醒逻辑
src/main/java/app/service         聊天服务、Prompt 环境信息、朗读控制
src/main/java/app/ui              桌宠窗口、聊天窗口、提醒窗口
src/main/java/app/voice           GPT-SoVITS 配置、客户端、服务自启动
src/main/java/app/weather         天气配置、Open-Meteo 客户端、启动问候
src/main/resources                角色图片、动画帧、音频、系统 Prompt
src/test/java                     单元测试
config                            本地配置示例
```

---

## 开发路线

已经完成：

- [x] 透明桌宠窗口
- [x] 鼠标拖动角色
- [x] 双击打开聊天窗口
- [x] 基础聊天界面
- [x] 博士与可露希尔头像和名称
- [x] 流式 AI 回复显示
- [x] OpenAI-compatible 模型接入
- [x] 移除聊天假回复
- [x] 闲置随机有声气泡
- [x] 当前日期、时间、时区、季节、时段注入 Prompt
- [x] GPT-SoVITS 自动朗读
- [x] 情绪 VoiceProfile
- [x] 桌宠启动时自动拉起语音模型
- [x] 启动天气问候
- [x] 工作休息提醒
- [x] 课程表上传、保存和提醒
- [x] 课程表测试提醒
- [x] 单元测试覆盖核心非 UI 逻辑

接下来可以继续做：

- [ ] 完善待机、思考、说话、警告动画状态机
- [ ] 让朗读时角色动作和口型/状态更同步
- [ ] 支持更完整的课程表编辑、删除和重复课程规则
- [ ] 支持自然语言添加临时提醒
- [ ] 捕获 Java 子进程输出
- [ ] 识别 Maven / Gradle 构建失败
- [ ] 解析 Java 编译错误和异常堆栈
- [ ] 自动定位文件与代码行
- [ ] 生成错误原因和解决建议
- [ ] 支持 JUnit 测试失败分析
- [ ] 读取必要的项目上下文
- [ ] 在用户确认后应用代码修改
- [ ] 打包为可安装桌面应用

---

## 项目目标

这个项目最终希望实现的是：

```text
二次元桌面角色
+
开发环境感知
+
构建日志解析
+
代码上下文读取
+
大语言模型分析
+
主动式编程辅助
```

当代码正常运行时，她是桌面上的陪伴角色。

当程序编译失败时，她是罗德岛工程部派来的技术专家。

当博士快把项目删掉重来时，她应该能及时拦住：

```text
“先停一下。真正炸掉的可能只有第一条报错。”
“后面那一串红字，多半只是跟着起哄。”
```

---

## 声明

本项目为非官方同人学习项目，主要用于 Java、JavaFX、AI Agent 和桌面应用开发相关技术研究与作品展示。

《明日方舟》、可露希尔角色形象及相关美术资源的版权归其原始权利方所有。

本项目与《明日方舟》官方及鹰角网络无任何关联，也不用于商业用途。

<p align="center">
  <b>罗德岛工程部正在施工中。</b>
</p>
