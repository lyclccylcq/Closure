# GPT → Claude Code → GPT 开发闭环

在已同步流程文件的 Closure 副本中，直接向 Codex 提开发需求即可。Codex 规划、调度和按 Spec 复核，本机 Claude Code 实现；宿主负责自测、CI、按授权合并和可运行交付。用户只验收最终项目，不来回搬运材料或逐个合并 PR。

用户于 2026-10-28 明确授权代理运行 CI 和合并符合检查的 PR。这是当前 Closure 工程流程的授权，不等于用户已验收产品，也不授权范围外部署、费用或破坏性操作。历史 TASK-0001 / TASK-0002 保留当时设计，当前入口和完成策略已取代其中的旧人工合并步骤。

## 入口与证据

| 文件 | 用途 |
| --- | --- |
| `AGENTS.md` / `.agents/skills/closure-workflow/` | Codex 默认入口 |
| `CLAUDE.md` / `.claude/skills/implement-task/` | 项目约束与明确选择时的 Claude 手动入口 |
| `scripts/ai_dispatch.py` / `ai_dispatch_config.json` | 调度、可信命令、CI、授权合并与交付 |
| [automation.md](automation.md) | 完整命令、状态、权限和恢复 |
| `templates/task.spec.json` / `tasks/` | 版本化 Spec 与任务历史 |
| `prompts/design.md` / `review.md` | 两轮 GPT Prompt |
| `templates/implementation-report.json` / `gpt-review.json` | 实施和技术复核证据 |
| `templates/pr-acceptance-report.md` | PR、合并与交付报告 |
| `scripts/ai_workflow.py` | Spec、范围、完整 Diff 和哈希校验 |
| `scripts/verify_startup.py` | 实际窗口/模型的离线启动检查 |

## 默认流程

1. Codex 为执行请求生成唯一 Spec，明确 AC、范围、检查、风险和交付标准。仅设计请求保持 DRAFT；普通执行选择不重复确认。
2. 宿主从已提交基线创建独立副本、冻结 Spec 和检查配置，调用本机已登录 Claude 实现。原目录未提交工作保留原样。
3. 宿主运行可信检查；失败先自动成批修复。稳定后生成绑定 head/base、Spec、报告和完整 Diff 的 packet。
4. 当前 Codex 一次集中复核全部 AC，写真实 PASS / REVISE / BLOCKED。REVISE 自动回同一 Claude 会话，再自测和复核。
5. PASS 后发布草稿 PR 并主动运行 finalize。核对当前提交、等待必要 CI，转 ready 后等待新检查实例，再按授权合并精确受审提交。
6. 核验实际合并提交的 main CI，在干净交付副本运行测试、构建和离线启动。COMPLETE 后交用户验收可运行项目；失败继续修复，不用“CI 绿灯”代替启动证据。

一个任务一个 Spec、分支和 PR。GPT 只复核稳定批次，不反复读取整仓库、历史聊天或原始 Claude 会话；完整 Diff 超预算时明确阻塞，不截断后声称全面复核。

## 正常使用与验证

```text
按 Closure 自动工作流实现这个需求：……
```

以下命令通常由 Codex 调用；从仓库根目录运行，任务号使用新的唯一编号：

```sh
python3 scripts/ai_dispatch.py doctor
python3 scripts/ai_dispatch.py start --task docs/ai/tasks/TASK-NNNN.json --base origin/main
python3 scripts/ai_dispatch.py review --run <RUN_DIR> --review <REVIEW_JSON>
python3 scripts/ai_dispatch.py publish --run <RUN_DIR>
python3 scripts/ai_dispatch.py finalize --run <RUN_DIR>
```

正常启动为 `./mvnw javafx:run`，需要 JDK 21。`python3 scripts/verify_startup.py` 核验实际桌面窗口和模型，但跳过 AI、天气、TTS、提醒与音频，不声称覆盖这些服务集成。默认交付检查还包含 Java 测试与 package 构建，具体以冻结的完成策略为准。

## 手动交接（仅明确选择时）

手动模式共享同一 Spec、实施报告、packet 和技术审查格式。用户可在 Claude 中调用 `/implement-task <Spec路径>`，由 Codex 核对真实证据并按已授权策略完成 CI 和合并；只有用户明确要求人工合并时才保留逐 PR 人工步骤。不要自动把材料搬运或普通工程工作交回用户。

## 权限与完成条件

可信 `completion_policy` 必须记录实际合并授权；缺少该授权的旧配置不会合并。模型 PASS 只是技术结论，不能证明模型身份或用户验收。代理不伪造 APPROVE，不使用 admin 或强推绕过远端规则。

CI 和合并后检查并不要求模型 API Key；本地 Claude 调用使用现有登录。safe-mode 加文件工具限定不是 OS 或目录读取隔离，仍须信任上下文、保留正常权限并排除敏感资料。自动调度仅在当前活跃 Codex 任务内运行，不在结束后后台唤醒。

原本地 checkout 可能包含不同基线及未提交业务工作，不能自动覆盖、重置或混入。使用独立干净副本完成交付。GitHub 分支保护独立于脚本策略；遵守实际远端规则，不将新增 CI 等同于已设置服务器强制门禁。

维护协议时同步规范、Skill、Prompt、检查脚本和 CI，运行工具测试及实际交付检查。官方参考见 [自动化指南](automation.md#官方参考)。
