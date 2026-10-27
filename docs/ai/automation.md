# Codex 自动调度 Closure 开发

在已有流程文件的 Closure 副本中向 Codex 提需求即可。根 `AGENTS.md` 将相关开发请求导向项目 Skill；Codex 负责规划和复核，本地 Claude Code 负责实现。你不需要切换应用、复制交接 Prompt 或逐条转述修改意见。最终合并仍由你决定。

## 从需求开始

可以直接说：

```text
按 Closure 自动工作流实现这个需求：……
```

Codex 读取现有约束，建立唯一任务 Spec，在本轮会话中启动调度器，并持续推进到可供你审批的 PR 或实际阻塞。不要只生成 Spec 后把启动命令交给用户。

常规需求中的设计、实现和自测由用户的执行请求授权；Codex 不为每个普通实现选择重复请求批准。Spec 的 READY 表示已具备执行条件，不伪造单独的人工批准。产品歧义、范围扩张、不可逆操作或缺少权限时，集中说明所需决定；用户原先明确要求先确认方案时，遵守该要求。

没有本次需求的具体目标时，不自行选择业务功能开工。`TASK-0001` 是初次流程落地，`TASK-0002` 是自动调度升级；新功能使用新的任务号。

## 一次任务如何自动推进

1. Codex 在当前会话生成版本化 Spec，明确范围、AC、检查和回滚；相关上下文按需读取。
2. 调度器从指定的已提交 base 创建独立工作区和任务分支，冻结 Spec 快照。原目录的未提交工作保留原样，不会被自动复制进去。
3. 调度器通过已登录的本地 `claude -p` 启动实现。Claude 读取明确提供的项目约束与任务，只获得文件读取、搜索和编辑工具。
4. 宿主按可信检查配置执行测试，保存原始日志，整理结果并提交。失败时把当前批次问题交回 Claude；不把每个工具调用都发给 GPT。
5. 准备好完整 packet 后，当前 Codex 会话按 Spec 做一次集中复核，保存绑定当前提交与清单哈希的审查 JSON。
6. REVISE 自动触发一批修复、宿主自测和新 packet，再交 Codex 复核；PASS 后发布任务分支和 draft PR；BLOCKED 则停止依赖该条件的工作并报告。
7. Codex 核对 PR 的当前提交和 CI。若 CI 失败，提取绑定当前 head 的必要诊断，自动修复并重新复核。所有条件满足后交用户最终审批，绝不自动合并。

Codex 就是本轮 GPT 规划者和复核者。调度器不再启动递归的 Codex 进程，也不请求 GPT API。Claude 不填写自己的 GPT PASS。

## 调度命令

这些命令主要由 Codex 执行，用户可以用于诊断和恢复。均从仓库根目录运行。

```sh
python3 scripts/ai_dispatch.py doctor
python3 scripts/ai_dispatch.py start \
  --task docs/ai/tasks/TASK-0003.json --base origin/main
```

`start` 默认最多 3 轮 Claude 执行（含修复）、每次最多 30 个 agent turns、单次执行超时 900 秒；可用 `--max-rounds`、`--max-turns`、`--timeout` 调整。增加次数不是解决权限、认证或设计缺口的方法。检查命令及其可信范围须在启动前确定。

命令返回精简 JSON，其中 `run_dir` 是运行目录，`status` 是当前状态；`workspace`、`branch`、`head_sha` 和 `packet_dir` 在相应阶段可用。读取返回路径后使用：

```sh
python3 scripts/ai_dispatch.py status --run <RUN_DIR>
python3 scripts/ai_dispatch.py review \
  --run <RUN_DIR> --review <GPT_REVIEW_JSON>
python3 scripts/ai_dispatch.py repair \
  --run <RUN_DIR> --feedback <CURRENT_HEAD_CI_FEEDBACK_JSON>
python3 scripts/ai_dispatch.py publish --run <RUN_DIR>
```

占位参数须替换为实际路径，不要原样执行。`review` 核验当前 packet 与 GPT 记录的提交、Spec 和哈希绑定；只有有效 PASS 才进入 `READY_TO_PUBLISH`。REVISE 会自动派发下一批修复。`repair` 用于当前提交的 CI 诊断；修复后的提交必须得到新的 Codex 复核。`publish` 只发布通过复核的任务分支并创建 draft PR，返回 PR URL；当前发布目标为 main，受审 base 必须等于最新 origin/main。其他本地 base 可用于隔离实现和复核，发布前须建立正确的 main 基线。

CI 反馈至少包含当前 head 和问题描述，例如：

```json
{"head_sha": "当前完整40位提交SHA", "message": "失败检查及最少必要诊断"}
```

Codex 应自动完成这些调用、读取结果并继续，不要求用户逐条执行。创建 PR 后把 URL 附到当前 Codex 聊天；提交与证据必须对应同一个实际 head。

## 运行记录和精简上下文

运行状态、Claude 输出、测试日志、报告与每轮 packet 保存在控制副本的 `.ai-workflow/runs/TASK-NNNN-<unique>/` 中。目录包含 `state.json`、冻结的 `spec.json` / `config.json`、独立 `worktree/`，以及各 `round-N/` 的日志、报告和 packet。`status` 和命令结果提供精简状态，原始日志只在定位失败时读取相关部分。

Codex 每批复核读取 Spec、实施报告、简短摘要、完整任务 Diff 和必要上下文。完整 Diff 超过预算时阻塞，不截断后声称已全面复核；拆分任务或经授权修改 Spec 预算。旧 packet 保留备查，不反复把历史版本和整仓库发送给模型。

任何新提交、base 引用变化或 Spec 快照不符都会使旧 PASS 失效。发生变化时重新规划或重新启动任务，不能通过编辑运行状态文件、复用旧审查来绕过。用户若需要包含原目录的未提交业务修改，应先明确这些修改是否属于当前任务，再决定安全的迁移方式。

## 权限和停止条件

- 使用本机现有 Claude OAuth 登录，无需额外 API Key。先用 `doctor` 检查 Claude/Git/认证和所需工具；认证失败时报告实际缺口，不自行切换计费方式。
- Claude 使用 `--safe-mode` 加明确提供的可信指令，避免自动加载项目/个人 hooks 和无关扩展。该模式保留认证，但不会自动加载 CLAUDE.md 和 Skill；因此上下文必须由宿主显式交付。
- 预检和实际调用均使用相同的 safe-mode 与认证环境。本机 CLI 的 `--restricted` 经实测无法读取现有 OAuth 登录，因此本流程固定选择 safe-mode 加文件工具限定，不使用 restricted，也不在运行时静默切换模式或计费方式。
- Claude 仅使用文件工具，不获得 Bash、MCP、云部署或 GitHub 操作。宿主拥有测试、Git 和 PR 操作；不使用 `--dangerously-skip-permissions` 或 `bypassPermissions`。
- 文件工具限定仅限制工具种类，不是操作系统级隔离，也不保证文件读取仅限工作目录。Claude 应只处理 Spec 中的允许范围，宿主校验待提交变更；仍须信任仓库上下文和正常系统权限，不把密钥、真实日程或音频样本加入上下文。
- 宿主只执行 `scripts/ai_dispatch_config.json` 中精确命令映射对应的 argv，使用参数数组而非任意 shell。Spec 中的未知命令应 BLOCKED；新增检查必须先审阅并作为可信配置显式加入，不能为让任务通过自动放宽。
- 次数、超时、配额、权限、缺失证据、越界改动或冻结上下文不一致都会停止当前推进并保留记录。不得无限重试、弱化 AC、伪造测试通过或让 Claude 自批。
- 自动推进只发生在当前 Codex 活跃会话内。当前轮次结束后不会后台唤醒、定时监控或继续消耗模型额度；恢复时先读取运行状态并核对基线。

可信检查配置的结构如下；命令字符串须与 Spec 中 `checks[].command` 完全一致。启动时冻结配置，任务实现不能自行修改映射来扩大执行权限。

```json
{
  "schema_version": 1,
  "allowed_checks": {
    "python3 scripts/ai_workflow.py validate": ["python3", "scripts/ai_workflow.py", "validate"]
  }
}
```

## PR 和用户最终决定

PR 必须包含 `AI-Task:` 行、当前 head/base、AC 与检查证据、GPT 复核结论、风险和回滚。状态文件和转录的 GPT 结果不能认证模型身份，也不能代表用户批准。

发布 draft PR 不是合并。用户审查最终范围、CI、当前 GPT PASS 和剩余风险后决定合并。GitHub 分支保护仍需要仓库所有者配置；未配置时不能宣称远端已经强制执行了人工审批。

需要手动方式时，使用 [原有交接流程](README.md#手动交接可选)；它与自动方式共享 Spec、报告和 packet 格式。

## 官方参考

- [Codex 项目规范和 Skill](https://learn.chatgpt.com/docs/customization/overview)：根 AGENTS.md 与 `.agents/skills` 的作用。
- [Claude Code CLI](https://code.claude.com/docs/en/cli-reference)：print、safe-mode、工具范围、JSON 输出、会话与次数限制。
- [Claude Code 自动运行](https://code.claude.com/docs/en/headless)：`claude -p`、结构化结果和错误处理；`--bare` 不读取 OAuth 登录。
- [Claude Code 权限](https://code.claude.com/docs/en/permissions)：允许规则不代替工具范围或操作系统隔离。
