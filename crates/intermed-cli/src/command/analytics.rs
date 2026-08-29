use super::*;

#[derive(Args)]
#[command(
    after_help = "Example:\n  intermed db query --db history.duckdb \"SELECT kind, COUNT(*) FROM facts GROUP BY kind\""
)]
pub struct DbArgs {
    #[command(subcommand)]
    pub command: DbCommand,
}

#[derive(Args)]
#[command(after_help = "Examples:\n  \
intermed history conflicts --db history.duckdb --since 30d\n  \
intermed history conflicts --db history.duckdb --since 7d")]
pub struct HistoryArgs {
    #[command(subcommand)]
    pub command: HistoryCommand,
}

#[derive(Subcommand)]
pub enum HistoryCommand {
    /// Findings that recur across multiple runs within a time window.
    Conflicts(HistoryConflictsArgs),
    /// Recurring *kinds* of risk (rule + category) rolled up across all history.
    Patterns(HistoryPatternsArgs),
    /// Compare findings between two persisted runs.
    Diff(HistoryDiffArgs),
    /// Delete analytics runs older than a retention window.
    Prune(HistoryPruneArgs),
}

#[derive(Args)]
pub struct HistoryPatternsArgs {
    /// DuckDB analytics database file.
    #[arg(long = "db", value_name = "FILE")]
    pub db: PathBuf,

    /// Maximum patterns to show (highest severity / most recurring first).
    #[arg(long = "limit", default_value_t = 20, value_name = "N")]
    pub limit: usize,
}

#[derive(Args)]
pub struct HistoryDiffArgs {
    #[arg(long = "db", value_name = "FILE")]
    pub db: PathBuf,

    #[arg(long = "run-a", value_name = "RUN_ID")]
    pub run_a: String,

    #[arg(long = "run-b", value_name = "RUN_ID")]
    pub run_b: String,

    /// Emit structured JSON (`intermed-history-diff-v1`) instead of TSV.
    #[arg(long)]
    pub json: bool,
}

#[derive(Args)]
pub struct HistoryPruneArgs {
    #[arg(long = "db", value_name = "FILE")]
    pub db: PathBuf,

    /// Keep runs within this window (`90d`, `30d`). Older runs are deleted.
    #[arg(long = "keep", default_value = "90d", value_name = "DURATION")]
    pub keep: String,
}

#[derive(Args)]
pub struct HistoryConflictsArgs {
    /// DuckDB analytics database file.
    #[arg(long = "db", value_name = "FILE")]
    pub db: PathBuf,

    /// Relative look-back window (`30d`, `7d`, `24h`). Default: 30d.
    #[arg(long = "since", default_value = "30d", value_name = "DURATION")]
    pub since: String,
}

#[derive(Args)]
#[command(after_help = "Examples:\n  \
intermed trends mixin-risk --db history.duckdb\n  \
intermed trends mixin-overlaps --db history.duckdb --limit 10")]
pub struct TrendsArgs {
    #[command(subcommand)]
    pub command: TrendsCommand,
}

#[derive(Subcommand)]
pub enum TrendsCommand {
    /// Mixin-category finding counts per persisted run.
    MixinRisk(TrendsDbArgs),
    /// Top-N most frequent mixin overlaps (by mod set + target).
    MixinOverlaps(TrendsMixinOverlapsArgs),
}

#[derive(Args)]
pub struct TrendsDbArgs {
    /// DuckDB analytics database file.
    #[arg(long = "db", value_name = "FILE")]
    pub db: PathBuf,
}

#[derive(Args)]
pub struct TrendsMixinOverlapsArgs {
    /// DuckDB analytics database file.
    #[arg(long = "db", value_name = "FILE")]
    pub db: PathBuf,

    /// Number of rows to return (default: 10).
    #[arg(long = "limit", default_value_t = 10)]
    pub limit: usize,
}

#[derive(Subcommand)]
pub enum DbCommand {
    /// Run a read-only SQL query against the analytics store.
    Query(DbQueryArgs),
}

#[derive(Args)]
pub struct DbQueryArgs {
    /// DuckDB analytics database file.
    #[arg(long = "db", value_name = "FILE")]
    pub db: PathBuf,

    /// SQL to execute (read-only analytics).
    pub sql: String,
}
