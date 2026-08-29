//! `intermed` — the command-line workbench.
//!
//! This binary is the **composition root**: the one place that knows about
//! every concrete collector and rule. It detects the target, builds a
//! [`DiagnosticEngine`] with the registered layers, runs it, and renders the
//! report. Adding a layer in a future phase is one `.collector(...)` line here.

use std::collections::BTreeMap;
use std::io::IsTerminal;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use anyhow::{Context as _, Result as AnyhowResult};
use clap::Parser;
use sha2::{Digest, Sha256};

use intermed_cli::command::{
    CacheArgs, CacheCommand, Command, DbArgs, DemoArgs, DemoCommand, DepsArgs, DepsCommand,
    DepsIdArgs, DepsImplicitArgs, DepsPathArgs, DoctorArgs, DoctorCacheArgs, DoctorTuningArgs,
    GraphExportFormat, HistoryArgs, ImpactArgs, ImpactCommand, ImpactRemoveArgs, ImpactUpdateArgs,
    LabArgs, LabCommand, LabEvalArgs, LogicMode, MixinMapArgs, RulesArgs, RulesCommand, SbomArgs,
    SbomCommand, SbomExportFormatCli, SparkMapArgs, TrendsArgs, VfsArgs, VfsCommand,
};
// Subcommand enums are only matched on the duckdb-backed analytics path.
#[cfg(feature = "duckdb")]
use intermed_cli::command::DbCommand;
#[cfg(feature = "duckdb")]
use intermed_cli::command::{HistoryCommand, TrendsCommand};
use intermed_cli::telemetry::{self, TelemetryOptions};
use intermed_cli::{detail, info};
use intermed_config::{ConfigError, IntermedConfig};
use intermed_doctor_core::evidence::Finding;
use intermed_doctor_core::facts::Fact;
use intermed_doctor_core::{
    DiagnosisSettings, DiagnosticEngine, DiagnosticRun, GatedCollector, JarCache, Target,
    TargetKind, detect_target, materialize_modpack_archive, parse_changed_since, write_atomic,
};
use intermed_duckdb::{DuckdbRulePack, duckdb_available};
use intermed_report::{Format, ReportSchema, write_demo_artifacts};
use intermed_spark_bridge::PerformanceThresholds;

use intermed_deps::{DependencyRule, ResolutionOutcome, build_graph, resolve_store};
use intermed_log::{LogCollector, LogSignalRule};
use intermed_minecraft_scan::{EnvironmentCollector, MetadataCollector};
use intermed_rules::{
    ColumnarRulePack, GenerateBackend, MixedLoaderPackRule, RULE_PACK_SCHEMA_V2,
    RULE_PACK_SCHEMA_V3, RULE_REGISTRY_SCHEMA, RulePackSelection, SouffleRulePack,
    check_rule_packs, default_rule_pack_install_dir, format_trace, generate_rules,
    install_pack_from_registry, install_pack_with_dependencies, load_registry_from_source,
    load_rule_pack, load_signing_key, load_trusted_keys, merged_default_registry, registry_to_json,
    resolve_doctor_packs, souffle_available, trace_pack, validate_rule_pack,
    verify_rule_pack_signature,
};
use intermed_sbom::{SbomExportFormat, export_scan, scan_mods_dir};

use intermed_cli::Cli;

mod persistence;

#[cfg(feature = "dhat-heap")]
#[global_allocator]
static ALLOC: dhat::Alloc = dhat::Alloc;

// mimalloc keeps RSS close to the live heap on this allocation-heavy workload (the
// fact graph is millions of small allocations that fragment glibc malloc badly).
// The dhat profiler installs its own allocator, so only swap in mimalloc otherwise.
#[cfg(not(feature = "dhat-heap"))]
#[global_allocator]
static ALLOC: mimalloc::MiMalloc = mimalloc::MiMalloc;

fn main() -> ExitCode {
    #[cfg(feature = "dhat-heap")]
    let _dhat = dhat::Profiler::new_heap();
    let cli = Cli::parse();
    intermed_cli::verbosity::configure(cli.quiet, cli.verbose);

    // Resolve every configuration layer before initializing Rayon. In
    // particular, `--jobs` must beat INTERMED_JOBS rather than arriving after
    // the process-global pool has already been built.
    let mut effective_config = match IntermedConfig::load(cli.config.as_deref()) {
        Ok(config) => config,
        Err(e) => {
            eprintln!("error: could not resolve configuration: {e}");
            return ExitCode::from(2);
        }
    };
    if let Some(Command::Doctor(args)) = cli.command.as_ref() {
        apply_doctor_cli_overrides(&mut effective_config, args);
    }

    if cli.dump_config {
        return match effective_config.to_toml() {
            Ok(text) => {
                print!("{text}");
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("error: could not serialize effective config: {e}");
                ExitCode::from(2)
            }
        };
    }

    let jobs = (effective_config.runtime.jobs > 0).then_some(effective_config.runtime.jobs);
    if let Err(e) = configure_thread_pool(jobs) {
        eprintln!("warning: {e} (continuing with Rayon default)");
    }

    if let Some(command) = cli.command {
        match command {
            Command::Doctor(args) => run_doctor(args, cli.config.as_deref()),
            Command::Vfs(args) => run_vfs(args),
            Command::Deps(args) => run_deps(args),
            Command::Impact(args) => run_impact(args),
            Command::MixinMap(args) => run_mixin_map(args),
            Command::SparkMap(args) => run_spark_map(args),
            Command::Lab(args) => run_lab(args, cli.config.as_deref()),
            Command::Rules(args) => run_rules(args),
            Command::Db(args) => run_db(args),
            Command::History(args) => run_history(args),
            Command::Trends(args) => run_trends(args),
            Command::Cache(args) => run_cache(args),
            Command::Sbom(args) => run_sbom(args),
            Command::Demo(args) => run_demo(args),
        }
    } else {
        eprintln!(
            "error: subcommand required (try `intermed doctor --help` or `intermed --dump-config`)"
        );
        ExitCode::from(2)
    }
}

/// Size the global Rayon pool that every parallel scanner shares. `None` or `0`
/// leaves Rayon's default (one worker per core). A non-zero cap is for weak
/// machines and shared CI runners where saturating all cores is undesirable.
///
/// `build_global` may only be called once per process; since exactly one
/// subcommand runs per invocation, that is fine.
fn configure_thread_pool(jobs: Option<usize>) -> Result<(), String> {
    let n = match jobs {
        None | Some(0) => return Ok(()),
        Some(n) => n,
    };
    rayon::ThreadPoolBuilder::new()
        .num_threads(n)
        .build_global()
        .map_err(|e| format!("could not configure {n} worker thread(s): {e}"))
}

mod handlers {
    pub(crate) mod analytics;
    pub(crate) mod deps;
    pub(crate) mod doctor;
    pub(crate) mod lab;
    pub(crate) mod maintenance;
    pub(crate) mod mixin;
    pub(crate) mod rules;
    pub(crate) mod spark;
    pub(crate) mod util;
    pub(crate) mod vfs;
}

use handlers::analytics::*;
use handlers::deps::*;
use handlers::doctor::*;
use handlers::lab::*;
use handlers::maintenance::*;
use handlers::mixin::*;
use handlers::rules::*;
use handlers::spark::*;
use handlers::util::*;
use handlers::vfs::*;

#[cfg(test)]
mod explain_tests {
    use super::*;
    use std::collections::BTreeMap;

    use chrono::Utc;
    use intermed_doctor_core::evidence::{Finding, Severity};
    use intermed_doctor_core::report::{OperationalError, Summary, TargetView};
    use intermed_doctor_core::{DoctorReport, Environment, TargetKind};

    fn finding(id: &str, sev: Severity) -> Finding {
        Finding::builder("rule", id)
            .severity(sev)
            .title("t")
            .build()
    }

    fn sample() -> Vec<Finding> {
        vec![
            finding("duplicate-id:minecraft:copper", Severity::Error),
            finding("missing-dependency:create->fabric-api", Severity::Warn),
            finding("resource-conflict:assets/foo.json", Severity::Note),
        ]
    }

    #[test]
    fn exact_id_matches() {
        let f = sample();
        assert!(matches!(
            resolve_explain_target(&f, "duplicate-id:minecraft:copper"),
            ExplainResolution::Exact(_)
        ));
    }

    #[test]
    fn case_insensitive_exact_auto_resolves() {
        let f = sample();
        match resolve_explain_target(&f, "DUPLICATE-ID:MINECRAFT:COPPER") {
            ExplainResolution::Fuzzy(found) => {
                assert_eq!(found.id, "duplicate-id:minecraft:copper")
            }
            _ => panic!("expected fuzzy auto-resolve"),
        }
    }

    #[test]
    fn unique_substring_auto_resolves() {
        let f = sample();
        match resolve_explain_target(&f, "copper") {
            ExplainResolution::Fuzzy(found) => {
                assert_eq!(found.id, "duplicate-id:minecraft:copper")
            }
            _ => panic!("expected fuzzy auto-resolve from substring"),
        }
    }

    #[test]
    fn ambiguous_substring_suggests() {
        let f = sample();
        // "id" appears in two ids (duplicate-id, missing-dependency has no "id"...).
        match resolve_explain_target(&f, "duplicate") {
            // only one contains "duplicate" → resolves
            ExplainResolution::Fuzzy(_) => {}
            other => panic!("unexpected {}", matches_name(&other)),
        }
        match resolve_explain_target(&f, ":") {
            ExplainResolution::Suggestions(s) => assert!(s.len() > 1),
            other => panic!("expected suggestions, got {}", matches_name(&other)),
        }
    }

    #[test]
    fn typo_suggests_closest() {
        let f = sample();
        match resolve_explain_target(&f, "duplcate-id:minecraft:coper") {
            ExplainResolution::Suggestions(s) => {
                assert_eq!(s[0].id, "duplicate-id:minecraft:copper");
            }
            other => panic!("expected suggestions, got {}", matches_name(&other)),
        }
    }

    #[test]
    fn no_match_lists_by_severity() {
        let f = sample();
        match resolve_explain_target(&f, "zzzzzzzzzzzzzz-nothing-like-this") {
            ExplainResolution::Listing(l) => {
                assert_eq!(l[0].severity, Severity::Error); // most severe first
            }
            other => panic!("expected listing, got {}", matches_name(&other)),
        }
    }

    #[test]
    fn empty_report_lists_nothing() {
        match resolve_explain_target(&[], "anything") {
            ExplainResolution::Listing(l) => assert!(l.is_empty()),
            _ => panic!("expected empty listing"),
        }
    }

    #[test]
    fn exit_zero_never_masks_operational_failure() {
        let report = DoctorReport {
            schema: "test".into(),
            tool_version: "test".into(),
            generated_at: Utc::now(),
            target: TargetView {
                path: ".".into(),
                kind: TargetKind::ModsDir,
            },
            analysis_environment: Default::default(),
            environment: Environment::default(),
            summary: Summary::default(),
            findings: Vec::new(),
            fix_plan: Vec::new(),
            fact_stats: BTreeMap::new(),
            collectors: Vec::new(),
            analysis_configuration: Default::default(),
            mixin_coverage: Default::default(),
            target_capabilities: Default::default(),
            evidence_graph: Default::default(),
            incidents: Vec::new(),
            recommendations: Vec::new(),
            rules: Vec::new(),
            operational_errors: vec![OperationalError {
                stage: "rule".into(),
                component: "duckdb-rule-pack".into(),
                message: "out of memory".into(),
            }],
            deferred_layers: Vec::new(),
            profile: None,
        };
        assert_eq!(findings_exit_code(&report, true), ExitCode::from(2));
    }

    fn matches_name(r: &ExplainResolution<'_>) -> &'static str {
        match r {
            ExplainResolution::Exact(_) => "Exact",
            ExplainResolution::Fuzzy(_) => "Fuzzy",
            ExplainResolution::Suggestions(_) => "Suggestions",
            ExplainResolution::Listing(_) => "Listing",
        }
    }
}
