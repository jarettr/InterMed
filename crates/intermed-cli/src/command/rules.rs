use super::*;

#[derive(Args)]
pub struct RulesArgs {
    #[command(subcommand)]
    pub command: RulesCommand,
}

#[derive(Subcommand)]
pub enum RulesCommand {
    /// Validate rule-pack JSON/YAML files under a path.
    Check(RulesCheckArgs),
    /// Generate backend artifacts (SQL, Datalog, Rust stubs) from a rule pack.
    Generate(RulesGenerateArgs),
    /// Sign a v2 rule pack with an Ed25519 key.
    Sign(RulesSignArgs),
    /// Verify a signed rule pack (optional trusted-keys file).
    Verify(RulesVerifyArgs),
    /// Refresh an installed pack from the registry (embedded core by default).
    Update(RulesUpdateArgs),
    /// List packs in a registry index (embedded default if omitted).
    Registry(RulesRegistryArgs),
    /// Install a pack and its registry dependencies into XDG rule-packs.
    Install(RulesInstallArgs),
    /// Show the query-engine plan (EXPLAIN, and EXPLAIN ANALYZE with `--facts`) per rule.
    Explain(RulesExplainArgs),
}

#[derive(Args)]
#[command(
    after_help = "Examples:\n  intermed rules explain                       # static EXPLAIN, all core rules\n  intermed rules explain --rule duplicate-id\n  intermed doctor ./mods --dump-facts f.json && intermed rules explain --facts f.json --rule resource-conflict-safe-crdt-merge"
)]
pub struct RulesExplainArgs {
    #[arg(
        default_value = "",
        help = "Rule pack JSON/YAML (default: embedded core v2 when empty or missing)"
    )]
    pub pack: PathBuf,

    /// Explain only this rule id (default: every lowerable rule).
    #[arg(long = "rule", value_name = "ID")]
    pub rule: Option<String>,

    /// A `doctor --dump-facts` JSON file: enables EXPLAIN ANALYZE on those real facts.
    #[arg(long = "facts", value_name = "FILE")]
    pub facts: Option<PathBuf>,
}

#[derive(Clone, Copy, Debug, clap::ValueEnum)]
pub enum RulesGenerateBackend {
    Sql,
    Rust,
    Datalog,
    /// Columnar query-engine `EXPLAIN` (logical + physical plan + engines) per rule.
    Explain,
}

#[derive(Args)]
#[command(
    after_help = "Example:\n  intermed rules generate --backend sql rules/core/intermed-core.rules.v2.json"
)]
pub struct RulesGenerateArgs {
    #[arg(
        default_value = "",
        help = "Rule pack JSON/YAML (default: embedded core v2 when empty or missing)"
    )]
    pub pack: PathBuf,

    /// Output backend: sql, rust, or datalog.
    #[arg(long = "backend", value_enum, default_value_t = RulesGenerateBackend::Sql)]
    pub backend: RulesGenerateBackend,

    /// Write to file instead of stdout.
    #[arg(long = "out", value_name = "FILE")]
    pub out: Option<PathBuf>,
}

#[derive(Args)]
#[command(after_help = "Example:\n  intermed rules check ./rules")]
pub struct RulesCheckArgs {
    /// Rule pack file or directory. Defaults to ./rules.
    #[arg(default_value = "rules")]
    pub path: PathBuf,

    /// Require a valid Ed25519 signature on v2 packs.
    #[arg(long = "require-signature")]
    pub require_signature: bool,

    /// Trusted publisher public keys (one base64 key per line).
    #[arg(long = "trusted-keys", value_name = "FILE")]
    pub trusted_keys: Option<PathBuf>,

    /// Dry-run: evaluate each rule against facts JSON and print a trace table.
    #[arg(long)]
    pub trace: bool,

    /// Fact snapshot JSON for `--trace` (from `doctor --dump-facts`).
    #[arg(long = "facts", value_name = "FILE", requires = "trace")]
    pub facts: Option<PathBuf>,
}

#[derive(Args)]
#[command(
    after_help = "Example:\n  intermed rules sign rules/core/intermed-core.rules.json --key ./publisher.key"
)]
pub struct RulesSignArgs {
    /// Unsigned v2 rule pack to sign.
    pub pack: PathBuf,

    /// Ed25519 seed file (32 raw bytes or base64 text).
    #[arg(long = "key", value_name = "FILE")]
    pub key: PathBuf,

    /// Output signed pack path (default: overwrite input).
    #[arg(long = "out", value_name = "FILE")]
    pub out: Option<PathBuf>,
}

#[derive(Args)]
pub struct RulesVerifyArgs {
    /// Signed rule pack to verify.
    pub pack: PathBuf,

    /// Trusted publisher public keys (one base64 key per line).
    #[arg(long = "trusted-keys", value_name = "FILE")]
    pub trusted_keys: Option<PathBuf>,
}

#[derive(Args)]
#[command(after_help = "Example:\n  intermed rules update --pack intermed-core")]
pub struct RulesUpdateArgs {
    /// Registry index JSON or URL (`intermed-rule-registry-v1`). Defaults to embedded + community index.
    #[arg(long = "registry", value_name = "FILE|URL")]
    pub registry: Option<String>,

    /// Pack id to refresh (default: intermed-core).
    #[arg(long = "pack", default_value = "intermed-core")]
    pub pack_id: String,

    /// Install directory (default: XDG data/intermed/rule-packs).
    #[arg(long = "install-dir", value_name = "DIR")]
    pub install_dir: Option<PathBuf>,

    /// Trusted publisher public keys (one base64 key per line) to pin signatures against.
    #[arg(long = "trusted-keys", value_name = "FILE")]
    pub trusted_keys: Option<PathBuf>,

    /// Allow `http://` registries/packs (insecure; HTTPS is required by default).
    #[arg(long = "allow-insecure-registry")]
    pub allow_insecure_registry: bool,

    /// Accept unsigned, or signed-but-unpinned, remote rule packs.
    #[arg(long = "allow-unsigned-rules")]
    pub allow_unsigned_rules: bool,
}

#[derive(Args)]
#[command(after_help = "Example:\n  intermed rules install --pack community-mixin-pack")]
pub struct RulesInstallArgs {
    #[arg(long = "registry", value_name = "FILE|URL")]
    pub registry: Option<String>,

    #[arg(long = "pack", required = true)]
    pub pack_id: String,

    #[arg(long = "install-dir", value_name = "DIR")]
    pub install_dir: Option<PathBuf>,

    #[arg(long = "trusted-keys", value_name = "FILE")]
    pub trusted_keys: Option<PathBuf>,

    /// Allow `http://` registries/packs (insecure; HTTPS is required by default).
    #[arg(long = "allow-insecure-registry")]
    pub allow_insecure_registry: bool,

    /// Accept unsigned, or signed-but-unpinned, remote rule packs.
    #[arg(long = "allow-unsigned-rules")]
    pub allow_unsigned_rules: bool,
}

#[derive(Args)]
pub struct RulesRegistryArgs {
    /// Registry index JSON or URL. Defaults to the embedded InterMed registry.
    #[arg(long = "registry", value_name = "FILE|URL")]
    pub registry: Option<String>,

    /// Allow `http://` registries (insecure; HTTPS is required by default).
    #[arg(long = "allow-insecure-registry")]
    pub allow_insecure_registry: bool,
}
