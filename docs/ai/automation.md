# Codex 自动调度、CI、合并与交付

在已更新的 Closure 副本中向 Codex 提执行需求，Codex 将持续推进规划、Claude 实现、技术复核、CI、合并和可运行交付。用户已于 2026-10-28 授权代理运行 CI 和合并 PR，自己只验收最终可运行项目；无需逐次批准普通 CI 或合并。

该授权属于 Closure 需求范围内的工程流程，不代表用户已接受结果，不授权范围外部署、额外费用、破坏性数据操作或对外发消息。用户后续要求先审方案、人工合并等具体限制优先。

## 从需求到交付

1. 当前 Codex 生成版本化 Spec，明确目标、非目标、允许路径、AC、检查、回滚和可运行标准。执行条件齐备设 READY；只读/设计请求保持 DRAFT。
2. 调度器从已提交 base 创建独立工作区和分支，冻结 Spec、可信命令和完成策略。原目录未提交内容不会自动复制；需求依赖这些内容时先确定正确基线。
3. 本机已登录 Claude 通过文件工具实现，宿主负责测试、Git、证据和 PR。自测失败在次数上限内自动成批回传同一 Claude 会话。
4. 当前 Codex 对稳定 packet 集中复核，写真实 PASS / REVISE / BLOCKED。REVISE 自动修复并重建包，不能沿用旧提交结论。
5. PASS 后发布草稿 PR；Codex 主动执行 finalize，等待当前 head 的必要 CI。失败自动交回修复并要求新的技术审查。
6. 草稿转 ready 后等待新一轮必要检查实例，避免新检查尚未注册时误用旧绿灯。所有门禁通过后，按授权合并精确受审 SHA，不绕过远端规则。
7. 合并不是完成：核对实际 merge commit 的 main push CI，并在干净交付副本运行冻结的测试、构建与启动检查。只有 COMPLETE 才向用户交付项目和启动方式。

当前 Codex 就是 GPT 规划者和复核者；调度器不启动额外 GPT API 或递归 Codex 进程。Claude 不给自己的实现填写 GPT PASS，也不负责合并。

## 命令与状态

以下命令主要由 Codex 执行，用户可用于诊断。任务号和路径须替换为实际值：

```sh
python3 scripts/ai_dispatch.py doctor
python3 scripts/ai_dispatch.py start --task docs/ai/tasks/TASK-NNNN.json --base origin/main
python3 scripts/ai_dispatch.py status --run <RUN_DIR>
python3 scripts/ai_dispatch.py review --run <RUN_DIR> --review <REVIEW_JSON>
python3 scripts/ai_dispatch.py repair --run <RUN_DIR> --feedback <CURRENT_HEAD_FEEDBACK_JSON>
python3 scripts/ai_dispatch.py publish --run <RUN_DIR>
python3 scripts/ai_dispatch.py finalize --run <RUN_DIR>
```

start 默认最多 3 次 Claude 执行（包括修复）、每次最多 30 turns、超时 900 秒；可调整 max-rounds / max-turns / timeout。增加次数不能解决缺少授权、认证或产品决定的问题。

| 状态 | Codex 的后续工作 |
| --- | --- |
| AWAITING_REVIEW | 读取完整 packet，实际复核并调用 review |
| READY_TO_PUBLISH | 调用 publish，附上 PR URL，再调用 finalize |
| AWAITING_CI | 必要检查仍在等待或缺失；限时等待后继续 finalize，不宣称已完成 |
| MERGED | 已合并，继续合并后的工程验证 |
| AWAITING_MAIN_CI | main push 检查待完成；继续 finalize |
| DELIVERY_FAILED | 读取失败日志，修复并审查后续任务；不交付失败版本 |
| COMPLETE | 合并后 CI 与交付检查均通过，交用户产品验收 |
| BLOCKED | 先处理可独立解决的工程缺口；仅真实决定/权限问题交用户 |

发布目标为 main，受审 base 必须等于最新 origin/main。其他 base 可用于隔离实现与复核；主分支发生变化时，先安全整合、重新检查和复核，不复用旧 PASS。

CI 反馈至少包含当前完整 head 与必要诊断：

```json
{"head_sha": "当前完整40位提交SHA", "message": "失败检查及最少必要诊断"}
```

## 可信完成策略

`scripts/ai_dispatch_config.json` 中的 allowed_checks 将精确命令映射到 argv；Spec 不会被作为任意 shell 字符串执行。completion_policy 与 Spec 在 start 时冻结，任务不能自行扩大权限：

```json
{
  "completion_policy": {
    "merge_authorized": true,
    "authorization_note": "User delegated CI and PR merging; user accepts the final runnable project.",
    "merge_method": "merge",
    "required_checks": ["workflow-contract", "java-tests"],
    "check_timeout_seconds": 900,
    "poll_interval_seconds": 10,
    "delivery_checks": [
      "./mvnw -B -Djava.awt.headless=true test",
      "./mvnw -B -DskipTests package",
      "python3 scripts/verify_startup.py"
    ]
  }
}
```

上例中的交付命令还必须存在于可信 allowed_checks。没有实际 merge_authorized 授权的旧配置不能合并，不能仅把布尔值或模型结论当作人类授权来源。当前仓库策略对应用户已给出的明确授权；撤回或更具体的任务限制必须遵守。

finalize 核验真实 PASS、冻结的 Spec/报告/原始检查日志、工作分支、当前 PR head/base、最新主分支和必要 CI。合并命令绑定受审 SHA，不使用 admin、强推或绕过检查。只有现有草稿 PR 与受审分支一致时才继续；元数据被外部修改须核对实际状态，不静默合并未知内容。

CI 失败时 finalize 收集 head-bound 诊断并自动调用实现阶段，返回 AWAITING_REVIEW；Codex 重新审查后再次 publish / finalize。合并后交付检查失败时，不能把工程失败当作用户验收，须处理后续修复。

## 交付检查与证据

记录保存在 `.ai-workflow/runs/TASK-NNNN-<unique>/`：冻结的 spec/config、state、独立 worktree、每轮报告/packet 和原始日志。命令返回精简状态；原始 Claude 会话仅在定位问题时按需读取。

合并后的 `delivery_workspace` 指向实际 merge commit 的干净检出，`merge_commit_sha` 和 main CI 记录标识交付版本；delivery_checks 保存实际命令、结果和日志哈希。用户收到项目位置、启动方式、范围和必要限制，不承担逐 PR 的工程手续。

`./mvnw javafx:run` 正常启动应用，要求 JDK 21。离线 `python3 scripts/verify_startup.py` 会实际初始化并显示 PetWindow 和模型，随后退出，跳过 AI、天气、TTS、提醒和音频服务；它验证桌面启动和资源，不能代表真实服务集成已验收。Linux 无显示环境时使用 xvfb。交付仍须覆盖任务 Spec 要求的其他实际行为。

完整 Diff 超预算时明确停止，不截断后宣称全面复核。任何 head/base、Spec、报告、packet 或原始检查日志变化都使旧 PASS 失效。技术审查记录不认证模型身份，也不冒充 GitHub APPROVE 或用户产品验收。

## 权限、边界和恢复

- 复用本机 Claude OAuth，无需另配 API Key。预检与实际调用使用相同 safe-mode 和认证环境；认证失败不自行切换计费。
- safe-mode 禁用项目/个人 hooks 和无关扩展，CLAUDE.md/Skill 不自动加载，可信上下文由宿主明确提供。本机 restricted 无法读取现有 OAuth，因此固定采用已验证的 safe-mode，而不运行时静默切换。
- Claude 只有文件工具，无 Bash、MCP 或 GitHub 操作；不使用权限绕过。文件工具种类限制不是 OS 或目录读取隔离，须信任上下文并排除密钥、真实日程、音频和部署身份。
- 宿主只执行冻结的可信 argv。新增检查须实际审阅并纳入授权范围，不为执行任意字符串放宽配置。
- 合并不使用 admin 或绕过服务器规则。远端分支保护独立于本地策略；不得声称未配置的服务器门禁已存在。
- 普通 CI 失败、测试失败、限时等待由 Codex 继续处理。缺少关键产品决定、权限、额度或超出授权时集中报告；不能无限重试或弱化验收。
- 自动调度发生在当前活跃 Codex 任务内，结束后不后台唤醒。恢复先读状态并核对当前提交、基线和授权。

仅当用户明确选择时使用[手动交接](README.md#手动交接仅明确选择时)。它共享 Spec、证据和检查标准，不自动取消已经授予的工程合并权限；用户可明确要求某任务人工合并。

## 官方参考

- [Codex 项目规范和 Skill](https://learn.chatgpt.com/docs/customization/overview)
- [Claude Code CLI](https://code.claude.com/docs/en/cli-reference)
- [Claude Code 自动运行](https://code.claude.com/docs/en/headless)
- [GitHub CLI 合并命令](https://cli.github.com/manual/gh_pr_merge)
- [GitHub CLI 检查命令](https://cli.github.com/manual/gh_pr_checks)
