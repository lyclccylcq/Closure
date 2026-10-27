"""Integration tests with disposable Git histories; no network or application runtime."""
import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CLI = Path(__file__).resolve().parents[1] / "ai_workflow.py"
TASK = "docs/ai/tasks/TASK-0001.json"
TEMPLATE = "docs/ai/templates/task.spec.json"


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Workflow Test")
        self.git("config", "user.email", "workflow@example.invalid")
        self.spec = {
            "schema_version": 1, "task_id": "TASK-0001", "spec_version": 1,
            "status": "READY", "title": "Add greeting", "objective": "Return a greeting",
            "architecture": ["Use existing pure function module"], "non_goals": ["No HTTP service"],
            "allowed_paths": ["src/**"],
            "acceptance_criteria": [{"id": "AC-1", "requirement": "Return hello", "verification": "Unit assertion"}],
            "checks": [{"id": "CHECK-1", "command": "python3 -m unittest", "required": True}],
            "risks": [], "rollback": "Revert the implementation commit",
            "review_budget": {"max_diff_bytes": 100000},
        }
        self.write(".gitignore", ".ai-workflow/\n")
        self.write_json(TASK, self.spec)
        template = copy.deepcopy(self.spec)
        template.update(status="DRAFT", title="TODO")
        self.write_json(TEMPLATE, template)
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        self.write("src/greeting.py", "def greet():\n    return 'hello'\n")
        self.commit()
        self.head = self.git("rev-parse", "HEAD")
        self.report_path = ".ai-workflow/report.json"
        self.packet_path = ".ai-workflow/packet"
        self.review_path = ".ai-workflow/review.json"
        self.report = {
            "schema_version": 1, "task_id": "TASK-0001", "spec_version": 1,
            "head_sha": self.head,
            "acceptance": [{"id": "AC-1", "status": "PASS", "evidence": "greet() assertion passed"}],
            "checks": [{"id": "CHECK-1", "status": "PASS", "evidence": "1 test passed",
                        "command": "python3 -m unittest"}],
            "deviations": [], "risks": [], "rollback": "Revert implementation commit",
        }
        self.write_json(self.report_path, self.report)

    def git(self, *args):
        result = subprocess.run(["git", *args], cwd=self.repo, capture_output=True, check=True)
        return result.stdout.decode().strip()

    def write(self, path, value):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value, encoding="utf-8")

    def write_json(self, path, value):
        self.write(path, json.dumps(value, indent=2) + "\n")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "Test fixture")

    def run_cli(self, *args, ok=True, contains=None, cwd=None):
        result = subprocess.run([sys.executable, str(CLI), *args], cwd=cwd or self.repo, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0 if ok else 1, result.stdout + result.stderr)
        if contains:
            self.assertIn(contains, result.stdout + result.stderr)
        return result

    def packet(self, ok=True, contains=None):
        return self.run_cli("packet", "--task", TASK, "--base", self.base, "--head", self.head,
                            "--report", self.report_path, "--output", self.packet_path, ok=ok, contains=contains)

    def make_review(self, verdict="PASS"):
        manifest_path = self.repo / self.packet_path / "manifest.json"
        manifest_raw = manifest_path.read_bytes()
        manifest = json.loads(manifest_raw)
        review = {key: manifest[key] for key in
                  ("schema_version", "task_id", "spec_version", "head_sha", "base_sha", "spec_sha256")}
        review.update(manifest_sha256=hashlib.sha256(manifest_raw).hexdigest(), verdict=verdict,
                      acceptance=[{"id": "AC-1", "status": "PASS", "evidence": "Diff matches AC and tests"}],
                      findings=[], reviewed_by="GPT via manual session")
        self.write_json(self.review_path, review)
        return review

    def review(self, ok=True, contains=None, base=None, head=None):
        return self.run_cli("review", "--packet", self.packet_path, "--review", self.review_path,
                            "--head", head or self.head, "--base", base or self.base, ok=ok, contains=contains)

    def ci(self, ok=True, contains=None, body=None):
        event = {"pull_request": {"body": body if body is not None else "AI-Task: " + TASK,
                                  "base": {"sha": self.base}, "head": {"sha": self.git("rev-parse", "HEAD")}}}
        self.write_json(".ai-workflow/event.json", event)
        return self.run_cli("ci", "--event", ".ai-workflow/event.json", ok=ok, contains=contains)

    def update_spec(self, **changes):
        self.spec.update(changes)
        self.write_json(TASK, self.spec)
        self.commit()
        self.head = self.git("rev-parse", "HEAD")
        self.report.update(head_sha=self.head, spec_version=self.spec["spec_version"])
        self.write_json(self.report_path, self.report)

    def test_happy_path_validates_scope_packet_ci_and_review(self):
        self.run_cli("validate")
        self.run_cli("scope", "--task", TASK, "--base", self.base)
        self.ci()
        self.packet()
        self.make_review()
        self.review()
        manifest = json.loads((self.repo / self.packet_path / "manifest.json").read_text())
        self.assertEqual(manifest["changed_files"], ["src/greeting.py"])
        self.assertEqual(manifest["head_sha"], self.head)

    def test_scope_rejects_unapproved_file_and_deleted_file(self):
        self.write("unapproved.txt", "unexpected")
        self.commit()
        self.run_cli("scope", "--task", TASK, "--base", self.base, ok=False, contains="out-of-scope")
        added_head = self.git("rev-parse", "HEAD")
        self.git("rm", "unapproved.txt")
        self.commit()
        self.run_cli("scope", "--task", TASK, "--base", added_head, ok=False, contains="out-of-scope")

    def test_scope_uses_committed_spec_not_dirty_allowlist(self):
        self.write("unapproved.txt", "unexpected")
        self.commit()
        self.spec["allowed_paths"] = ["**"]
        self.write_json(TASK, self.spec)
        self.run_cli("scope", "--task", TASK, "--base", self.base, ok=False, contains="out-of-scope")

    def test_duplicate_acceptance_id_rejected(self):
        self.spec["acceptance_criteria"] *= 2
        self.write_json(TASK, self.spec)
        self.run_cli("validate", "--task", TASK, ok=False, contains="duplicate id")

    def test_ready_placeholder_and_unsafe_path_rejected(self):
        self.spec["objective"] = "TODO"
        self.write_json(TASK, self.spec)
        self.run_cli("validate", "--task", TASK, ok=False, contains="placeholder")
        self.spec["objective"] = "Return a greeting"
        self.spec["allowed_paths"] = ["../**"]
        self.write_json(TASK, self.spec)
        self.run_cli("validate", "--task", TASK, ok=False, contains="unsafe path")

    def test_changed_spec_needs_version_bump(self):
        self.update_spec(title="Improve greeting")
        self.ci(ok=False, contains="version bump")
        self.update_spec(spec_version=2)
        self.ci()

    def test_ready_spec_accepts_java_generics(self):
        self.spec["architecture"] = ["Keep Map<String, Event> in the reminder service"]
        self.write_json(TASK, self.spec)
        self.run_cli("validate", "--task", TASK)

    def test_ci_rejects_deleting_an_existing_versioned_spec(self):
        self.update_spec(spec_version=2, allowed_paths=["src/**", "docs/ai/tasks/*.json"])
        other = copy.deepcopy(self.spec)
        other["task_id"] = "TASK-0002"
        self.write_json("docs/ai/tasks/TASK-0002.json", other)
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        self.git("rm", "docs/ai/tasks/TASK-0002.json")
        self.commit()
        self.ci(ok=False, contains="cannot be deleted")

    def test_ci_rejects_missing_duplicate_and_malformed_markers(self):
        for body in ("No task", "AI-Task: " + TASK + "\nAI-Task: " + TASK, "AI-Task: ../escape.json"):
            with self.subTest(body=body):
                self.ci(ok=False, body=body)

    def test_required_check_failure_rejected(self):
        self.report["checks"][0]["status"] = "FAIL"
        self.write_json(self.report_path, self.report)
        self.packet(ok=False, contains="required check must PASS")

    def test_missing_check_and_command_mismatch_rejected(self):
        self.report["checks"][0]["command"] = "echo fake"
        self.write_json(self.report_path, self.report)
        self.packet(ok=False, contains="command mismatch")
        self.report["checks"] = []
        self.write_json(self.report_path, self.report)
        self.packet(ok=False, contains="cover all ids")

    def test_oversized_diff_rejected_without_truncation(self):
        self.update_spec(spec_version=2, review_budget={"max_diff_bytes": 1})
        self.packet(ok=False, contains="exceeds review budget")
        self.assertFalse((self.repo / self.packet_path).exists())

    def test_dirty_worktree_rejected(self):
        self.write("src/local.py", "uncommitted")
        self.packet(ok=False, contains="clean worktree")

    def test_sensitive_path_rejected_even_when_scope_allows_it(self):
        self.update_spec(spec_version=2, allowed_paths=["**"])
        self.write("config/local-secret.properties", "fictional fixture")
        self.commit()
        self.head = self.git("rev-parse", "HEAD")
        self.report["head_sha"] = self.head
        self.write_json(self.report_path, self.report)
        self.packet(ok=False, contains="sensitive path")

    def test_stale_head_rejected(self):
        self.packet()
        self.make_review()
        self.write("src/next.py", "new code")
        self.commit()
        self.review(ok=False, head="HEAD", contains="head_sha")

    def test_packet_is_stable_across_ambient_diff_settings(self):
        lines = [f"# context {index}" for index in range(30)]
        self.write("src/greeting.py", "\n".join(lines) + "\n")
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        lines[3] = "# changed first"
        lines[18] = "# changed second"
        self.write("src/greeting.py", "\n".join(lines) + "\n")
        self.write("src/another.py", "# another changed file\n")
        self.write("src/é.py", "# Unicode path quoting\n")
        self.commit()
        self.head = self.git("rev-parse", "HEAD")
        self.report["head_sha"] = self.head
        self.write_json(self.report_path, self.report)
        self.packet()
        self.make_review()
        for key, value in (("diff.context", "0"), ("diff.noprefix", "true"), ("color.ui", "always"),
                           ("diff.algorithm", "histogram"), ("diff.statGraphWidth", "3"),
                           ("diff.relative", "true"), ("diff.suppressBlankEmpty", "true"),
                           ("diff.interHunkContext", "12"), ("diff.outputIndicatorNew", "!"),
                           ("core.quotePath", "false")):
            self.git("config", key, value)
        self.write(".ai-workflow/order.txt", "src/greeting.py\n")
        self.git("config", "diff.orderFile", ".ai-workflow/order.txt")
        self.review()

    def test_commands_use_root_relative_paths_from_a_subdirectory(self):
        subdir = self.repo / "src"
        self.git("config", "diff.relative", "true")
        self.run_cli("validate", "--task", TASK, cwd=subdir)
        self.run_cli("scope", "--task", TASK, "--base", self.base, cwd=subdir)
        self.run_cli("packet", "--task", TASK, "--base", self.base, "--report", self.report_path,
                     "--output", self.packet_path, cwd=subdir)
        manifest = json.loads((self.repo / self.packet_path / "manifest.json").read_text())
        self.assertEqual(manifest["changed_files"], ["src/greeting.py"])
        self.make_review()
        self.run_cli("review", "--packet", self.packet_path, "--review", self.review_path,
                     "--head", "HEAD", "--base", self.base, cwd=subdir)

    def test_stale_base_rejected(self):
        self.packet()
        self.make_review()
        self.review(ok=False, base=self.head, contains="stale or altered")

    def test_diff_and_report_hash_tampering_rejected(self):
        self.packet()
        self.make_review()
        diff = self.repo / self.packet_path / "changes.diff"
        original = diff.read_bytes()
        diff.write_bytes(original + b"tamper")
        self.review(ok=False, contains="altered packet file")
        diff.write_bytes(original)
        report = self.repo / self.packet_path / "report.json"
        report.write_bytes(report.read_bytes() + b" ")
        self.review(ok=False, contains="stale or altered")

    def test_review_manifest_binding_and_pass_requirements(self):
        self.packet()
        review = self.make_review()
        review["manifest_sha256"] = "0" * 64
        self.write_json(self.review_path, review)
        self.review(ok=False, contains="manifest hash")
        review = self.make_review()
        review["findings"] = ["Unresolved behavior mismatch"]
        self.write_json(self.review_path, review)
        self.review(ok=False, contains="no unresolved findings")
        for verdict in ("REVISE", "BLOCKED"):
            self.make_review(verdict)
            self.review(ok=False, contains="GPT verdict: " + verdict)

    def test_command_injection_ref_is_not_executed(self):
        self.run_cli("scope", "--task", TASK, "--base", "$(touch INJECTED)", ok=False)
        self.assertFalse((self.repo / "INJECTED").exists())
        self.run_cli("scope", "--task", TASK, "--base=-bad", ok=False, contains="invalid Git ref")


if __name__ == "__main__":
    unittest.main()
