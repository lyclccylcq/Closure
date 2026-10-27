#!/usr/bin/env python3
"""Offline task contracts and head-bound review packets; Python standard library only.

Reports and review records are manual evidence, not authenticated reviewer identity.
This program never executes check commands, calls models, or contacts the network.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath


class ContractError(ValueError):
    pass


def need(condition, message):
    if not condition:
        raise ContractError(message)


def git(*args, optional=False):
    result = subprocess.run(["git", *args], capture_output=True, check=False)
    if result.returncode:
        if optional:
            return None
        raise ContractError("git failed: " + result.stderr.decode(errors="replace").strip())
    return result.stdout


def root():
    return Path(git("rev-parse", "--show-toplevel").decode().strip())


def diff(*args):
    """Use stable root-relative patch formatting, independent of user Git settings."""
    return git("-c", "core.quotePath=true", "-c", "diff.suppressBlankEmpty=false", "diff",
               "--no-ext-diff", "--no-textconv", "--no-renames", "--no-relative", "--no-color",
               "--unified=3", "--inter-hunk-context=0", "--src-prefix=a/", "--dst-prefix=b/",
               "--line-prefix=", "--diff-algorithm=myers", "--no-indent-heuristic", "--full-index",
               "--no-color-moved", "--word-diff=none", "--ignore-submodules=none", "--submodule=short",
               "--output-indicator-new=+", "--output-indicator-old=-", "--output-indicator-context= ",
               "-O", os.devnull, *args)


def commit(ref):
    need(isinstance(ref, str) and ref and not ref.startswith("-") and not re.search(r"[\x00-\x20]", ref),
         "invalid Git ref")
    return git("rev-parse", "--verify", "--end-of-options", ref + "^{commit}").decode().strip()


def safe_path(value):
    return (isinstance(value, str) and bool(value) and not value.startswith("/")
            and not re.search(r"[\\:\x00-\x1f\x7f]", value)
            and all(part not in ("", ".", "..") for part in value.split("/")))


def task_path(value):
    need(safe_path(value), "task path must be safe and relative to repository root")
    need(re.fullmatch(r"docs/ai/tasks/TASK-[0-9]{4,}\.json", value),
         "task path must be docs/ai/tasks/TASK-NNNN.json")
    return value


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, "duplicate JSON key: " + key)
        result[key] = value
    return result


def read_json(raw):
    try:
        value = json.loads(raw, object_pairs_hook=no_duplicate_keys)
    except (ValueError, UnicodeError) as exc:
        raise ContractError("invalid JSON: " + str(exc)) from exc
    need(isinstance(value, dict), "JSON document must be an object")
    return value


def text(value):
    return isinstance(value, str) and bool(value.strip())


def strings(value, nonempty=False):
    return isinstance(value, list) and (bool(value) or not nonempty) and all(text(x) for x in value)


def positive(value):
    return type(value) is int and value > 0


def rows(value, prefix, fields):
    need(isinstance(value, list) and value, prefix + " entries must be a nonempty array")
    ids = set()
    for item in value:
        need(isinstance(item, dict), prefix + " entry must be an object")
        ident = item.get("id")
        need(isinstance(ident, str) and re.fullmatch(prefix + r"-[1-9][0-9]*", ident),
             "invalid " + prefix + " id")
        need(ident not in ids, "duplicate id: " + ident)
        ids.add(ident)
        for field in fields:
            need(text(item.get(field)), ident + " needs " + field)
    return ids


def validate_spec(spec, path=None, template=False):
    need(spec.get("schema_version") == 1 and type(spec.get("schema_version")) is int,
         "unsupported spec schema_version")
    need(isinstance(spec.get("task_id"), str) and re.fullmatch(r"TASK-[0-9]{4,}", spec["task_id"]),
         "invalid task_id")
    need(positive(spec.get("spec_version")), "spec_version must be a positive integer")
    need(spec.get("status") in ("DRAFT", "READY"), "status must be DRAFT or READY")
    for field in ("title", "objective", "rollback"):
        need(text(spec.get(field)), field + " must be a nonempty string")
    for field in ("architecture", "non_goals", "risks"):
        need(strings(spec.get(field), nonempty=field == "architecture"), field + " must be a string array")
    need(strings(spec.get("allowed_paths"), nonempty=True), "allowed_paths must be a nonempty string array")
    need(all(safe_path(x) for x in spec["allowed_paths"]), "allowed_paths contains an unsafe path")
    rows(spec.get("acceptance_criteria"), "AC", ("requirement", "verification"))
    rows(spec.get("checks"), "CHECK", ("command",))
    need(all(type(x.get("required")) is bool for x in spec["checks"]), "checks need a boolean required")
    budget = spec.get("review_budget")
    need(isinstance(budget, dict) and positive(budget.get("max_diff_bytes")),
         "review_budget.max_diff_bytes must be a positive integer")
    if path and not template:
        need(PurePosixPath(path).stem == spec["task_id"], "task_id does not match filename")
    if spec["status"] == "READY":
        substantive = {key: spec[key] for key in ("title", "objective", "architecture", "non_goals",
                       "allowed_paths", "acceptance_criteria", "checks", "risks", "rollback")}
        need(not re.search(r"\b(?:TODO|TBD|PLACEHOLDER)\b|\{\{", json.dumps(substantive), re.I),
             "READY spec contains a placeholder")
    return spec


def at(sha, path, optional=False):
    need(safe_path(path), "unsafe repository path")
    return git("show", sha + ":" + path, optional=optional)


def snapshots(base_ref, head_ref):
    base, head = commit(base_ref), commit(head_ref)
    ancestor = git("merge-base", base, head).decode().strip()
    need(bool(ancestor), "base and head have no merge base")
    return base, ancestor, head


def scope(path, base_ref, head_ref):
    path = task_path(path)
    base, ancestor, head = snapshots(base_ref, head_ref)
    raw = at(head, path)
    spec = validate_spec(read_json(raw), path)
    need(spec["status"] == "READY", "implementation requires a READY spec")
    changed = diff("--name-only", "-z", ancestor, head)
    try:
        paths = [p.decode("utf-8") for p in changed.split(b"\0") if p]
    except UnicodeError as exc:
        raise ContractError("changed paths must use UTF-8") from exc
    for name in paths:
        need(safe_path(name), "unsafe changed path")
        need(name == path or any(fnmatch.fnmatchcase(name, pattern) for pattern in spec["allowed_paths"]),
             "out-of-scope change: " + name)
    return spec, raw, base, ancestor, head, paths


def validate_local(args):
    repo = root()
    if args.task:
        path = task_path(args.task)
        validate_spec(read_json((repo / path).read_bytes()), path)
        print("Spec valid: " + path)
        return
    paths = sorted((repo / "docs/ai/tasks").glob("*.json"))
    template = repo / "docs/ai/templates/task.spec.json"
    need(template.is_file(), "missing docs/ai/templates/task.spec.json")
    for path in [template, *paths]:
        validate_spec(read_json(path.read_bytes()), path.name, path == template)
    print(f"Validated {len(paths)} task specs and the template")


def spec_files(head):
    names = git("ls-tree", "-r", "--name-only", "-z", head, "--", "docs/ai/tasks", "docs/ai/templates/task.spec.json")
    return [x.decode() for x in names.split(b"\0") if x and
            (x == b"docs/ai/templates/task.spec.json" or re.fullmatch(rb"docs/ai/tasks/[^/]+\.json", x))]


def validate_ci(args):
    event = read_json(Path(args.event).read_bytes())
    pr = event.get("pull_request")
    need(isinstance(pr, dict), "event must contain pull_request")
    body = pr.get("body") or ""
    need(isinstance(body, str), "pull request body must be text")
    markers = [line for line in body.splitlines() if line.startswith("AI-Task:")]
    need(len(markers) == 1, "PR body needs exactly one AI-Task: line")
    match = re.fullmatch(r"AI-Task: (docs/ai/tasks/TASK-[0-9]{4,}\.json)", markers[0])
    need(match is not None, "invalid AI-Task: line")
    try:
        result = scope(match[1], pr["base"]["sha"], pr["head"]["sha"])
    except (KeyError, TypeError) as exc:
        raise ContractError("event lacks base/head SHA") from exc
    _, _, _, ancestor, head, _ = result
    names = spec_files(head)
    need("docs/ai/templates/task.spec.json" in names, "missing Spec template at head")
    removed = set(spec_files(ancestor)) - set(names)
    need(not removed, "versioned task Specs and template cannot be deleted")
    for name in names:
        template = name == "docs/ai/templates/task.spec.json"
        new_raw = at(head, name)
        spec = validate_spec(read_json(new_raw), name, template)
        old_raw = at(ancestor, name, optional=True)
        if not template and old_raw is not None and old_raw != new_raw:
            old = validate_spec(read_json(old_raw), name)
            need(spec["spec_version"] > old["spec_version"], "changed spec needs a version bump: " + name)
    print("AI workflow contract and scope PASS (not GPT approval)")


def status_rows(value, expected, label):
    need(isinstance(value, list), label + " must be an array")
    got = {}
    for item in value:
        need(isinstance(item, dict) and item.get("id") in expected, label + " has unknown id")
        ident = item["id"]
        need(ident not in got, "duplicate " + label + " id: " + ident)
        need(item.get("status") in ("PASS", "FAIL", "BLOCKED") and text(item.get("evidence")),
             label + " needs status and evidence: " + ident)
        got[ident] = item
    need(set(got) == set(expected), label + " must cover all ids")
    return got


def validate_report(report, spec, head):
    for field, expected in (("schema_version", 1), ("task_id", spec["task_id"]),
                            ("spec_version", spec["spec_version"]), ("head_sha", head)):
        need(type(report.get(field)) is type(expected) and report.get(field) == expected,
             "report mismatch: " + field)
    need(re.fullmatch(r"[0-9a-f]{40}", report["head_sha"]), "report needs a full head SHA")
    acceptance = status_rows(report.get("acceptance"), {x["id"] for x in spec["acceptance_criteria"]}, "acceptance")
    need(all(x["status"] == "PASS" for x in acceptance.values()), "all acceptance criteria must PASS")
    checks = status_rows(report.get("checks"), {x["id"] for x in spec["checks"]}, "checks")
    for check in spec["checks"]:
        result = checks[check["id"]]
        need(result.get("command") == check["command"], "check command mismatch: " + check["id"])
        need(not check["required"] or result["status"] == "PASS", "required check must PASS: " + check["id"])
    for field in ("deviations", "risks"):
        need(strings(report.get(field)), "report needs " + field + " string array")
    need(text(report.get("rollback")), "report needs rollback")


def sensitive(path):
    parts = PurePosixPath(path).parts
    name = parts[-1].lower()
    return (path.startswith("config/local-") or any(p.lower().startswith(".env") for p in parts)
            or name in ("id_rsa", "id_ed25519", "credentials", "credentials.json")
            or name.endswith((".pem", ".key", ".p12", ".pfx")))


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def packet_data(path, base_ref, head_ref, report_raw):
    spec, raw, base, ancestor, head, paths = scope(path, base_ref, head_ref)
    need(not any(sensitive(name) for name in paths), "potential sensitive path in diff; review locally")
    report = read_json(report_raw)
    validate_report(report, spec, head)
    patch = diff("--binary", ancestor, head)
    need(len(patch) <= spec["review_budget"]["max_diff_bytes"],
         f"diff exceeds review budget ({len(patch)} bytes); split task or approve a Spec revision")
    manifest = dict(schema_version=1, task_id=spec["task_id"], spec_version=spec["spec_version"],
                    spec_path=path, spec_sha256=digest(raw), base_sha=base, merge_base_sha=ancestor,
                    head_sha=head, report_sha256=digest(report_raw), diff_sha256=digest(patch),
                    changed_files=paths, diff_bytes=len(patch))
    stat = diff("--no-patch", "--stat=80,40", "--stat-graph-width=30", ancestor, head).decode()
    summary = (f"# {spec['task_id']} review packet\n\nSpec version: {spec['spec_version']}\n"
               f"Base: {base}\nMerge base: {ancestor}\nHead: {head}\n"
               f"Diff: {len(patch)} bytes; complete and untruncated.\n\n"
               "Read spec.json, report.json, and changes.diff together. Reports are self-reported evidence; "
               "verify code and tests independently. Filename filtering does not guarantee absence of secrets.\n\n"
               + stat).encode()
    return manifest, raw, patch, summary


def write_packet(args):
    need(commit("HEAD") == commit(args.head), "packet head must equal checked-out HEAD")
    need(not git("status", "--porcelain", "-z", "--untracked-files=all"),
         "packet requires a clean worktree; store reports/output under ignored .ai-workflow/")
    output = Path(args.output).resolve()
    need(not output.exists() or not any(output.iterdir()), "output directory must be absent or empty")
    report_raw = Path(args.report).read_bytes()
    manifest, raw, diff, summary = packet_data(args.task, args.base, args.head, report_raw)
    output.mkdir(parents=True, exist_ok=True)
    for name, data in (("spec.json", raw), ("report.json", report_raw),
                       ("changes.diff", diff), ("SUMMARY.md", summary),
                       ("manifest.json", (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode())):
        (output / name).write_bytes(data)
    print("Complete review packet: " + str(output))


def review_packet(args, require_pass=True):
    packet = Path(args.packet)
    manifest_raw = (packet / "manifest.json").read_bytes()
    manifest = read_json(manifest_raw)
    need(text(manifest.get("spec_path")), "manifest needs spec_path")
    expected, spec_raw, diff, summary = packet_data(manifest["spec_path"], args.base, args.head,
                                                  (packet / "report.json").read_bytes())
    need(manifest == expected, "stale or altered packet manifest")
    for name, raw in (("spec.json", spec_raw), ("changes.diff", diff), ("SUMMARY.md", summary)):
        need((packet / name).read_bytes() == raw, "altered packet file: " + name)
    review = read_json(Path(args.review).read_bytes())
    for field in ("schema_version", "task_id", "spec_version", "head_sha", "base_sha", "spec_sha256"):
        need(type(review.get(field)) is type(expected[field]) and review.get(field) == expected[field],
             "review mismatch: " + field)
    need(review.get("manifest_sha256") == digest(manifest_raw), "review manifest hash mismatch")
    need(review.get("verdict") in ("PASS", "REVISE", "BLOCKED"), "invalid review verdict")
    need(text(review.get("reviewed_by")), "review needs reviewed_by")
    acceptance = status_rows(review.get("acceptance"), {x["id"] for x in read_json(spec_raw)["acceptance_criteria"]},
                             "review acceptance")
    need(isinstance(review.get("findings"), list), "review needs findings array")
    if review["verdict"] == "PASS":
        need(all(x["status"] == "PASS" for x in acceptance.values()) and not review["findings"],
             "PASS needs all criteria PASS and no unresolved findings")
    if require_pass:
        need(review["verdict"] == "PASS", "GPT verdict: " + review["verdict"])
        print("Review record PASS for current head/base; manual evidence only. User merge approval still required.")
    return review


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate", help="validate local Specs (DRAFT allowed)")
    validate.add_argument("--task")
    validate.set_defaults(run=validate_local)
    scope_parser = commands.add_parser("scope", help="validate committed READY Spec and changed paths")
    scope_parser.add_argument("--task", required=True)
    scope_parser.add_argument("--base", required=True)
    scope_parser.add_argument("--head", default="HEAD")
    scope_parser.set_defaults(run=lambda a: (scope(a.task, a.base, a.head), print("Scope PASS")))
    ci = commands.add_parser("ci", help="validate PR event, scope and Spec versions")
    ci.add_argument("--event", required=True)
    ci.set_defaults(run=validate_ci)
    packet = commands.add_parser("packet", help="create complete offline review packet")
    for flag in ("task", "base", "report", "output"):
        packet.add_argument("--" + flag, required=True)
    packet.add_argument("--head", default="HEAD")
    packet.set_defaults(run=write_packet)
    review = commands.add_parser("review", help="verify a manual GPT verdict against current commits")
    for flag in ("packet", "review", "head", "base"):
        review.add_argument("--" + flag, required=True)
    review.set_defaults(run=review_packet)
    args = parser.parse_args(argv)
    try:
        os.chdir(root())
        args.run(args)
        return 0
    except (ContractError, OSError, UnicodeError) as exc:
        print("BLOCKED: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
