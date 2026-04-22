#!/usr/bin/env python3
"""Generate a beta-readiness dashboard summary from suite artifacts."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path


OUTCOMES = ["pass", "fail", "unsupported", "not-run", "blocked"]

SECTION_RULES = {
    "hard_gates": {
        "required_pass": ["GATE-001", "GATE-002", "GATE-003", "GATE-004"],
    },
    "mandatory_smoke": {
        "required_pass": ["BOOT-001", "BOOT-002", "BOOT-003", "BOOT-004", "BOOT-005"],
    },
    "client_server": {
        "required_pass": ["NET-001", "NET-002", "NET-003"],
        "public_required_pass": ["NET-004", "NET-005"],
    },
    "vfs_datapack": {
        "required_pass": ["VFS-002", "VFS-003", "VFS-004"],
    },
    "strict_security": {
        "required_pass": [
            "SEC-001",
            "SEC-002",
            "SEC-003",
            "SEC-004",
            "SEC-005",
            "SEC-006",
            "SEC-008",
        ],
    },
    "native_tccl": {
        "pass_or_unsupported": ["NATIVE-001", "NATIVE-002", "SEC-007", "SEC-009"],
    },
    "frozen_corpus": {
        "classified": ["CORPUS-001", "CORPUS-002", "CORPUS-003"],
    },
    "performance": {
        "required_pass": ["PERF-001", "PERF-002", "PERF-003", "PERF-004", "PERF-005"],
    },
    "soak": {
        "required_pass": ["SOAK-001", "SOAK-002"],
    },
}


def root_dir() -> Path:
    return Path(__file__).resolve().parents[1]


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_text(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(payload, encoding="utf-8")


def git_commit(root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
    except Exception:
        return "unknown"


def discover_summaries(root: Path, run_root: Path | None, explicit: list[str]) -> list[Path]:
    if explicit:
        return [Path(item).expanduser().resolve() for item in explicit]
    base = run_root or (root / "build" / "test-runs")
    if not base.exists():
        return []
    return sorted(base.glob("**/suite-summary.json"))


def latest_results(summaries: list[Path]) -> tuple[dict[str, dict], list[dict], dict[str, list[str]]]:
    by_case: dict[str, dict] = {}
    loaded = []
    missing_required: dict[str, list[str]] = {}
    for path in summaries:
        summary = load_json(path)
        summary_missing = summary.get("missing_required_artifacts", {})
        loaded.append({
            "path": str(path),
            "suite": summary.get("suite", "unknown"),
            "date": summary.get("date", "unknown"),
            "counts": summary.get("counts", {}),
        })
        for result in summary.get("results", []):
            case_id = result.get("case_id")
            if not case_id:
                continue
            by_case[case_id] = {
                **result,
                "summary_path": str(path),
                "suite": summary.get("suite", "unknown"),
                "date": summary.get("date", "unknown"),
            }
            if case_id in summary_missing:
                missing_required[case_id] = list(summary_missing[case_id])
            else:
                missing_required.pop(case_id, None)
    return by_case, loaded, missing_required


def waiver_state(root: Path, path: Path | None) -> dict:
    waiver_path = path or (root / "testing" / "waivers.json")
    if not waiver_path.exists():
        return {"active": [], "expired": []}
    payload = load_json(waiver_path)
    today = date.today()
    active = []
    expired = []
    for waiver in payload.get("waivers", []):
        expiry = waiver.get("expiry_date")
        try:
            expiry_date = datetime.strptime(expiry, "%Y-%m-%d").date() if expiry else today
        except ValueError:
            expiry_date = today
        if expiry_date < today:
            expired.append(waiver)
        else:
            active.append(waiver)
    return {"active": active, "expired": expired}


def candidate_ids(root: Path, path: Path | None) -> list[str]:
    candidate_path = path or (root / "testing" / "frozen-beta-candidate.json")
    if candidate_path.exists():
        return list(load_json(candidate_path).get("case_ids", []))
    catalog = load_json(root / "testing" / "test-cases.json")
    return [case["id"] for case in catalog.get("cases", []) if case.get("beta_critical")]


def outcome_for(case_id: str, results: dict[str, dict]) -> str:
    return results.get(case_id, {}).get("outcome", "not-run")


def section_color(rule: dict, results: dict[str, dict], expired_waivers: list[dict]) -> str:
    relevant_ids = set(rule.get("required_pass", []))
    relevant_ids.update(rule.get("pass_or_unsupported", []))
    relevant_ids.update(rule.get("classified", []))
    relevant_ids.update(rule.get("public_required_pass", []))
    waived_expired = {
        cid
        for waiver in expired_waivers
        for cid in waiver.get("case_ids", []) + ([waiver.get("case_id")] if waiver.get("case_id") else [])
    }
    if relevant_ids & waived_expired:
        return "red"

    for case_id in rule.get("required_pass", []):
        if outcome_for(case_id, results) in {"fail", "blocked", "not-run"}:
            return "red"
    for case_id in rule.get("pass_or_unsupported", []):
        if outcome_for(case_id, results) in {"fail", "blocked", "not-run"}:
            return "red"
    for case_id in rule.get("classified", []):
        if outcome_for(case_id, results) in {"blocked", "not-run"}:
            return "red"
    for case_id in rule.get("public_required_pass", []):
        if outcome_for(case_id, results) != "pass":
            return "yellow"

    yellow_sensitive = set(rule.get("required_pass", []))
    yellow_sensitive.update(rule.get("public_required_pass", []))
    has_yellow = False
    for case_id in yellow_sensitive:
        if outcome_for(case_id, results) == "unsupported":
            has_yellow = True
    return "yellow" if has_yellow else "green"


def frozen_candidate_color(
    candidates: list[str],
    results: dict[str, dict],
    missing_required: dict[str, list[str]],
    expired_waivers: list[dict],
) -> str:
    waived_expired = {
        cid
        for waiver in expired_waivers
        for cid in waiver.get("case_ids", []) + ([waiver.get("case_id")] if waiver.get("case_id") else [])
    }
    if any(case_id in waived_expired for case_id in candidates):
        return "red"
    if any(case_id in missing_required for case_id in candidates):
        return "red"

    for case_id in candidates:
        result = results.get(case_id)
        outcome = result.get("outcome", "not-run") if result else "not-run"
        if outcome in {"fail", "blocked", "not-run"}:
            return "red"
        if outcome == "unsupported":
            if not result.get("reason"):
                return "yellow"

    return "green"


def build_dashboard(args: argparse.Namespace) -> dict:
    root = root_dir()
    summaries = discover_summaries(root, Path(args.run_root).resolve() if args.run_root else None, args.summary)
    results, loaded_summaries, missing_required = latest_results(summaries)
    candidates = candidate_ids(root, Path(args.candidate).resolve() if args.candidate else None)
    waivers = waiver_state(root, Path(args.waivers).resolve() if args.waivers else None)

    candidate_counts = Counter({outcome: 0 for outcome in OUTCOMES})
    missing_results = []
    for case_id in candidates:
        outcome = outcome_for(case_id, results)
        candidate_counts[outcome] += 1
        if case_id not in results:
            missing_results.append(case_id)

    sections = {
        name: section_color(rule, results, waivers["expired"])
        for name, rule in SECTION_RULES.items()
    }
    sections["frozen_beta_candidate"] = frozen_candidate_color(
        candidates,
        results,
        missing_required,
        waivers["expired"],
    )

    blockers = []
    for case_id in candidates:
        outcome = outcome_for(case_id, results)
        if outcome in {"fail", "blocked", "not-run"}:
            blockers.append({
                "case_id": case_id,
                "outcome": outcome,
                "reason": results.get(case_id, {}).get("reason", "missing result"),
            })
        if case_id in missing_required:
            blockers.append({
                "case_id": case_id,
                "outcome": "fail",
                "reason": "missing required artifacts: " + ", ".join(missing_required[case_id]),
            })
    for waiver in waivers["expired"]:
        blockers.append({
            "case_id": waiver.get("case_id", "waiver"),
            "outcome": "fail",
            "reason": f"expired waiver: {waiver.get('id', 'unknown')}",
        })

    internal_green_sections = [
        "hard_gates",
        "frozen_beta_candidate",
        "mandatory_smoke",
        "client_server",
        "vfs_datapack",
        "strict_security",
        "native_tccl",
        "frozen_corpus",
        "performance",
        "soak",
    ]
    internal_ready = (
        all(sections.get(name) == "green" for name in internal_green_sections)
        and candidate_counts["fail"] == 0
        and candidate_counts["not-run"] == 0
        and candidate_counts["blocked"] == 0
        and not any(case_id in missing_required for case_id in candidates)
        and not waivers["expired"]
    )
    public_beta_eligible = internal_ready and not blockers
    if public_beta_eligible:
        state = "public-beta-eligible"
    elif internal_ready:
        state = "internal-beta-ready"
    elif summaries:
        state = "beta-prep"
    else:
        state = "alpha-active"

    return {
        "schema": "intermed-beta-readiness-dashboard-v1",
        "state": state,
        "date": args.date,
        "commit": git_commit(root),
        "candidate_set": args.candidate or "testing/frozen-beta-candidate.json",
        "suite_summaries": loaded_summaries,
        "frozen_beta_candidate": {outcome.replace("-", "_"): candidate_counts[outcome] for outcome in OUTCOMES},
        "missing_result_case_ids": missing_results,
        "missing_required_artifacts": missing_required,
        "artifact_completeness": {
            "candidate_cases": len(candidates),
            "candidate_cases_with_missing_required_artifacts": sum(
                1 for case_id in candidates if case_id in missing_required
            ),
            "complete": all(case_id not in missing_required for case_id in candidates),
        },
        "blocker_count": len(blockers),
        "blockers": blockers[:50],
        "waiver_count": len(waivers["active"]),
        "expired_waiver_count": len(waivers["expired"]),
        "waivers": waivers,
        "sections": sections,
    }


def render_markdown(dashboard: dict) -> str:
    counts = dashboard.get("frozen_beta_candidate", {})
    sections = dashboard.get("sections", {})
    blockers = dashboard.get("blockers", [])
    suites = dashboard.get("suite_summaries", [])
    completeness = dashboard.get("artifact_completeness", {})

    lines = [
        "# InterMed Beta Readiness Dashboard",
        "",
        f"- Date: `{dashboard.get('date', 'unknown')}`",
        f"- Commit: `{dashboard.get('commit', 'unknown')}`",
        f"- State: `{dashboard.get('state', 'unknown')}`",
        f"- Blockers: `{dashboard.get('blocker_count', 0)}`",
        f"- Active waivers: `{dashboard.get('waiver_count', 0)}`",
        f"- Expired waivers: `{dashboard.get('expired_waiver_count', 0)}`",
        f"- Artifact completeness: `{'complete' if completeness.get('complete') else 'incomplete'}`",
        "",
        "## Frozen Beta Candidate Counts",
        "",
        "| Outcome | Count |",
        "|---|---:|",
    ]

    for key in ["pass", "fail", "unsupported", "not_run", "blocked"]:
        lines.append(f"| `{key}` | {counts.get(key, 0)} |")

    lines.extend([
        "",
        "## Sections",
        "",
        "| Section | Status |",
        "|---|---|",
    ])

    for name in sorted(sections):
        lines.append(f"| `{name}` | `{sections[name]}` |")

    lines.extend([
        "",
        "## First Blockers",
        "",
    ])

    if blockers:
        lines.extend([
            "| Case | Outcome | Reason |",
            "|---|---|---|",
        ])
        for blocker in blockers[:20]:
            reason = str(blocker.get("reason", "")).replace("|", "\\|").replace("\n", " ")
            lines.append(
                f"| `{blocker.get('case_id', 'unknown')}` | "
                f"`{blocker.get('outcome', 'unknown')}` | {reason} |"
            )
    else:
        lines.append("No blockers recorded.")

    lines.extend([
        "",
        "## Suite Summaries",
        "",
    ])

    if suites:
        lines.extend([
            "| Suite | Date | Summary |",
            "|---|---|---|",
        ])
        for suite in suites:
            lines.append(
                f"| `{suite.get('suite', 'unknown')}` | "
                f"`{suite.get('date', 'unknown')}` | "
                f"`{suite.get('path', '')}` |"
            )
    else:
        lines.append("No suite summaries were loaded.")

    lines.extend([
        "",
        "## Notes",
        "",
        "This report is generated from stored suite artifacts. Missing frozen beta candidate results are counted as `not_run`; beta wording remains blocked until the dashboard rules are green.",
        "",
    ])

    return "\n".join(lines)


def parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-root", help="Directory containing build/test-runs style suite summaries")
    parser.add_argument("--summary", action="append", default=[], help="Explicit suite-summary.json path")
    parser.add_argument("--candidate", help="Frozen beta candidate JSON path")
    parser.add_argument("--waivers", help="Waiver registry JSON path")
    parser.add_argument(
        "--date",
        default=os.environ.get("INTERMED_TEST_DATE") or datetime.now(timezone.utc).strftime("%Y%m%d"),
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Output JSON path (default: build/test-runs/<date>/beta-readiness-dashboard.json)",
    )
    parser.add_argument(
        "--markdown-output",
        default=None,
        help="Optional Markdown report path",
    )
    return parser


def main(argv: list[str]) -> int:
    args = parser().parse_args(argv)
    root = root_dir()
    dashboard = build_dashboard(args)
    output = Path(args.output).expanduser().resolve() if args.output else (
        root / "build" / "test-runs" / args.date / "beta-readiness-dashboard.json"
    )
    write_json(output, dashboard)
    if args.markdown_output:
        write_text(Path(args.markdown_output).expanduser().resolve(), render_markdown(dashboard))
    print(output)
    if dashboard["blocker_count"]:
        print(f"blockers={dashboard['blocker_count']}")
    print(f"state={dashboard['state']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
