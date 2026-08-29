use super::*;

#[derive(Args)]
#[command(
    after_help = "Examples:\n  intermed cache stats\n  intermed cache prune\n  intermed cache clear"
)]
pub struct CacheArgs {
    #[command(subcommand)]
    pub command: CacheCommand,
}

#[derive(Subcommand)]
pub enum CacheCommand {
    /// Show hit/miss counters and on-disk cache size.
    Stats(CacheStatsArgs),
    /// Force a prune pass (age + size limits).
    Prune(CacheStatsArgs),
    /// Delete all cached jar payloads and fingerprints.
    Clear(CacheStatsArgs),
}

#[derive(Args)]
pub struct CacheStatsArgs {
    #[arg(long = "cache-dir", value_name = "DIR")]
    pub cache_dir: Option<PathBuf>,
}

#[derive(Clone, Copy, Debug, ValueEnum)]
pub enum SbomExportFormatCli {
    #[value(name = "spdx-json")]
    SpdxJson,
    #[value(name = "cyclonedx-json")]
    CycloneDxJson,
}

#[derive(Args)]
#[command(
    after_help = "Examples:\n  intermed sbom export ./mods --format spdx-json\n  intermed sbom export ./mods --format cyclonedx-json --out sbom.json"
)]
pub struct SbomArgs {
    #[command(subcommand)]
    pub command: SbomCommand,
}

#[derive(Subcommand)]
pub enum SbomCommand {
    /// Export SPDX or CycloneDX SBOM from jar metadata.
    Export(SbomExportArgs),
}

#[derive(Args)]
pub struct SbomExportArgs {
    #[arg(default_value = ".")]
    pub target: PathBuf,

    #[arg(long = "mods-dir")]
    pub mods_dir: Option<PathBuf>,

    #[arg(long = "format", value_enum, default_value_t = SbomExportFormatCli::SpdxJson)]
    pub format: SbomExportFormatCli,

    #[arg(long = "out", value_name = "FILE")]
    pub out: Option<PathBuf>,
}

#[derive(Args)]
#[command(after_help = "Examples:\n  \
./scripts/intermed-demo-run.sh\n  \
intermed demo report ~/intermed_demo_runs/LATEST --out .")]
pub struct DemoArgs {
    #[command(subcommand)]
    pub command: DemoCommand,
}

#[derive(Subcommand)]
pub enum DemoCommand {
    /// Render markdown, HTML, and JSON presentation artifacts from a demo run directory.
    Report(DemoReportArgs),
}

#[derive(Args)]
pub struct DemoReportArgs {
    /// Directory produced by `scripts/intermed-demo-run.sh` (contains `corpus.json` and `doctor-*.txt`).
    pub run_dir: PathBuf,

    /// Output directory for `intermed-atlauncher-demo-summary.md`, `intermed-demo-report.html`, and JSON.
    #[arg(long, short = 'o', default_value = ".")]
    pub out: PathBuf,
}
