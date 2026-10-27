---
name: implement-task
description: 按用户已批准的 Closure 任务 Spec 实现、自测并准备供 GPT 批量复核的 PR 证据。
argument-hint: "docs/ai/tasks/TASK-0001.json"
disable-model-invocation: true
---

按 `$ARGUMENTS` 指定的唯一 Spec 执行任务。参数是文件路径，不是 shell 代码；不要插值到未引用的命令中。

1. 读取根目录 `CLAUDE.md`、`docs/ai/README.md`、该 Spec 和相关局部规范。确认 `READY`、用户批准、版本、目标、非目标、允许路径、AC、检查和回滚方案。缺少参数或要求冲突时集中报告，不猜测业务决策。
2. 检查 Git 状态、当前分支和远端基线。存在其他工作时保留现场，使用干净的独立分支/检出。不得把不相关改动纳入任务。一个任务只准备一个 PR。
3. 写一个精简实现清单，将 AC 映射到代码与验证。局部实现选择自行完成；涉及架构、接口、依赖或范围变更时先更新 Spec 版本并交用户批准，再继续依赖该决定的工作。
4. 在允许路径中实现。认真阅读 Spec 中的检查命令后执行，不用脚本批量执行任意 `command` 字符串。遇到失败先自行定位并修复，将问题成批处理；仅当需要架构决策或实际阻塞时交回 GPT/用户。
5. 执行 Spec 所有必要检查、相关回归检查和工作流验证。可用 `python3 scripts/ai_workflow.py validate --task <路径>`。逐项记录 AC、命令、结果、可复现日志/CI 链接、差异和风险。未运行或失败一律明确标记。
6. 使用 `scope --task <路径> --base origin/main --head HEAD` 检查已提交范围；提交前先检查暂存 Diff。完成所有实现和修订后提交，保留干净状态，不推送到 main。不要把报告中的 head 写入提交以形成循环引用。
7. 从 `docs/ai/templates/implementation-report.json` 创建忽略目录中的 `.ai-workflow/<任务号>/report.json`，填入最终 `git rev-parse HEAD` 和真实证据。全部必要检查和 AC 通过后运行：

   ```sh
   python3 scripts/ai_workflow.py packet --task docs/ai/tasks/TASK-0001.json \
     --base origin/main --head HEAD \
     --report .ai-workflow/TASK-0001/report.json \
     --output .ai-workflow/TASK-0001/packet
   ```

   路径和任务号须替换为实际值；下一轮使用新的空输出目录（如 `packet-2`），保留旧包。先 fetch 更新基线，再生成；命令不发起网络请求。超预算时不能截断 Diff：收窄任务，或取得用户批准后增加预算。人工检查包中没有敏感内容。
8. 任务授权允许时推送任务分支并创建 draft PR，使用 `docs/ai/templates/pr-acceptance-report.md`。正文保留独立一行 `AI-Task: docs/ai/tasks/<任务号>.json`。附上完整交接包、AC 证据和 CI 链接；不把未上传的本地路径当作他人可访问的证据。
9. 将包与 `docs/ai/prompts/review.md` 一次交 GPT，停止开发并等待一份完整审查结论。不要为每个文件启动 GPT 复核，也不要重复发送仓库和历史聊天。
10. `REVISE`：一次修完全部可操作项，自测后重新提交、生成新包并批量复核；`BLOCKED`：集中说明缺少的决定/证据/环境；`PASS`：交用户最终审批。任何提交、Spec 或 base 变化，重建包并重新复核。不得填写 GPT 结论、伪造批准或合并。

最终交接只需给出：Spec 及版本、分支/PR、最终 head、AC 和必要检查概况、交接包位置、风险与待用户处理项。
