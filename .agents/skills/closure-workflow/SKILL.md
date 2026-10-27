---
name: closure-workflow
description: 自动调度 Closure 开发任务：Codex 规划 Spec，调用本机 Claude Code 实现，收回测试证据并按 Spec 批量复核，修复后准备用户审批的 PR。用于功能、修复和重构；只读问答和用户仅要求设计时不启动开发。
---

让用户始终留在 Codex 聊天中，由当前 Codex 负责设计和复核，由本机已登录的 Claude Code 负责实现。[自动化指南](../../../docs/ai/automation.md) 提供命令、运行状态和边界；[项目约束](../../../CLAUDE.md) 适用于两端。

## 规划与启动

- 先确认任务是执行请求。用户要求实现/修复即授权需求范围内的设计和自动交接，不对常规实现选择重复索要批准；仅方案讨论或重大未决业务选择时保持 DRAFT。
- 从 `docs/ai/templates/task.spec.json` 创建未使用任务号的 Spec。明确 AC、允许路径、非目标和检查命令；已有任务设计变更必须升版。准备实施时设为 READY，记录实际授权背景，不伪造人类确认。
- 检查 Git 状态和基线，保留未提交工作。调度器只从已提交基线创建独立副本；任务若依赖原目录未提交内容，先解决基线问题，不静默丢弃这些上下文。
- 读取 `scripts/ai_dispatch_config.json`，确保必要检查是受信任命令映射中的精确命令。不要为了运行任意 Spec 字符串而新增 shell 或放宽权限。
- 运行 `python3 scripts/ai_dispatch.py doctor`，然后主动运行以下命令，不把命令交给用户代执行：

```sh
python3 scripts/ai_dispatch.py start --task docs/ai/tasks/TASK-NNNN.json --base origin/main
```

按实际任务替换路径。需要更新基线时先 fetch；不重置原工作区。长任务持续等待进程和简洁状态，按需向用户说明实质进展。不要反复把完整 Claude 会话读入 GPT。

## 收回与批量复核

- 调度器使用文件工具调用 Claude，负责执行受信任检查、自测失败重试、Git 提交和交接包。命令返回 `AWAITING_REVIEW` 时，读取返回的 Spec、report、manifest、短摘要和完整任务 Diff；需要时按准确 head 读取上下文。
- 按 `docs/ai/prompts/review.md` 对所有 AC 做一次独立复核，使用 `docs/ai/templates/gpt-review.json` 写入真实的 Codex 复核记录。计算精确 manifest 哈希，逐项记录证据；不能仅复述 Claude 的 PASS，也不能用脚本生成假结论。
- 主动执行 `python3 scripts/ai_dispatch.py review --run <run_dir> --review <review.json>`。REVISE 会自动把完整问题批次交给同一 Claude 会话，重新检查、提交并生成新包；继续对新稳定批次复核。BLOCKED 或达到上限时集中报告所需决定/权限，不无限重试。
- head、base、Spec 或证据发生变化时，旧 PASS 失效。脚本不会自己请求 GPT；当前聊天中的 Codex 必须实际阅读证据并生成每轮结论。

## PR 与最终交付

- 真实 PASS 后主动执行 `python3 scripts/ai_dispatch.py publish --run <run_dir>`，准备或更新草稿 PR，不要求用户搬运材料。检查返回 URL 和当前 head，对创建的 PR 调用可用的 `attach_artifact`。
- 等待当前提交的 CI。失败时先读实际诊断，将绑定当前 head 的反馈文件交给 `repair --run <run_dir> --feedback <file>`，自动成批修复并重新复核；不要直接沿用旧 PASS。反馈格式见自动化指南。
- CI 通过后向用户给出 PR、受审提交、AC/检查概况和必要风险，等待用户最终合并。任何自动推送/PR 操作都应在用户开发任务授权范围内；外部部署、发消息或合并不由该 Skill 自动授权。
- 这是当前活跃 Codex 任务中的自动调度，持续工作至交付或实际阻塞；不是在聊天结束后自行唤醒的后台服务。
