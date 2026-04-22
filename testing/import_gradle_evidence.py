#!/usr/bin/env python3
"""Import existing Gradle/JUnit reports into beta-prep case artifacts.

This script is intentionally conservative. It turns already-produced local
JUnit XML reports into machine-readable beta-case evidence, but it does not
claim real Minecraft lifecycle coverage for cases that require a dedicated
server, client/server login, datapack reload, native fixture, or soak run.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


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


def git_dirty(root: Path) -> bool:
    try:
        status = subprocess.check_output(
            ["git", "-C", str(root), "status", "--porcelain", "--untracked-files=normal"],
            stderr=subprocess.DEVNULL,
            text=True,
        )
        return bool(status.strip())
    except Exception:
        return True


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


def catalog_names(root: Path) -> dict[str, str]:
    catalog = json.loads((root / "testing" / "test-cases.json").read_text(encoding="utf-8"))
    return {case["id"]: case.get("name", case["id"]) for case in catalog.get("cases", [])}


def expand_sources(root: Path, sources: list[str]) -> tuple[list[Path], list[str]]:
    paths: list[Path] = []
    missing: list[str] = []
    for source in sources:
        matches = sorted(root.glob(source))
        if matches:
            paths.extend(matches)
        else:
            missing.append(source)
    return paths, missing


def parse_junit(root: Path, paths: list[Path], missing: list[str]) -> dict:
    totals = Counter()
    source_reports = []
    testcases = []
    parse_errors = []

    for path in paths:
        try:
            suite = ET.parse(path).getroot()
        except Exception as exc:
            parse_errors.append({"path": rel(root, path), "error": str(exc)})
            continue

        stats = {
            "path": rel(root, path),
            "name": suite.get("name", path.stem.removeprefix("TEST-")),
            "tests": int(suite.get("tests", "0")),
            "failures": int(suite.get("failures", "0")),
            "errors": int(suite.get("errors", "0")),
            "skipped": int(suite.get("skipped", "0")),
            "time": suite.get("time"),
        }
        source_reports.append(stats)
        totals.update({
            "tests": stats["tests"],
            "failures": stats["failures"],
            "errors": stats["errors"],
            "skipped": stats["skipped"],
        })
        for testcase in suite.findall("testcase"):
            testcases.append({
                "class": testcase.get("classname", stats["name"]),
                "name": testcase.get("name"),
                "time": testcase.get("time"),
            })

    ok = (
        not missing
        and not parse_errors
        and totals["tests"] > 0
        and totals["failures"] == 0
        and totals["errors"] == 0
        and totals["skipped"] == 0
    )

    return {
        "ok": ok,
        "missing_sources": missing,
        "parse_errors": parse_errors,
        "totals": dict(totals),
        "source_reports": source_reports,
        "testcases": testcases,
    }


def artifact_payload(case_id: str, kind: str, stats: dict, note: str) -> dict:
    return {
        "schema": "intermed-imported-gradle-evidence-v1",
        "case_id": case_id,
        "kind": kind,
        "evidence_level": "synthetic-tested",
        "source": "existing Gradle/JUnit reports",
        "note": note,
        "totals": stats["totals"],
        "source_reports": stats["source_reports"],
        "source_test_count": len(stats["testcases"]),
        "limitations": [
            "This imported evidence does not replace real Minecraft client/server lifecycle testing.",
            "This imported evidence does not replace external corpus, native fixture, datapack reload, or soak evidence unless the case explicitly says so.",
        ],
    }


def write_case(
    root: Path,
    out_root: Path,
    suite: str,
    case_id: str,
    name: str,
    outcome: str,
    reason: str,
    stats: dict | None,
    extras: dict[str, tuple[str, str]] | None = None,
    text_extras: dict[str, str] | None = None,
) -> None:
    case_dir = out_root / case_id
    started_at = utc_now()
    ended_at = started_at
    commit = git_commit(root)
    exit_code = 0 if outcome in {"pass", "not-run", "unsupported"} else 1
    command = f"# imported existing Gradle/JUnit evidence for {case_id}"

    case_dir.mkdir(parents=True, exist_ok=True)
    (case_dir / "command.txt").write_text(command + "\n", encoding="utf-8")
    (case_dir / "stdout.log").write_text(reason + "\n", encoding="utf-8")
    (case_dir / "stderr.log").write_text("", encoding="utf-8")

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
    write_json(case_dir / "exit-status.json", {
        "schema": "intermed-exit-status-v1",
        "exit_code": exit_code,
        "started_at": started_at,
        "ended_at": ended_at,
    })
    write_json(case_dir / "mod-list.json", {
        "schema": "intermed-mod-list-v1",
        "mods": [],
    })

    if stats is not None:
        write_json(case_dir / "evidence.json", {
            "schema": "intermed-imported-gradle-evidence-v1",
            "case_id": case_id,
            "outcome": outcome,
            "reason": reason,
            "totals": stats["totals"],
            "source_reports": stats["source_reports"],
            "testcases": stats["testcases"],
            "missing_sources": stats["missing_sources"],
            "parse_errors": stats["parse_errors"],
        })
        for filename, (kind, note) in (extras or {}).items():
            write_json(case_dir / filename, artifact_payload(case_id, kind, stats, note))
        for filename, content in (text_extras or {}).items():
            (case_dir / filename).write_text(content, encoding="utf-8")
    else:
        for filename, content in (text_extras or {}).items():
            (case_dir / filename).write_text(content, encoding="utf-8")

    write_json(case_dir / "result.json", {
        "schema": "intermed-case-result-v1",
        "case_id": case_id,
        "name": name,
        "outcome": outcome,
        "reason": reason,
        "exit_code": exit_code,
    })


def import_spec(root: Path, out_root: Path, suite: str, names: dict[str, str], spec: dict) -> None:
    paths, missing = expand_sources(root, spec["sources"])
    stats = parse_junit(root, paths, missing)
    name = names.get(spec["id"], spec["id"])
    if stats["ok"]:
        outcome = "pass"
        reason = spec["reason"]
    elif stats["missing_sources"] or stats["parse_errors"] or stats["totals"].get("tests", 0) == 0:
        outcome = "blocked"
        reason = "required JUnit evidence is missing or unreadable"
    else:
        outcome = "fail"
        reason = (
            "imported JUnit evidence is not green: "
            f"failures={stats['totals'].get('failures', 0)}, "
            f"errors={stats['totals'].get('errors', 0)}, "
            f"skipped={stats['totals'].get('skipped', 0)}"
        )

    write_case(
        root,
        out_root,
        suite,
        spec["id"],
        name,
        outcome,
        reason,
        stats,
        spec.get("extras"),
        spec.get("text_extras"),
    )


def specs() -> list[dict]:
    test = "app/build/test-results/test/TEST-{}.xml"
    strict = "app/build/test-results/strictSecurity/TEST-{}.xml"
    harness = "test-harness/build/test-results/test/TEST-*.xml"
    dep_note = "Imported resolver/classloader evidence; this is not a real external launch dependency plan."
    security_note = "Imported strict-security synthetic lane evidence; permissive results are not used as security proof."

    return [
        {
            "id": "GATE-002",
            "sources": [harness],
            "reason": "all test-harness JUnit reports are green",
            "text_extras": {
                "gradle-summary.txt": (
                    "source=test-harness/build/test-results/test\n"
                    "evidence=imported-junit\n"
                    "lane=harness-self-test\n"
                ),
            },
        },
        {
            "id": "DAG-001",
            "sources": [
                test.format("org.intermed.core.resolver.PubGrubResolverTest"),
                test.format("org.intermed.core.resolver.SemVerConstraintTest"),
                test.format("org.intermed.core.resolver.VirtualDependencyMapTest"),
            ],
            "reason": "imported deterministic resolver evidence from passing Gradle tests",
            "extras": {"dependency-plan.json": ("dependency-plan", dep_note)},
        },
        {
            "id": "DAG-002",
            "sources": [
                test.format("org.intermed.core.classloading.LazyInterMedClassLoaderTest"),
                test.format("org.intermed.core.classloading.DagAwareClassWriterIntegrationTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported parent/peer/weak-peer classloader evidence from passing Gradle tests",
            "extras": {"dependency-plan.json": ("classloader-topology", dep_note)},
        },
        {
            "id": "DAG-003",
            "sources": [
                test.format("org.intermed.core.classloading.LibraryDiscoveryTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported private nested library isolation evidence from passing Gradle tests",
            "extras": {"dependency-plan.json": ("private-library-isolation", dep_note)},
        },
        {
            "id": "DAG-004",
            "sources": [
                test.format("org.intermed.core.classloading.LazyInterMedClassLoaderTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported private library re-export evidence from passing Gradle tests",
            "extras": {"dependency-plan.json": ("private-library-re-export", dep_note)},
        },
        {
            "id": "DAG-005",
            "sources": [
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
                test.format("org.intermed.core.InterMedKernelTest"),
            ],
            "reason": "imported fallback discipline evidence from passing Gradle tests",
            "extras": {"dependency-plan.json": ("fallback-discipline", dep_note)},
        },
        {
            "id": "REMAP-001",
            "sources": [
                test.format("org.intermed.core.remapping.InterMedRemapperTest"),
                test.format("org.intermed.core.remapping.MappingDictionaryTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported bytecode class/member remap evidence from passing Gradle tests",
        },
        {
            "id": "REMAP-002",
            "sources": [
                test.format("org.intermed.core.remapping.InterMedRemapperTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported reflection string remap evidence from passing Gradle tests",
        },
        {
            "id": "REMAP-003",
            "sources": [test.format("org.intermed.core.remapping.InterMedRemapperTest")],
            "reason": "imported unsupported dynamic-name diagnostics evidence from passing Gradle tests",
        },
        {
            "id": "MIXIN-001",
            "sources": [
                test.format("org.intermed.core.mixin.MixinIntegrationTest"),
                test.format("org.intermed.core.mixin.MixinTransformerTest"),
                test.format("org.intermed.core.ast.ResolutionEngineTest"),
            ],
            "reason": "imported additive mixin merge evidence from passing Gradle tests",
        },
        {
            "id": "MIXIN-002",
            "sources": [
                test.format("org.intermed.core.ast.ResolutionEngineTest"),
                test.format("org.intermed.core.mixin.MixinIntegrationTest"),
            ],
            "reason": "imported overwrite conflict policy evidence from passing Gradle tests",
            "extras": {
                "mixin-conflict-report.json": (
                    "mixin-conflict-report",
                    "Imported AST resolution evidence for overwrite/conflict policy.",
                ),
            },
        },
        {
            "id": "MIXIN-003",
            "sources": [
                test.format("org.intermed.core.mixin.MixinIntegrationTest"),
                test.format("org.intermed.core.mixin.MixinTransformerTest"),
            ],
            "reason": "imported mixin chain/order fixture evidence from passing Gradle tests",
        },
        {
            "id": "REG-001",
            "sources": [
                test.format("org.intermed.core.registry.VirtualRegistryServiceTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported conflicting key sharding evidence from passing Gradle tests",
            "extras": {
                "registry-report.json": (
                    "registry-report",
                    "Imported synthetic registry sharding evidence.",
                ),
            },
        },
        {
            "id": "REG-002",
            "sources": [
                test.format("org.intermed.core.registry.VirtualRegistryServiceTest"),
                test.format("org.intermed.core.registry.RegistryTranslationMatrixTest"),
                test.format("org.intermed.core.registry.RegistryLinkerTest"),
            ],
            "reason": "imported global ID lookup consistency evidence from passing Gradle tests",
            "extras": {
                "registry-report.json": (
                    "registry-report",
                    "Imported synthetic registry lookup and translation evidence.",
                ),
            },
        },
        {
            "id": "REG-003",
            "sources": [
                test.format("org.intermed.core.registry.VirtualRegistryServiceTest"),
                test.format("org.intermed.core.registry.FrozenStringIntHashIndexTest"),
                test.format("org.intermed.core.registry.RegistryLinkerTest"),
            ],
            "reason": "imported registry freeze behavior evidence from passing Gradle tests",
            "extras": {
                "registry-report.json": (
                    "registry-report",
                    "Imported synthetic registry freeze/hot-path evidence.",
                ),
            },
        },
        {
            "id": "VFS-001",
            "sources": [test.format("org.intermed.core.vfs.VirtualFileSystemRouterTest")],
            "reason": "imported resource overlay conflict diagnostics evidence from passing Gradle tests",
            "extras": {
                "vfs-diagnostics.json": (
                    "vfs-diagnostics",
                    "Imported VFS overlay/diagnostics evidence.",
                ),
            },
        },
        {
            "id": "VFS-005",
            "sources": [test.format("org.intermed.core.vfs.VirtualFileSystemRouterTest")],
            "reason": "imported priority override and merge-policy sanity evidence from passing Gradle tests",
            "extras": {
                "vfs-diagnostics.json": (
                    "vfs-diagnostics",
                    "Imported VFS merge and priority policy evidence.",
                ),
            },
        },
        {
            "id": "SEC-001",
            "sources": [
                strict.format("org.intermed.core.security.HostileSecuritySmokeTest"),
                strict.format("org.intermed.core.security.SecurityPolicyTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported strict denied file-read evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-002",
            "sources": [
                strict.format("org.intermed.core.security.SecurityPolicyTest"),
                strict.format("org.intermed.core.security.CapabilityManagerGranularTest"),
                test.format("org.intermed.core.lifecycle.LifecycleManagerIntegrationTest"),
            ],
            "reason": "imported strict allowed file-read evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-003",
            "sources": [
                strict.format("org.intermed.core.security.CapabilityManagerGranularTest"),
                strict.format("org.intermed.core.security.SecurityPolicyTest"),
            ],
            "reason": "imported unattributed sensitive operation denial evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-004",
            "sources": [
                strict.format("org.intermed.core.security.HostileSecuritySmokeTest"),
                strict.format("org.intermed.core.security.SecurityPolicyTest"),
                strict.format("org.intermed.core.security.CapabilityManagerGranularTest"),
                strict.format("org.intermed.core.security.SecurityHookTransformerTest"),
            ],
            "reason": "imported network deny/allow evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-005",
            "sources": [
                strict.format("org.intermed.core.security.HostileSecuritySmokeTest"),
                strict.format("org.intermed.core.security.SecurityHookTransformerTest"),
            ],
            "reason": "imported process-spawn denial evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-006",
            "sources": [
                strict.format("org.intermed.core.security.HostileSecuritySmokeTest"),
                strict.format("org.intermed.core.security.CapabilityManagerGranularTest"),
            ],
            "reason": "imported reflection access denial/diagnostics evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-007",
            "sources": [
                test.format("org.intermed.core.classloading.LazyInterMedClassLoaderTest"),
                strict.format("org.intermed.core.security.HostileSecuritySmokeTest"),
                strict.format("org.intermed.core.security.SecurityHookTransformerTest"),
            ],
            "reason": "imported native library routing, dedupe, and conflict diagnostic evidence from passing Gradle tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-008",
            "sources": [
                strict.format("org.intermed.core.security.HostileSecuritySmokeTest"),
                test.format("org.intermed.core.classloading.TcclInterceptorTest"),
            ],
            "reason": "imported async attribution propagation evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-009",
            "sources": [test.format("org.intermed.core.classloading.TcclInterceptorTest")],
            "reason": "imported TCCL propagation fixture evidence from passing Gradle tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "SEC-010",
            "sources": [
                strict.format("org.intermed.core.sandbox.GraalVMSandboxTest"),
                strict.format("org.intermed.core.sandbox.PolyglotSandboxManagerTest"),
                strict.format("org.intermed.core.sandbox.WasmSandboxTest"),
            ],
            "reason": "imported sandbox denial/grant behavior evidence from passing strictSecurity tests",
            "extras": {"security-report.json": ("security-report", security_note)},
        },
        {
            "id": "NATIVE-002",
            "sources": [test.format("org.intermed.core.classloading.TcclInterceptorTest")],
            "reason": "imported custom async scheduler/TCCL propagation fixture evidence from passing Gradle tests",
        },
    ]


def write_clean_checkout_marker(root: Path, out_root: Path, suite: str, names: dict[str, str]) -> None:
    dirty = git_dirty(root)
    if dirty:
        outcome = "blocked"
        reason = "working tree is dirty; importer cannot certify the clean checkout hard gate"
    else:
        outcome = "not-run"
        reason = "importer does not execute the clean checkout hard gate; run testing/run_local_gates.sh with INTERMED_RUN_LOCAL_GATES=true"
    summary = (
        "source=git-status\n"
        f"working_tree_dirty={'true' if dirty else 'false'}\n"
        f"outcome={outcome}\n"
        f"reason={reason}\n"
    )
    write_case(
        root,
        out_root,
        suite,
        "GATE-001",
        names.get("GATE-001", "GATE-001"),
        outcome,
        reason,
        None,
        text_extras={"gradle-summary.txt": summary},
    )


def write_known_gap_markers(root: Path, out_root: Path, suite: str, names: dict[str, str]) -> None:
    """Record explicit not-run rows for gaps not covered by imported JUnit evidence.

    Keep this list narrow. Cases normally produced by client/server lifecycle,
    live launcher gates, or VFS reload runners are intentionally not written
    here so later real suite results cannot be shadowed by imported not-run
    records in the dashboard.
    """

    gaps = {
        "BOOT-001": ("not-run", "real minimal Fabric dedicated server boot was not executed by the Gradle evidence importer"),
        "BOOT-002": ("not-run", "real minimal Forge dedicated server boot was not executed by the Gradle evidence importer"),
        "BOOT-003": ("not-run", "real minimal NeoForge dedicated server boot was not executed by the Gradle evidence importer"),
        "BOOT-004": ("not-run", "real mixed-loader dedicated server boot was not executed by the Gradle evidence importer"),
        "MIXIN-004": ("not-run", "public-mod mixin corpus classification must come from the corpus runner"),
        "MIXIN-005": ("not-run", "unsupported public mixin safe-fail fixtures are not frozen yet"),
        "NATIVE-001": (
            "unsupported",
            "documented unsupported: real JNI/JNA host-library smoke fixture is not wired into the current beta-prep runner; synthetic native routing remains covered by SEC-007",
        ),
        "CORPUS-002": ("not-run", "pair-mod external corpus shard has not been executed yet"),
        "CORPUS-003": ("not-run", "curated slice-pack external corpus shard has not been executed yet"),
    }
    for case_id, (outcome, reason) in gaps.items():
        write_case(root, out_root, suite, case_id, names.get(case_id, case_id), outcome, reason, None)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", default="imported-gradle-evidence")
    parser.add_argument("--out-root")
    args = parser.parse_args()

    root = root_dir()
    out_root = Path(args.out_root).expanduser().resolve() if args.out_root else (
        root / "build" / "test-runs" / run_date() / args.suite
    )
    names = catalog_names(root)
    out_root.mkdir(parents=True, exist_ok=True)

    write_clean_checkout_marker(root, out_root, args.suite, names)
    for spec in specs():
        import_spec(root, out_root, args.suite, names, spec)
    write_known_gap_markers(root, out_root, args.suite, names)

    subprocess.run(
        [str(root / "testing" / "collect_artifacts.sh"), args.suite, str(out_root)],
        cwd=root,
        check=True,
    )
    print(out_root / "suite-summary.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
