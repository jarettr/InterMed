#!/usr/bin/env python3
"""Import real harness boot results into BOOT beta-prep case artifacts."""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path


BOOT_CASES = {
    "FABRIC": ("BOOT-001", "minimal-fabric-dedicated-server"),
    "FORGE": ("BOOT-002", "minimal-forge-dedicated-server"),
    "NEOFORGE": ("BOOT-003", "minimal-neoforge-dedicated-server"),
}


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def root_dir() -> Path:
    return Path(__file__).resolve().parents[1]


def run_date() -> str:
    return os.environ.get("INTERMED_TEST_DATE") or datetime.now(timezone.utc).strftime("%Y%m%d")


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def git_commit(root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
    except Exception:
        return "unknown"


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


def rel(root: Path, path: Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def load_results(paths: list[Path]) -> list[dict]:
    collected = []
    for path in paths:
        payload = json.loads(path.read_text(encoding="utf-8"))
        for result in payload.get("results", []):
            collected.append({
                **result,
                "_source": path,
                "_runMetadata": payload.get("runMetadata", {}),
                "_generatedAt": payload.get("generatedAt"),
                "_sourceMtime": path.stat().st_mtime,
            })
    return collected


def is_ready_boot(result: dict) -> bool:
    if not result.get("passed"):
        return False
    if result.get("exitCode") != 0:
        return False
    snippet = result.get("logSnippet", "")
    if "Done (" in snippet:
        return True
    return any(issue.get("tag") == "STARTUP_TIME" for issue in result.get("issues", []))


def result_timestamp(result: dict) -> str:
    for key in ("executedAt", "_generatedAt"):
        value = result.get(key)
        if not value:
            continue
        try:
            return datetime.fromisoformat(str(value).replace("Z", "+00:00")).isoformat()
        except ValueError:
            continue
    return f"{float(result.get('_sourceMtime', 0.0)):020.6f}"


def select_by_loader(results: list[dict]) -> dict[str, dict]:
    selected = {}
    for result in results:
        loader = str(result.get("loader", "")).upper()
        if loader not in BOOT_CASES:
            continue
        current = selected.get(loader)
        if current is None or result_timestamp(result) >= result_timestamp(current):
            selected[loader] = result
    return selected


def classify_boot_result(root: Path, result: dict) -> tuple[str, str]:
    source = Path(result["_source"])
    base_reason = f"imported harness dedicated-server boot for {str(result.get('loader', '')).lower()} from {rel(root, source)}"
    if is_ready_boot(result):
        return "pass", base_reason

    issue_tags = [issue.get("tag") for issue in result.get("issues", []) if issue.get("tag")]
    harness_outcome = result.get("outcome", "UNKNOWN")
    if issue_tags:
        return "fail", f"{base_reason}; harness outcome={harness_outcome}; issue_tags={','.join(issue_tags[:5])}"
    return "fail", f"{base_reason}; harness outcome={harness_outcome}; server never reached ready state"


def write_case(root: Path, out_root: Path, suite: str, loader: str, result: dict) -> None:
    case_id, default_name = BOOT_CASES[loader]
    case_dir = out_root / case_id
    case_dir.mkdir(parents=True, exist_ok=True)
    started_at = utc_now()
    commit = git_commit(root)
    source = Path(result["_source"])
    outcome, reason = classify_boot_result(root, result)
    command = f"# imported harness boot result: {rel(root, source)}"

    (case_dir / "command.txt").write_text(command + "\n", encoding="utf-8")
    (case_dir / "stdout.log").write_text(result.get("logSnippet", "") + "\n", encoding="utf-8")
    (case_dir / "stderr.log").write_text("", encoding="utf-8")
    write_json(case_dir / "run-manifest.json", {
        "schema": "intermed-run-manifest-v1",
        "case_id": case_id,
        "name": default_name,
        "suite": suite,
        "started_at": started_at,
        "commit": commit,
        "artifact_contract": "docs/test-plan-v8-alpha-to-beta.md",
    })
    write_json(case_dir / "environment.json", {
        "schema": "intermed-environment-v1",
        "env_id": os.environ.get("INTERMED_ENV_ID", "unknown"),
        "machine_class": os.environ.get("INTERMED_MACHINE_CLASS", "unknown"),
        "minecraft": result.get("_runMetadata", {}).get("mcVersion", "1.20.1"),
        "java_baseline": "21",
        "os": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
        },
        "java_version": java_version(root),
        "commit": commit,
    })
    write_json(case_dir / "exit-status.json", {
        "schema": "intermed-exit-status-v1",
        "exit_code": int(result.get("exitCode", 0)),
        "started_at": started_at,
        "ended_at": started_at,
    })
    write_json(case_dir / "mod-list.json", {
        "schema": "intermed-mod-list-v1",
        "mods": result.get("mods", []),
    })
    write_json(case_dir / "startup-report.json", {
        "schema": "intermed-harness-boot-startup-report-v1",
        "case_id": case_id,
        "loader": loader,
        "evidence_level": "harness-tested",
        "outcome": outcome,
        "source_results": rel(root, source),
        "harness_result_id": result.get("id"),
        "harness_outcome": result.get("outcome"),
        "description": result.get("description"),
        "startup_ms": result.get("startupMs"),
        "exit_code": result.get("exitCode"),
        "executed_at": result.get("executedAt"),
        "issues": result.get("issues", []),
        "run_metadata": result.get("_runMetadata", {}),
        "limitations": [
            "This proves one dedicated-server harness boot for the loader family.",
            "It does not prove mixed-loader boot, client menu entry, login, registry sync, or broad corpus compatibility.",
        ],
    })
    write_json(case_dir / "compatibility-report.json", {
        "schema": "intermed-harness-boot-compatibility-report-v1",
        "case_id": case_id,
        "loader": loader,
        "outcome": outcome,
        "source_results": rel(root, source),
        "run_metadata": result.get("_runMetadata", {}),
        "result": {
            key: value for key, value in result.items() if not key.startswith("_")
        },
    })
    write_json(case_dir / "result.json", {
        "schema": "intermed-case-result-v1",
        "case_id": case_id,
        "name": default_name,
        "outcome": outcome,
        "reason": reason,
        "exit_code": int(result.get("exitCode", 0)),
    })


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", default="nightly-smoke")
    parser.add_argument("--out-root")
    parser.add_argument("--results", action="append", required=True, help="Path to harness results-booted.json")
    args = parser.parse_args()

    root = root_dir()
    out_root = Path(args.out_root).expanduser().resolve() if args.out_root else (
        root / "build" / "test-runs" / run_date() / "imported-harness-boot"
    )
    paths = [Path(item).expanduser().resolve() for item in args.results]
    results = load_results(paths)
    selected = select_by_loader(results)
    out_root.mkdir(parents=True, exist_ok=True)
    for loader, result in selected.items():
        write_case(root, out_root, args.suite, loader, result)
    subprocess.run(
        [str(root / "testing" / "collect_artifacts.sh"), args.suite, str(out_root)],
        cwd=root,
        check=True,
    )
    print(out_root / "suite-summary.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
