use super::*;

#[derive(Args)]
#[command(
    after_help = "Examples:\n  intermed vfs scan ./mods\n  intermed vfs explain ./mods\n  intermed vfs overlay ./mods --out ./overlay-preview\n  intermed vfs overlay ./mods --out ./overlay-preview --include-unsafe-winners"
)]
pub struct VfsArgs {
    #[command(subcommand)]
    pub command: VfsCommand,
}

#[derive(Subcommand)]
pub enum VfsCommand {
    /// Scan jar assets/data writers and summarize resource collisions.
    Scan(VfsTargetArgs),
    /// Explain each resource collision and its merge/override class.
    Explain(VfsTargetArgs),
    /// Write a read-only overlay preview directory from detected collisions.
    Overlay(VfsOverlayArgs),
}

#[derive(Args)]
pub struct VfsTargetArgs {
    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Explain a single resource path (e.g. `data/create/recipes/crushing/tuff.json`).
    #[arg(long = "path", value_name = "RESOURCE_PATH")]
    pub path: Option<String>,

    /// Show the Layer-M typed AST view (domain, semantic diff, references) for
    /// `--path`. Requires `--path`.
    #[arg(long = "ast")]
    pub ast: bool,

    /// AST depth used by `--ast`: `semantic` (default) or `full`.
    #[arg(
        long = "resource-level",
        value_enum,
        value_name = "LEVEL",
        default_value = "full"
    )]
    pub resource_level: ResourceLevelArg,

    /// Accepted for script consistency; VFS output currently has no ANSI colour.
    #[arg(long = "no-color")]
    pub _no_color: bool,
}

#[derive(Args)]
pub struct VfsOverlayArgs {
    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// New output directory for the overlay preview.
    #[arg(long)]
    pub out: PathBuf,

    /// Also stage order-dependent collisions by picking a lexical winner. These
    /// are previews, NOT safe fixes: the manifest marks them safe_to_apply=false.
    /// By default only deterministic, order-independent merges are written.
    #[arg(long = "include-unsafe-winners")]
    pub include_unsafe_winners: bool,

    /// Print the semantic overlay plan (`intermed-overlay-plan-v2`: safe / review /
    /// unsafe buckets) to stdout and exit — read-only, writes nothing.
    #[arg(long = "explain-plan")]
    pub explain_plan: bool,

    /// Accepted for script consistency; VFS output currently has no ANSI colour.
    #[arg(long = "no-color")]
    pub _no_color: bool,
}

#[derive(Args)]
#[command(after_help = "Examples:\n  \
intermed deps graph ./mods\n  \
intermed deps resolve ./mods\n  \
intermed deps why create ./mods\n  \
intermed deps why-missing balm-fabric ./mods\n  \
intermed deps implicit ./mods --namespace create\n  \
intermed deps path waystones balm-fabric ./mods")]
pub struct DepsArgs {
    #[command(subcommand)]
    pub command: DepsCommand,
}

#[derive(Subcommand)]
pub enum DepsCommand {
    /// Export the modpack dependency graph (`intermed-modpack-graph-v1` JSON).
    Graph(DepsTargetArgs),
    /// Run PubGrub resolution and emit `intermed-deps-resolution-v1` JSON.
    Resolve(DepsTargetArgs),
    /// Explain why a mod/namespace is depended upon (declared + implicit reasons).
    Why(DepsIdArgs),
    /// Explain why an absent dependency is required (the requiring edges).
    WhyMissing(DepsIdArgs),
    /// List implicit references into a namespace (resource-derived dependencies).
    Implicit(DepsImplicitArgs),
    /// Find a dependency chain between two mods (`deps path <from> <to>`).
    Path(DepsPathArgs),
}

#[derive(Args)]
pub struct DepsTargetArgs {
    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Override the mods directory (otherwise auto-detected).
    #[arg(long = "mods-dir")]
    pub mods_dir: Option<PathBuf>,
}

#[derive(Args)]
pub struct DepsIdArgs {
    /// The mod id or namespace to explain.
    pub id: String,

    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Override the mods directory (otherwise auto-detected).
    #[arg(long = "mods-dir")]
    pub mods_dir: Option<PathBuf>,

    /// Emit machine-readable JSON instead of text.
    #[arg(long = "json")]
    pub json: bool,
}

#[derive(Args)]
pub struct DepsImplicitArgs {
    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// The provider namespace to list implicit references for.
    #[arg(long = "namespace", value_name = "NS")]
    pub namespace: String,

    /// Override the mods directory (otherwise auto-detected).
    #[arg(long = "mods-dir")]
    pub mods_dir: Option<PathBuf>,

    /// Emit machine-readable JSON instead of text.
    #[arg(long = "json")]
    pub json: bool,
}

#[derive(Args)]
pub struct DepsPathArgs {
    /// Source mod id.
    pub from: String,

    /// Target mod id / namespace.
    pub to: String,

    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Override the mods directory (otherwise auto-detected).
    #[arg(long = "mods-dir")]
    pub mods_dir: Option<PathBuf>,

    /// Emit machine-readable JSON instead of text.
    #[arg(long = "json")]
    pub json: bool,
}

#[derive(Args)]
#[command(after_help = "Examples:\n  \
intermed impact remove create ./mods\n  \
intermed impact update sodium 0.5.8 0.6.0 ./mods")]
pub struct ImpactArgs {
    #[command(subcommand)]
    pub command: ImpactCommand,
}

#[derive(Subcommand)]
pub enum ImpactCommand {
    /// Blast radius of removing a mod (reverse resource graph + dependents).
    Remove(ImpactRemoveArgs),
    /// Blast radius of bumping a mod's version (which declared ranges reject it).
    Update(ImpactUpdateArgs),
}

#[derive(Args)]
pub struct ImpactRemoveArgs {
    /// The mod id / namespace to remove.
    pub id: String,

    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Override the mods directory (otherwise auto-detected).
    #[arg(long = "mods-dir")]
    pub mods_dir: Option<PathBuf>,

    /// Emit machine-readable JSON instead of text.
    #[arg(long = "json")]
    pub json: bool,
}

#[derive(Args)]
pub struct ImpactUpdateArgs {
    /// The mod id to update.
    pub id: String,

    /// Current version (use `-` to omit and only check the target version).
    pub from: String,

    /// Proposed new version.
    pub to: String,

    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Override the mods directory (otherwise auto-detected).
    #[arg(long = "mods-dir")]
    pub mods_dir: Option<PathBuf>,

    /// Emit machine-readable JSON instead of text.
    #[arg(long = "json")]
    pub json: bool,
}

#[derive(Args)]
#[command(
    after_help = "Example:\n  intermed spark-map ./server --spark-report ./spark/profile.json"
)]
pub struct SparkMapArgs {
    /// Server/instance directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Explicit spark report JSON (`intermed-spark-report-v1`).
    #[arg(long = "spark-report", value_name = "FILE")]
    pub spark_report: Option<PathBuf>,

    /// Accepted for script consistency; Spark Map output currently has no ANSI colour.
    #[arg(long = "no-color")]
    pub _no_color: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, ValueEnum)]
pub enum GraphExportFormat {
    /// Human-readable mixin map summary (default).
    Json,
    /// Machine-readable interaction graph (`MixinGraphExport` JSON).
    #[value(name = "graph-json")]
    GraphData,
    Dot,
    Graphml,
    Html,
}

#[derive(Args)]
#[command(
    after_help = "Examples:\n  intermed mixin-map ./mods\n  intermed mixin-map ./mods --graph-format dot --graph-out mixin.dot"
)]
pub struct MixinMapArgs {
    /// Mods directory or instance/server directory. Defaults to current dir.
    #[arg(default_value = ".")]
    pub target: PathBuf,

    /// Graph export format (default: json summary).
    #[arg(long = "graph-format", value_enum, default_value_t = GraphExportFormat::Json)]
    pub graph_format: GraphExportFormat,

    /// Write graph export to file (stdout when omitted for dot/graphml).
    #[arg(long = "graph-out", value_name = "FILE")]
    pub graph_out: Option<PathBuf>,

    /// Accepted for script consistency; Mixin Map output currently has no ANSI colour.
    #[arg(long = "no-color")]
    pub _no_color: bool,
}
