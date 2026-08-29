//! Clap command definitions (shared by the binary and man-page generation).

use std::path::PathBuf;

use clap::{Args, Parser, Subcommand, ValueEnum};

#[derive(Parser)]
#[command(
    name = "intermed",
    arg_required_else_help = false,
    version,
    about = "InterMed — Minecraft modpack/server evidence engine",
    long_about = "InterMed builds a fact graph from Minecraft servers, instances, mods directories, \
and logs, then derives findings with full provenance.\n\n\
See docs/guides/quickstart.md for copy-paste recipes for every subcommand.",
    after_help = "Examples:\n  \
intermed doctor ./mods\n  \
intermed doctor ./server --mixin-risk --json\n  \
intermed vfs explain ./mods\n  \
intermed mixin-map ./mods\n  \
intermed rules check ./rules\n\n\
More: docs/guides/quickstart.md | Reference: docs/reference/commands.md | Man: docs/man/intermed.1"
)]
pub struct Cli {
    /// Config file (`intermed-config-v1` TOML). Overrides discovery; see docs/reference/configuration.md.
    #[arg(long = "config", global = true, value_name = "FILE")]
    pub config: Option<PathBuf>,

    /// Print the fully merged config (defaults, files, environment, and doctor CLI overrides)
    /// as TOML and exit. A subcommand is optional.
    #[arg(long = "dump-config", global = true)]
    pub dump_config: bool,

    /// Suppress informational progress messages on stderr (errors still print).
    #[arg(long, global = true, conflicts_with = "verbose")]
    pub quiet: bool,

    /// Increase informational detail (repeatable: `-v`, `-vv`).
    #[arg(long, short = 'v', global = true, action = clap::ArgAction::Count)]
    pub verbose: u8,

    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Subcommand)]
pub enum Command {
    /// Diagnose a server, instance, mods directory, or log/crash file.
    Doctor(Box<DoctorArgs>),
    /// Inspect resource/data overrides and generate overlay previews.
    Vfs(VfsArgs),
    /// Layer-C dependency graph, resolution, and explainable queries.
    Deps(DepsArgs),
    /// Blast-radius analysis for removing or updating a mod.
    Impact(ImpactArgs),
    /// Inspect static Mixin targets, overlaps, and overwrite risks.
    MixinMap(MixinMapArgs),
    /// Import and summarize Spark performance reports.
    SparkMap(SparkMapArgs),
    /// Compatibility Lab: corpus locks, smoke-test ingestion, matrices.
    Lab(LabArgs),
    /// Validate declarative rule packs.
    Rules(RulesArgs),
    /// Query the DuckDB analytics store (`--features duckdb`).
    Db(DbArgs),
    /// Recurring conflicts across persisted diagnosis runs.
    History(HistoryArgs),
    /// Time-series analytics over persisted runs.
    Trends(TrendsArgs),
    /// Jar scan cache maintenance (`stats`, `prune`, `clear`).
    Cache(CacheArgs),
    /// SBOM export (SPDX / CycloneDX) from a mods directory.
    Sbom(SbomArgs),
    /// Presentation demo: aggregate a small real-mod run into launcher-facing reports.
    Demo(DemoArgs),
}

// Explicit paths keep these shared definitions usable both from `lib.rs` and
// from `build.rs`, which includes this facade directly to generate man pages
// and shell completions.
#[path = "command/analytics.rs"]
mod analytics;
#[path = "command/doctor.rs"]
mod doctor;
#[path = "command/graph.rs"]
mod graph;
#[path = "command/lab.rs"]
mod lab;
#[path = "command/maintenance.rs"]
mod maintenance;
#[path = "command/rules.rs"]
mod rules;

pub use analytics::*;
pub use doctor::*;
pub use graph::*;
pub use lab::*;
pub use maintenance::*;
pub use rules::*;
