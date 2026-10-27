"""Exercise the dispatch state machine with local Git and a fake Claude, without models."""
import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]
TASK = "docs/ai/tasks/TASK-0001.json"
SESSION = "7de86be1-bd47-4982-ae96-9d8b7d51dd94"


class DispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "controller"
        self.repo.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ.get("PATH", ""))
        self.git("init", "-q")
        self.git("config", "user.name", "Dispatch Test")
        self.git("config", "user.email", "dispatch@example.invalid")
        self.write(".gitignore", ".ai-workflow/\n__pycache__/\n")
        self.write("CLAUDE.md", "Only implement the specified task. Host owns checks and Git.\n")
        (self.repo / "scripts").mkdir()
        for name in ("ai_dispatch.py", "ai_workflow.py"):
            shutil.copyfile(SCRIPTS / name, self.repo / "scripts" / name)
        self.spec = {
            "schema_version": 1, "task_id": "TASK-0001", "spec_version": 1,
            "status": "READY", "title": "Add documented result", "objective": "Write the documented result",
            "architecture": ["Use a plain text result"], "non_goals": ["No service calls"],
            "allowed_paths": ["docs/result.txt"],
            "acceptance_criteria": [{"id": "AC-1", "requirement": "Document the result", "verification": "Host fixture check"}],
            "checks": [{"id": "CHECK-1", "command": "python3 fixture-check.py", "required": True}],
            "risks": [], "rollback": "Revert the task branch",
            "review_budget": {"max_diff_bytes": 100000},
        }
        self.write_json(TASK, self.spec)
        template = copy.deepcopy(self.spec)
        template["status"] = "DRAFT"
        self.write_json("docs/ai/templates/task.spec.json", template)
        self.write("fixture-check.py", "from pathlib import Path\nassert Path('docs/result.txt').read_text() == 'fixed\\n'\n")
        self.write_json("scripts/ai_dispatch_config.json", {
            "schema_version": 1,
            "allowed_checks": {"python3 fixture-check.py": [sys.executable, "fixture-check.py"]},
        })
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        self.git("branch", "automation-base", self.base)
        self.scenario = self.bin / "scenario.json"
        self.scenario.write_text(json.dumps({"mode": "good"}))
        self.invocations = self.bin / "invocations.json"
        script = '''#!PYTHON
import json,sys,time
from pathlib import Path
folder=Path(__file__).parent
args=sys.argv[1:]
if '--help' in args:
    print('--safe-mode --json-schema --permission-prompts --tools --allowedTools --max-turns'); sys.exit(0)
if '--version' in args:
    print('2.1.302 (Claude Code)'); sys.exit(0)
if args[-2:] == ['auth','status']:
    if '--safe-mode' not in args: sys.exit(3)
    (folder/'auth-called').write_text(json.dumps(args))
    print(json.dumps({'loggedIn':True})); sys.exit(0)
calls=folder/'invocations.json'
history=json.loads(calls.read_text()) if calls.exists() else []
history.append({'args':args,'cwd':str(Path.cwd())})
calls.write_text(json.dumps(history))
scenario=json.loads((folder/'scenario.json').read_text())
mode=scenario['mode']
if mode == 'timeout': time.sleep(10)
if mode == 'spec-mutation':
    p=Path('docs/ai/tasks/TASK-0001.json'); task=json.loads(p.read_text())
    task['objective']='Changed without authorization'; p.write_text(json.dumps(task))
if mode == 'out-of-scope': Path('unexpected.txt').write_text('outside the allowed paths')
if mode == 'repair-extra': Path('docs/repair.txt').write_text('CI repair evidence\\n')
if mode != 'blocked':
    content='wrong\\n' if mode == 'always-bad' or (mode == 'fix-after-check' and len(history) == 1) else 'fixed\\n'
    Path('docs').mkdir(exist_ok=True); Path('docs/result.txt').write_text(content)
result={'is_error':False,'session_id':'SESSION', 'structured_output':{
    'status':'BLOCKED' if mode == 'blocked' else 'COMPLETED','summary':'Fixture implementation',
    'acceptance':[{'id':'AC-1','status':'BLOCKED' if mode == 'blocked' else 'PASS','evidence':'Result file inspected'}],
    'risks':[]}}
if mode == 'missing-structured': result.pop('structured_output')
if mode == 'denied': result['permission_denials']=[{'tool_name':'Write','tool_use_id':'fixture'}]
print(json.dumps(result))
'''.replace("#!PYTHON", "#!" + sys.executable).replace("SESSION", SESSION)
        self.executable("claude", script)
        self.executable("gh", "#!" + sys.executable + '''
import json,sys,subprocess
from pathlib import Path
marker=Path(__file__).with_name('gh-called')
calls=json.loads(marker.read_text()) if marker.exists() else []
calls.append(sys.argv[1:]); marker.write_text(json.dumps(calls))
scenario=json.loads(Path(__file__).with_name('scenario.json').read_text())
mode=scenario.get('gh_mode','new')
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
metadata={'number':7,'url':'https://github.com/example/test/pull/7','isDraft':True,'baseRefName':'main','headRefOid':head}
if mode == 'existing-repair': metadata.update(number=1,url='https://github.com/example/test/pull/1')
if sys.argv[1:3] == ['pr','list'] and scenario.get('gh_list_head'): metadata['headRefOid']=scenario['gh_list_head']
if mode.endswith('wrong-base'): metadata['baseRefName']='release'
if mode.endswith('wrong-head'): metadata['headRefOid']='0'*40
if mode.endswith('ready'): metadata['isDraft']=False
if sys.argv[1:3] == ['pr','list']:
    print(json.dumps([metadata] if mode.startswith('existing-') else []))
elif sys.argv[1:3] == ['pr','create'] and '--draft' in sys.argv:
    print('https://github.com/example/test/pull/1')
elif sys.argv[1:3] == ['pr','edit']:
    print(metadata['url'])
elif sys.argv[1:3] == ['pr','view']:
    if not mode.startswith('existing-'): metadata['url']='https://github.com/example/test/pull/1'
    print(json.dumps(metadata))
else:
    sys.exit(2)
''')

    def executable(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def git(self, *args, cwd=None):
        result = subprocess.run(["git", *args], cwd=cwd or self.repo, capture_output=True, text=True, check=True)
        return result.stdout.strip()

    def write(self, path, content):
        p = self.repo / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def write_json(self, path, content):
        self.write(path, json.dumps(content, indent=2) + "\n")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "Fixture baseline")

    def mode(self, mode):
        self.scenario.write_text(json.dumps({"mode": mode}))

    def local_github_remote(self):
        remote = self.root / "remote.git"
        self.git("init", "-q", "--bare", str(remote))
        self.git("remote", "add", "origin", "https://github.com/example/test.git")
        self.git("config", "url." + str(remote) + ".insteadOf", "https://github.com/example/test.git")
        return remote

    def calls(self):
        return json.loads(self.invocations.read_text()) if self.invocations.exists() else []

    def test_doctor_checks_oauth_under_the_same_safe_mode_as_dispatch(self):
        self.assertEqual(self.command("doctor")["status"], "READY")
        self.assertIn("--safe-mode", json.loads((self.bin / "auth-called").read_text()))

    def command(self, *args, ok=True):
        proc = subprocess.run([sys.executable, "scripts/ai_dispatch.py", *args],
                              cwd=self.repo, env=self.env, capture_output=True, text=True, timeout=30)
        self.assertEqual(proc.returncode, 0 if ok else 1, proc.stdout + proc.stderr)
        try:
            state = json.loads(proc.stdout)
        except ValueError:
            self.fail("Dispatcher must return one compact JSON result: " + proc.stdout + proc.stderr)
        if not ok:
            self.assertEqual(state["status"], "BLOCKED")
        return state

    def start(self, *extra, ok=True):
        return self.command("start", "--task", TASK, "--base", "automation-base", *extra, ok=ok)

    def review_file(self, state, verdict="PASS"):
        packet = Path(state["packet_dir"])
        raw = (packet / "manifest.json").read_bytes()
        manifest = json.loads(raw)
        review = {key: manifest[key] for key in
                  ("schema_version", "task_id", "spec_version", "head_sha", "base_sha", "spec_sha256")}
        review.update(manifest_sha256=hashlib.sha256(raw).hexdigest(), verdict=verdict,
                      acceptance=[{"id": "AC-1", "status": "PASS" if verdict == "PASS" else "FAIL", "evidence": "Independent fixture review"}],
                      findings=[] if verdict == "PASS" else ["Improve the documented result"],
                      reviewed_by="Independent test reviewer")
        path = self.root / ("review-" + verdict + ".json")
        path.write_text(json.dumps(review, indent=2) + "\n")
        return path

    def test_happy_path_isolates_workspace_runs_checks_and_makes_bound_packet(self):
        state = self.start()
        self.assertEqual(state["status"], "AWAITING_REVIEW")
        self.assertEqual(state["round"], 1)
        worker = Path(state["workspace"])
        self.assertNotEqual(worker, self.repo)
        self.assertEqual((worker / "docs/result.txt").read_text(), "fixed\n")
        self.assertFalse((self.repo / "docs/result.txt").exists())
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        manifest = json.loads((Path(state["packet_dir"]) / "manifest.json").read_text())
        report = json.loads(Path(state["report_path"]).read_text())
        self.assertEqual(manifest["head_sha"], state["head_sha"])
        self.assertEqual(report["checks"][0]["status"], "PASS")
        args = self.calls()[0]["args"]
        self.assertIn("--safe-mode", args)
        self.assertNotIn("--restricted", args)
        self.assertNotIn("--dangerously-skip-permissions", args)
        self.assertEqual(set(args[args.index("--tools") + 1].split(",")), {"Read", "Glob", "Grep", "Edit", "Write"})
        self.assertEqual(json.loads(args[args.index("--mcp-config") + 1]), {"mcpServers": {}})
        self.assertIn("--strict-mcp-config", args)
        status = self.command("status", "--run", state["run_dir"])
        self.assertEqual(status["head_sha"], state["head_sha"])

    def test_dirty_controller_changes_are_preserved_and_excluded(self):
        self.write("CLAUDE.md", "Uncommitted user work\n")
        self.write("user-notes.txt", "Do not touch\n")
        before = self.git("status", "--porcelain")
        state = self.start()
        self.assertEqual(self.git("status", "--porcelain"), before)
        self.assertEqual((self.repo / "CLAUDE.md").read_text(), "Uncommitted user work\n")
        self.assertFalse((Path(state["workspace"]) / "user-notes.txt").exists())

    def test_host_checks_do_not_inherit_model_or_github_credentials(self):
        self.env.update(OPENAI_API_KEY="fictional-test-key", GITHUB_TOKEN="fictional-test-token")
        self.write("fixture-check.py", "import os\nfrom pathlib import Path\n"
                   "assert not os.environ.get('OPENAI_API_KEY')\n"
                   "assert not os.environ.get('GITHUB_TOKEN')\n"
                   "assert Path('docs/result.txt').read_text() == 'fixed\\n'\n")
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/heads/automation-base", self.base)
        self.assertEqual(self.start()["status"], "AWAITING_REVIEW")

    def test_failed_host_check_repairs_automatically_in_same_claude_session(self):
        self.mode("fix-after-check")
        state = self.start()
        self.assertEqual(state["status"], "AWAITING_REVIEW")
        self.assertEqual(state["round"], 2)
        self.assertEqual(len(self.calls()), 2)
        args = self.calls()[1]["args"]
        self.assertEqual(args[args.index("--resume") + 1], SESSION)
        self.assertEqual((Path(state["workspace"]) / "docs/result.txt").read_text(), "fixed\n")

    def test_revise_automatically_hands_the_batch_back_and_requires_new_review(self):
        first = self.start()
        review = self.review_file(first, "REVISE")
        second = self.command("review", "--run", first["run_dir"], "--review", str(review))
        self.assertEqual(second["status"], "AWAITING_REVIEW")
        self.assertEqual(second["round"], 2)
        self.assertEqual(len(self.calls()), 2)
        self.assertNotEqual(first["packet_dir"], second["packet_dir"])

    def test_pass_is_accepted_only_for_current_packet(self):
        state = self.start()
        review = self.review_file(state)
        accepted = self.command("review", "--run", state["run_dir"], "--review", str(review))
        self.assertEqual(accepted["status"], "READY_TO_PUBLISH")
        self.assertEqual(len(self.calls()), 1)

    def test_head_alias_base_stays_bound_to_the_controller_commit_during_review(self):
        state = self.command("start", "--task", TASK, "--base", "HEAD")
        self.assertEqual(state["base_sha"], self.base)
        result = self.command("review", "--run", state["run_dir"], "--review", str(self.review_file(state)))
        self.assertEqual(result["status"], "READY_TO_PUBLISH")

    def test_changed_or_missing_host_check_artifacts_block_review(self):
        for change in ("overwrite", "remove"):
            with self.subTest(change=change):
                state = self.start()
                review = self.review_file(state)
                logfile = Path(state["check_artifacts"][0]["path"])
                if change == "overwrite":
                    logfile.write_bytes(b"Forged host evidence\n")
                else:
                    logfile.unlink()
                self.command("review", "--run", state["run_dir"], "--review", str(review), ok=False)

    def test_altered_host_log_invalidates_a_prior_pass_before_publication(self):
        remote = self.local_github_remote()
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        state = self.start()
        self.command("review", "--run", state["run_dir"], "--review", str(self.review_file(state)))
        Path(state["check_artifacts"][0]["path"]).write_bytes(b"Changed after PASS\n")
        self.command("publish", "--run", state["run_dir"], ok=False)
        self.assertFalse((self.bin / "gh-called").exists())
        task_ref = subprocess.run(["git", "--git-dir", str(remote), "show-ref", "--verify", "refs/heads/" + state["branch"]],
                                  capture_output=True)
        self.assertNotEqual(task_ref.returncode, 0)

    def test_model_blocked_or_missing_structured_output_stops_without_retry(self):
        for mode in ("blocked", "missing-structured", "denied"):
            with self.subTest(mode=mode):
                self.mode(mode)
                before = len(self.calls())
                self.start(ok=False)
                self.assertEqual(len(self.calls()), before + 1)

    def test_out_of_scope_and_spec_mutation_stop_before_host_commit(self):
        for mode in ("out-of-scope", "spec-mutation"):
            with self.subTest(mode=mode):
                self.mode(mode)
                before = len(self.calls())
                state = self.start(ok=False)
                self.assertEqual(len(self.calls()), before + 1)
                self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
                self.assertFalse((self.repo / "unexpected.txt").exists())

    def test_unknown_check_is_never_executed_or_sent_to_claude(self):
        self.spec["checks"][0]["command"] = "$(touch INJECTED)"
        self.write_json(TASK, self.spec)
        self.start(ok=False)
        self.assertFalse(self.calls())
        self.assertFalse((self.repo / "INJECTED").exists())

    def test_existing_spec_changes_need_a_version_increase_before_model_dispatch(self):
        self.spec["objective"] = "Improve the documented result within the same allowed scope"
        self.write_json(TASK, self.spec)
        raw = (self.repo / TASK).read_bytes()
        self.start(ok=False)
        self.assertFalse(self.calls())
        self.assertEqual((self.repo / TASK).read_bytes(), raw)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.spec["spec_version"] = 2
        self.write_json(TASK, self.spec)
        accepted = self.start()
        self.assertEqual(accepted["status"], "AWAITING_REVIEW")
        self.assertEqual(len(self.calls()), 1)

    def test_round_limit_stops_automatic_failed_check_loop(self):
        self.mode("always-bad")
        self.start("--max-rounds", "2", ok=False)
        self.assertEqual(len(self.calls()), 2)

    def test_wall_clock_timeout_stops_model_process(self):
        self.mode("timeout")
        start = time.monotonic()
        self.start("--timeout", "1", ok=False)
        self.assertLess(time.monotonic() - start, 6)
        self.assertEqual(len(self.calls()), 1)

    def test_stale_worker_head_invalidates_pass(self):
        state = self.start()
        review = self.review_file(state)
        worker = Path(state["workspace"])
        (worker / "docs/result.txt").write_text("later change\n")
        self.git("add", "docs/result.txt", cwd=worker)
        self.git("commit", "-q", "-m", "Later change", cwd=worker)
        self.command("review", "--run", state["run_dir"], "--review", str(review), ok=False)

    def test_updated_base_invalidates_pass(self):
        state = self.start()
        review = self.review_file(state)
        self.write("later-main.txt", "new base\n")
        self.commit()
        self.git("update-ref", "refs/heads/automation-base", self.git("rev-parse", "HEAD"))
        self.command("review", "--run", state["run_dir"], "--review", str(review), ok=False)

    def test_altered_packet_invalidates_pass(self):
        state = self.start()
        review = self.review_file(state)
        diff = Path(state["packet_dir"]) / "changes.diff"
        diff.write_bytes(diff.read_bytes() + b"tampering")
        self.command("review", "--run", state["run_dir"], "--review", str(review), ok=False)

    def test_blocked_review_stops_instead_of_relaunching_claude(self):
        state = self.start()
        review = self.review_file(state, "BLOCKED")
        self.command("review", "--run", state["run_dir"], "--review", str(review), ok=False)
        self.assertEqual(len(self.calls()), 1)

    def test_publish_requires_a_valid_current_pass(self):
        state = self.start()
        self.command("publish", "--run", state["run_dir"], ok=False)
        self.assertFalse((self.bin / "gh-called").exists())

    def test_valid_pass_publishes_only_task_branch_and_creates_a_draft(self):
        remote = self.local_github_remote()
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        # Keep the reported GitHub URL while routing all Git traffic to this temporary bare repository.
        state = self.start()
        review = self.review_file(state)
        self.command("review", "--run", state["run_dir"], "--review", str(review))
        result = self.command("publish", "--run", state["run_dir"])
        self.assertEqual(result["status"], "AWAITING_CI")
        self.assertEqual(result["pr_url"], "https://github.com/example/test/pull/1")
        self.assertEqual(result["published_head_sha"], state["head_sha"])
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/main"), self.base)
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/" + state["branch"]), state["head_sha"])
        requests = json.loads((self.bin / "gh-called").read_text())
        self.assertEqual([request[:2] for request in requests], [["pr", "list"], ["pr", "create"], ["pr", "view"]])
        self.assertIn("--draft", requests[1])

    def test_detached_reviewed_head_cannot_publish_a_retargeted_unreviewed_branch(self):
        remote = self.local_github_remote()
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        state = self.start()
        review = self.review_file(state)
        self.command("review", "--run", state["run_dir"], "--review", str(review))
        worker = Path(state["workspace"])
        self.git("checkout", "-q", "--detach", state["head_sha"], cwd=worker)
        self.write("unreviewed.txt", "This commit has never been reviewed\n")
        self.commit()
        unreviewed_head = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/heads/" + state["branch"], unreviewed_head)
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=worker), state["head_sha"])
        self.command("publish", "--run", state["run_dir"], ok=False)
        self.assertFalse((self.bin / "gh-called").exists())
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/main"), self.base)
        task_ref = subprocess.run(["git", "--git-dir", str(remote), "show-ref", "--verify", "refs/heads/" + state["branch"]],
                                  capture_output=True)
        self.assertNotEqual(task_ref.returncode, 0)

    def test_existing_pr_must_match_reviewed_main_head_and_remain_draft_before_edit(self):
        remote = self.local_github_remote()
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        for gh_mode in ("existing-wrong-base", "existing-wrong-head", "existing-ready"):
            with self.subTest(gh_mode=gh_mode):
                self.scenario.write_text(json.dumps({"mode": "good", "gh_mode": gh_mode}))
                state = self.start()
                self.command("review", "--run", state["run_dir"], "--review", str(self.review_file(state)))
                marker = self.bin / "gh-called"
                before = len(json.loads(marker.read_text())) if marker.exists() else 0
                self.command("publish", "--run", state["run_dir"], ok=False)
                requests = json.loads(marker.read_text())[before:]
                self.assertTrue(requests)
                self.assertFalse(any(request[:2] in (["pr", "edit"], ["pr", "create"]) for request in requests))
                self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/main"), self.base)
                task_ref = subprocess.run(["git", "--git-dir", str(remote), "show-ref", "--verify", "refs/heads/" + state["branch"]],
                                          capture_output=True)
                self.assertNotEqual(task_ref.returncode, 0)

    def test_ci_repair_republishes_from_the_last_published_pr_head_to_the_new_reviewed_head(self):
        remote = self.local_github_remote()
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        self.spec["spec_version"] = 2
        self.spec["allowed_paths"].append("docs/repair.txt")
        self.write_json(TASK, self.spec)
        state = self.start()
        self.command("review", "--run", state["run_dir"], "--review", str(self.review_file(state)))
        first = self.command("publish", "--run", state["run_dir"])
        old_head = first["head_sha"]
        self.scenario.write_text(json.dumps({"mode": "repair-extra", "gh_mode": "existing-repair", "gh_list_head": old_head}))
        feedback = self.root / "ci-feedback.json"
        feedback.write_text(json.dumps({"head_sha": old_head, "message": "Add all missing CI repair evidence"}))
        repaired = self.command("repair", "--run", state["run_dir"], "--feedback", str(feedback))
        self.assertNotEqual(repaired["head_sha"], old_head)
        self.assertEqual(repaired["published_head_sha"], old_head)
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/" + state["branch"]), old_head)
        self.command("review", "--run", state["run_dir"], "--review", str(self.review_file(repaired)))
        marker = self.bin / "gh-called"
        before = len(json.loads(marker.read_text()))
        republished = self.command("publish", "--run", state["run_dir"])
        self.assertEqual(republished["status"], "AWAITING_CI")
        self.assertEqual(republished["pr_url"], first["pr_url"])
        self.assertEqual(republished["published_head_sha"], repaired["head_sha"])
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/" + state["branch"]), repaired["head_sha"])
        requests = json.loads(marker.read_text())[before:]
        self.assertEqual([request[:2] for request in requests], [["pr", "list"], ["pr", "edit"], ["pr", "view"]])

    def test_created_pr_metadata_is_reverified_before_publication_is_accepted(self):
        self.local_github_remote()
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        for gh_mode in ("created-wrong-base", "created-wrong-head", "created-ready"):
            with self.subTest(gh_mode=gh_mode):
                self.scenario.write_text(json.dumps({"mode": "good", "gh_mode": gh_mode}))
                state = self.start()
                self.command("review", "--run", state["run_dir"], "--review", str(self.review_file(state)))
                marker = self.bin / "gh-called"
                before = len(json.loads(marker.read_text())) if marker.exists() else 0
                self.command("publish", "--run", state["run_dir"], ok=False)
                requests = json.loads(marker.read_text())[before:]
                self.assertTrue(any(request[:2] == ["pr", "view"] for request in requests))

    def test_mismatched_pushurl_cannot_redirect_the_reviewed_publication(self):
        remote = self.local_github_remote()
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        unexpected = self.root / "unexpected-remote.git"
        self.git("init", "-q", "--bare", str(unexpected))
        unexpected_url = "https://github.com/example/unexpected.git"
        self.git("config", "url." + str(unexpected) + ".insteadOf", unexpected_url)
        self.git("config", "remote.origin.pushurl", unexpected_url)
        state = self.start()
        self.command("review", "--run", state["run_dir"], "--review", str(self.review_file(state)))
        self.command("publish", "--run", state["run_dir"], ok=False)
        self.assertFalse((self.bin / "gh-called").exists())
        for target in (remote, unexpected):
            task_ref = subprocess.run(["git", "--git-dir", str(target), "show-ref", "--verify", "refs/heads/" + state["branch"]],
                                      capture_output=True)
            self.assertNotEqual(task_ref.returncode, 0)

    def test_ci_feedback_is_head_bound_and_repairs_require_a_fresh_review(self):
        state = self.start()
        feedback = self.root / "ci-feedback.json"
        feedback.write_text(json.dumps({"head_sha": state["head_sha"], "message": "Fix all current CI findings"}))
        repaired = self.command("repair", "--run", state["run_dir"], "--feedback", str(feedback))
        self.assertEqual(repaired["status"], "AWAITING_REVIEW")
        self.assertEqual(repaired["round"], 2)
        self.assertEqual(len(self.calls()), 2)
        feedback.write_text(json.dumps({"head_sha": "0" * 40, "message": "Stale feedback"}))
        self.command("repair", "--run", state["run_dir"], "--feedback", str(feedback), ok=False)
        self.assertEqual(len(self.calls()), 2)

    def test_publish_rejects_a_pass_against_a_different_remote_main_before_push(self):
        remote = self.local_github_remote()
        self.write("new-main.txt", "A newer main commit\n")
        self.commit()
        main_head = self.git("rev-parse", "HEAD")
        self.git("push", "-q", "origin", "HEAD:refs/heads/main")
        state = self.start()
        review = self.review_file(state)
        self.command("review", "--run", state["run_dir"], "--review", str(review))
        self.command("publish", "--run", state["run_dir"], ok=False)
        self.assertFalse((self.bin / "gh-called").exists())
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/main"), main_head)
        missing = subprocess.run(["git", "--git-dir", str(remote), "show-ref", "--verify", "refs/heads/" + state["branch"]],
                                 capture_output=True)
        self.assertNotEqual(missing.returncode, 0)


if __name__ == "__main__":
    unittest.main()
