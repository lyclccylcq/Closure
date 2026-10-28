---
name: closure-workflow
description: 自动完成 Closure 开发：Codex 规划并调用 Claude 实现，收回证据批量复核，处理 CI、按授权合并并验证可运行交付。用于功能、修复和重构；仅设计或只读问答不启动开发。
---

让用户始终留在 Codex，由当前会话负责规划、复核和工程交付，Claude Code 负责实现。[自动化指南](../../../docs/ai/automation.md) 说明命令和状态；[项目约束](../../../CLAUDE.md) 适用于两端。用户已授权运行 CI 和合并 PR，只验收最终可运行项目；不逐个索要合并批准。

## 规划与启动

- 先判断是否为执行请求。执行需求授权范围内的常规设计与实现；仅方案讨论保持 DRAFT。重大未决业务选择、范围外或破坏性操作遵守实际授权，不能凭 Skill 扩权。
- 从 `docs/ai/templates/task.spec.json` 创建唯一任务 Spec，明确 AC、允许路径、非目标、检查和可运行交付标准；设计变更升版本。执行条件齐备时设 READY，记录真实授权背景，不伪造人工确认。
- 检查 Git 状态和基线。独立副本不会自动携带原目录未提交内容；任务依赖这些工作时先解决基线问题，不能静默丢弃。
- 阅读可信 `scripts/ai_dispatch_config.json`：检查须精确映射到 argv，完成策略须包含实际合并授权及交付检查。不要为执行任意字符串而放宽配置。
- 主动运行 `doctor` 和 `start`，不把命令交用户代执行：

```sh
python3 scripts/ai_dispatch.py doctor
python3 scripts/ai_dispatch.py start --task docs/ai/tasks/TASK-NNNN.json --base origin/main
```

按实际任务替换路径；需要时先 fetch，不重置原工作区。长任务等待进程与简洁状态，并报告实质进展，不反复把 Claude 完整会话读入 GPT。

## 收回与批量复核

- `AWAITING_REVIEW` 时读取返回的 Spec、report、manifest、短摘要和完整 Diff，按准确 head 补充必要上下文。宿主自测失败先自动成批回传 Claude。
- 按 `docs/ai/prompts/review.md` 实际复核所有 AC，写真实的 `gpt-review.json` 并计算 manifest 原始字节哈希；不能照抄 Claude PASS 或生成假结论。
- 主动执行 `review --run <run_dir> --review <review.json>`。REVISE 自动交回同一 Claude 会话，重新检查、提交和生成包；继续复核新的稳定批次。BLOCKED 时先处理可独立解决的工程问题；只有真实缺口才交用户。
- head/base、Spec 或证据变化使旧 PASS 失效。调度器不调用 GPT API，当前 Codex 必须真正阅读证据并给出每轮结论。

## CI、合并与可运行交付

- 真实 PASS 后运行 `publish --run <run_dir>`，准备/更新草稿 PR，并对返回 URL 调用可用的 `attach_artifact`。
- 接着主动运行 `finalize --run <run_dir>`，不以“请用户合并”结束。可信完成策略验证当前 PASS、head/base 与 PR，等待必要 CI；失败会自动修复并返回 AWAITING_REVIEW，必须重新审查。
- 草稿转为 ready 后，等待新一轮必要检查实例，不能将旧绿灯当作新检查。合并绑定精确受审 SHA，不使用 admin、强推或绕过规则。
- AWAITING_CI / AWAITING_MAIN_CI 表示限时等待未结束，在当前任务继续调用 finalize；不要伪称失败或完成。MERGED 只表示合并，尚不是可运行交付。
- 合并后在返回的干净交付副本验证实际 merge commit 的 main CI、测试、构建和离线窗口启动。DELIVERY_FAILED 时读取实际诊断，修复后续任务并重新走审查流程，不能交付失败版本。
- COMPLETE 后给用户项目位置、启动方式、受审/合并提交、检查概况及必要限制，等待用户产品验收。技术 PASS 和代理合并不冒充用户验收或 GitHub APPROVE。
- 该授权不包含范围外生产部署、费用、破坏性数据操作或对外发消息。自动推进发生在当前活跃任务内，不在聊天结束后自行唤醒。
