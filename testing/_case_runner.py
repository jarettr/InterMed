#!/usr/bin/env python3
"""Small artifact-producing case runner for InterMed beta-prep suites."""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


OUTCOMES = {"pass", "fail", "unsupported", "not-run", "blocked"}
REQUIRED_DEFAULT = [
    "run-manifest.json",
    "environment.json",
    "command.txt",
    "exit-status.json",
    "stdout.log",
    "stderr.log",
    "mod-list.json",
    "result.json",
]


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run_date() -> str:
    return os.environ.get("INTERMED_TEST_DATE") or datetime.now(timezone.utc).strftime("%Y%m%d")


def root_dir() -> Path:
    return Path(__file__).resolve().parents[1]


def git_commit(root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
    except Exception:
        return "unknown"


def suite_dir(root: Path, suite: str, out_root: str | None) -> Path:
    if out_root:
        return Path(out_root).expanduser().resolve()
    return root / "build" / "test-runs" / run_date() / suite


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def java_version(root: Path) -> list[str]:
    java_bin = os.environ.get("JAVA_BIN") or os.environ.get("INTERMED_JAVA") or "java"
    try:
        result = subprocess.run(
            [java_bin, "-version"],
            cwd=root,
            text=True,
            capture_output=True,
            check=False,
        )
        return (result.stderr or result.stdout).strip().splitlines()
    except Exception as exc:
        return [f"java-version-error: {exc}"]


def mod_list(mods_dir: str | None) -> dict:
    if not mods_dir:
        return {"schema": "intermed-mod-list-v1", "mods": []}
    path = Path(mods_dir).expanduser()
    mods = []
    if path.exists():
        for candidate in sorted(path.glob("*.jar")):
            mods.append({
                "file": candidate.name,
                "size": candidate.stat().st_size,
            })
    return {
        "schema": "intermed-mod-list-v1",
        "mods_dir": str(path),
        "mods": mods,
    }


def write_common_artifacts(
    case_dir: Path,
    root: Path,
    suite: str,
    case_id: str,
    name: str,
    command: str,
    started_at: str,
    mods_dir: str | None,
) -> None:
    case_dir.mkdir(parents=True, exist_ok=True)
    commit = git_commit(root)
    (case_dir / "command.txt").write_text(command + "\n", encoding="utf-8")
    write_json(case_dir / "run-manifest.json", {
        "schema": "intermed-run-manifest-v1",
        "case_id": case_id,
        "name": name,
        "suite": suite,
        "started_at": started_at,
        "commit": commit,
        "artifact_contract": "docs/test-plan-v8-alpha-to-beta.md",
    })
    write_json(case_dir / "environment.json", {
        "schema": "intermed-environment-v1",
        "env_id": os.environ.get("INTERMED_ENV_ID", "unknown"),
        "machine_class": os.environ.get("INTERMED_MACHINE_CLASS", "unknown"),
        "minecraft": "1.20.1",
        "java_baseline": "21",
        "os": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
        "java_version": java_version(root),
        "commit": commit,
    })
    write_json(case_dir / "mod-list.json", mod_list(mods_dir))


def write_finish(
    case_dir: Path,
    case_id: str,
    name: str,
    outcome: str,
    reason: str,
    exit_code: int,
    started_at: str,
    ended_at: str,
) -> None:
    write_json(case_dir / "exit-status.json", {
        "schema": "intermed-exit-status-v1",
        "exit_code": exit_code,
        "started_at": started_at,
        "ended_at": ended_at,
    })
    write_json(case_dir / "result.json", {
        "schema": "intermed-case-result-v1",
        "case_id": case_id,
        "name": name,
        "outcome": outcome,
        "reason": reason,
        "exit_code": exit_code,
    })


def enforce_required(case_dir: Path, outcome: str, reason: str, required: list[str]) -> tuple[str, str]:
    if outcome != "pass":
        return outcome, reason
    missing = [item for item in required if not (case_dir / item).exists()]
    if not missing:
        return outcome, reason
    suffix = "missing required artifacts: " + ", ".join(missing)
    return "fail", f"{reason}; {suffix}" if reason else suffix


def run_case(args: argparse.Namespace) -> int:
    root = root_dir()
    out = suite_dir(root, args.suite, args.out_root)
    case_dir = out / args.case_id
    started_at = utc_now()
    write_common_artifacts(
        case_dir,
        root,
        args.suite,
        args.case_id,
        args.name,
        args.command,
        started_at,
        args.mods_dir,
    )

    result = subprocess.run(
        args.command,
        cwd=root,
        shell=True,
        executable="/bin/bash",
        text=True,
        capture_output=True,
        check=False,
    )
    (case_dir / "stdout.log").write_text(result.stdout or "", encoding="utf-8")
    (case_dir / "stderr.log").write_text(result.stderr or "", encoding="utf-8")

    if args.expect_nonzero:
        ok = result.returncode != 0
    else:
        ok = result.returncode == 0

    if ok:
        outcome = args.success_outcome
        reason = args.success_reason or "command completed successfully"
    else:
        outcome = "fail"
        reason = args.failure_reason or f"command exited with {result.returncode}"

    ended_at = utc_now()
    write_finish(case_dir, args.case_id, args.name, outcome, reason, result.returncode, started_at, ended_at)
    required = REQUIRED_DEFAULT + list(args.require_file or [])
    outcome, reason = enforce_required(case_dir, outcome, reason, required)
    if outcome != json.loads((case_dir / "result.json").read_text(encoding="utf-8")).get("outcome"):
        write_finish(case_dir, args.case_id, args.name, outcome, reason, result.returncode, started_at, ended_at)
    return 0 if outcome in {"pass", "not-run", "unsupported", "blocked"} else 1


def mark_case(args: argparse.Namespace) -> int:
    if args.outcome not in OUTCOMES:
        raise SystemExit(f"invalid outcome: {args.outcome}")
    root = root_dir()
    out = suite_dir(root, args.suite, args.out_root)
    case_dir = out / args.case_id
    started_at = utc_now()
    command = args.command or f"# case marked {args.outcome}: {args.reason}"
    write_common_artifacts(
        case_dir,
        root,
        args.suite,
        args.case_id,
        args.name,
        command,
        started_at,
        args.mods_dir,
    )
    (case_dir / "stdout.log").write_text(args.stdout or "", encoding="utf-8")
    (case_dir / "stderr.log").write_text(args.stderr or "", encoding="utf-8")
    ended_at = utc_now()
    write_finish(case_dir, args.case_id, args.name, args.outcome, args.reason, args.exit_code, started_at, ended_at)
    return 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command_name", required=True)

    run = sub.add_parser("run")
    run.add_argument("--suite", required=True)
    run.add_argument("--case-id", required=True)
    run.add_argument("--name", required=True)
    run.add_argument("--command", required=True)
    run.add_argument("--out-root")
    run.add_argument("--mods-dir")
    run.add_argument("--require-file", action="append", default=[])
    run.add_argument("--expect-nonzero", action="store_true")
    run.add_argument("--success-outcome", choices=sorted(OUTCOMES), default="pass")
    run.add_argument("--success-reason", default="")
    run.add_argument("--failure-reason", default="")
    run.set_defaults(func=run_case)

    mark = sub.add_parser("mark")
    mark.add_argument("--suite", required=True)
    mark.add_argument("--case-id", required=True)
    mark.add_argument("--name", required=True)
    mark.add_argument("--outcome", required=True, choices=sorted(OUTCOMES))
    mark.add_argument("--reason", required=True)
    mark.add_argument("--out-root")
    mark.add_argument("--mods-dir")
    mark.add_argument("--command", default="")
    mark.add_argument("--stdout", default="")
    mark.add_argument("--stderr", default="")
    mark.add_argument("--exit-code", type=int, default=0)
    mark.set_defaults(func=mark_case)
    return root


def main(argv: list[str]) -> int:
    args = parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
