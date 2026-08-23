//! Sandboxed live-process execution contracts for Compatibility Lab campaigns.
//!
//! The deterministic evidence pipeline ingests captured
//! [`RawSmokeOutput`](crate::run::RawSmokeOutput) or executes an explicit command
//! plan, then emits the same JSON shape for classification and evaluation.
//!
//! Acquisition and loader installation stay outside this module. The command
//! backend executes only explicit plans and refuses to run when isolation was
//! required but not configured.

use std::collections::BTreeMap;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::LabError;
use crate::corpus::{CorpusEnvironment, CorpusLock};
use crate::run::RawSmokeOutput;

/// Everything required to boot one lab environment (loader + MC + side).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EnvironmentSpec {
    pub environment: CorpusEnvironment,
    /// Directory containing corpus jars (from [`CorpusLock`]).
    pub mods_dir: PathBuf,
    /// Working directory for the server process (world, logs, configs).
    pub work_dir: PathBuf,
    /// Hard wall-clock budget for startup + soak.
    pub time_budget: Duration,
    /// TCP port the server should bind (0 = ephemeral).
    pub port: u16,
    #[serde(default)]
    pub limits: ExecutionLimits,
}

impl EnvironmentSpec {
    /// Build a spec from a locked corpus and output workspace.
    #[must_use]
    pub fn from_lock(lock: &CorpusLock, mods_dir: PathBuf, work_dir: PathBuf) -> Self {
        Self {
            environment: lock.environment.clone(),
            mods_dir,
            work_dir,
            time_budget: Duration::from_secs(180),
            port: 0,
            limits: ExecutionLimits::default(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExecutionLimits {
    pub wall_time_secs: u64,
    pub max_log_bytes: u64,
    pub memory_bytes: u64,
    pub cpu_quota_percent: u16,
    pub max_processes: u32,
    pub max_written_bytes: u64,
}

impl Default for ExecutionLimits {
    fn default() -> Self {
        Self {
            wall_time_secs: 300,
            max_log_bytes: 32 * 1024 * 1024,
            memory_bytes: 8 * 1024 * 1024 * 1024,
            cpu_quota_percent: 400,
            max_processes: 512,
            max_written_bytes: 4 * 1024 * 1024 * 1024,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
pub enum NetworkPolicy {
    #[default]
    Deny,
    LoopbackOnly,
    Allow,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum SandboxPolicy {
    /// Refuse to execute without a supported isolation backend.
    #[default]
    Required,
    /// Linux bubblewrap isolation: new namespaces, no host home, writable work
    /// directory only. Network remains isolated unless explicitly allowed.
    Bubblewrap,
    /// Use an external sandbox prefix such as `bwrap ... --`.
    External { prefix: Vec<String> },
    /// Explicit expert opt-in. Reports retain this unsafe choice.
    UnsafeHost,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExecutionPlan {
    pub id: String,
    pub command: Vec<String>,
    pub work_dir: PathBuf,
    #[serde(default)]
    pub environment: BTreeMap<String, String>,
    #[serde(default)]
    pub sandbox: SandboxPolicy,
    #[serde(default)]
    pub network: NetworkPolicy,
    #[serde(default)]
    pub limits: ExecutionLimits,
    /// Limits guaranteed by an external wrapper (for example a cgroup runner).
    /// These declarations are retained in the observation for audit.
    #[serde(default)]
    pub externally_enforced_limits: Vec<String>,
}

/// A launched server/client process handle.
#[derive(Debug)]
pub struct RunningProcess {
    pub pid: u32,
    pub log_path: PathBuf,
}

/// Outcome after waiting for process exit or timeout.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessOutcome {
    pub exited_ok: bool,
    pub timed_out: bool,
    pub log: String,
    pub exit_code: Option<i32>,
    pub log_complete: bool,
    pub infrastructure_failure: bool,
    pub harness_failure: bool,
    pub wall_time_ms: u64,
    pub enforced_limits: Vec<String>,
    pub isolation: String,
}

/// Boots environments and returns raw smoke outputs compatible with [`SmokeRunner`](crate::run::SmokeRunner).
///
/// Implementations: `CapturedLogRunner` (in-tree), future `ServerProcessRunner` (live JVM).
pub trait EnvironmentRunner: Send + Sync {
    /// Human-readable runner id (`captured-logs`, `live-server`, …).
    fn id(&self) -> &'static str;

    /// Produce one [`RawSmokeOutput`] per environment label in `specs`.
    fn run_environments(&self, specs: &[EnvironmentSpec]) -> Result<Vec<RawSmokeOutput>, LabError>;
}

/// Low-level process control for one environment (install loader, launch JVM, tail log).
///
/// `EnvironmentBootstrap` + loader installers compose into this trait; `lab run` never
/// calls it directly — only a live [`EnvironmentRunner`] implementation does.
pub trait ServerProcessRunner: Send + Sync {
    /// Prepare the working directory (download loader, lay out jars).
    fn prepare(&self, spec: &EnvironmentSpec) -> Result<(), LabError>;

    /// Launch the server and return a handle for log tailing.
    fn launch(&self, spec: &EnvironmentSpec) -> Result<RunningProcess, LabError>;

    /// Block until exit or `spec.time_budget`, returning captured log text.
    fn wait(
        &self,
        spec: &EnvironmentSpec,
        process: RunningProcess,
    ) -> Result<ProcessOutcome, LabError>;
}

/// Map a [`ProcessOutcome`] into the shared smoke-output schema.
#[must_use]
pub fn outcome_to_smoke(environment: &str, outcome: ProcessOutcome) -> RawSmokeOutput {
    RawSmokeOutput {
        schema: crate::run::SMOKE_OUTPUT_SCHEMA.into(),
        environment: environment.to_string(),
        exited_ok: outcome.exited_ok,
        timed_out: outcome.timed_out,
        log: outcome.log,
        exit_code: outcome.exit_code,
        log_complete: outcome.log_complete,
        infrastructure_failure: outcome.infrastructure_failure,
        harness_failure: outcome.harness_failure,
        skipped: false,
        wall_time_ms: Some(outcome.wall_time_ms),
        enforced_limits: outcome.enforced_limits,
        isolation: outcome.isolation,
    }
}

/// Minimal local execution backend used by campaign workers.
///
/// It fails closed unless an external sandbox command is supplied. `UnsafeHost`
/// exists for controlled CI containers and is always visible in the plan.
#[derive(Debug, Default)]
pub struct CommandExecutionBackend;

impl CommandExecutionBackend {
    pub fn execute(&self, plan: &ExecutionPlan) -> Result<ProcessOutcome, LabError> {
        if plan.command.is_empty() {
            return Err(LabError::new("execution plan has an empty command"));
        }
        if matches!(plan.sandbox, SandboxPolicy::Required) {
            return Err(LabError::new(
                "execution refused: no sandbox backend configured (use an external sandbox prefix)",
            ));
        }
        std::fs::create_dir_all(&plan.work_dir).map_err(|error| {
            LabError::new(format!("create {}: {error}", plan.work_dir.display()))
        })?;
        let log_path = plan.work_dir.join("intermed-lab-process.log");
        let stdout = File::create(&log_path)
            .map_err(|error| LabError::new(format!("create {}: {error}", log_path.display())))?;
        let stderr = stdout.try_clone().map_err(|error| {
            LabError::new(format!("clone capture {}: {error}", log_path.display()))
        })?;

        let (program, args) = command_parts(plan)?;
        let mut command = Command::new(&program);
        command
            .args(args)
            .current_dir(&plan.work_dir)
            .env_clear()
            .envs(plan.environment.iter())
            .stdin(Stdio::null())
            .stdout(Stdio::from(stdout))
            .stderr(Stdio::from(stderr));
        let started = std::time::Instant::now();
        let mut child = command
            .spawn()
            .map_err(|error| LabError::new(format!("launch {}: {error}", plan.id)))?;

        let deadline =
            std::time::Instant::now() + Duration::from_secs(plan.limits.wall_time_secs.max(1));
        let (status, timed_out) = loop {
            match child.try_wait() {
                Ok(Some(status)) => break (Some(status), false),
                Ok(None) if std::time::Instant::now() < deadline => {
                    thread::sleep(Duration::from_millis(50));
                }
                Ok(None) => {
                    let _ = child.kill();
                    break (child.wait().ok(), true);
                }
                Err(error) => {
                    let _ = child.kill();
                    return Err(LabError::new(format!("wait for {}: {error}", plan.id)));
                }
            }
        };

        let (log, log_complete) = read_tail(&log_path, plan.limits.max_log_bytes)?;
        let mut enforced_limits = vec!["wall-time".to_string(), "captured-log-bytes".to_string()];
        let isolation = match plan.sandbox {
            SandboxPolicy::Bubblewrap => {
                enforced_limits.push("filesystem-namespace".to_string());
                if plan.network != NetworkPolicy::Allow {
                    enforced_limits.push("network-namespace".to_string());
                }
                "bubblewrap"
            }
            SandboxPolicy::External { .. } => "external-sandbox",
            SandboxPolicy::UnsafeHost => "unsafe-host",
            SandboxPolicy::Required => unreachable!("required sandbox rejected before launch"),
        };
        if matches!(plan.sandbox, SandboxPolicy::External { .. }) {
            enforced_limits.extend(plan.externally_enforced_limits.iter().cloned());
            enforced_limits.sort();
            enforced_limits.dedup();
        }
        Ok(ProcessOutcome {
            exited_ok: status
                .as_ref()
                .is_some_and(std::process::ExitStatus::success),
            timed_out,
            log,
            exit_code: status.and_then(|status| status.code()),
            log_complete,
            infrastructure_failure: false,
            harness_failure: false,
            wall_time_ms: started.elapsed().as_millis().min(u64::MAX as u128) as u64,
            enforced_limits,
            isolation: isolation.to_string(),
        })
    }
}

fn command_parts(plan: &ExecutionPlan) -> Result<(String, Vec<String>), LabError> {
    match &plan.sandbox {
        SandboxPolicy::Required => Err(LabError::new("sandbox backend is required")),
        SandboxPolicy::UnsafeHost => Ok((plan.command[0].clone(), plan.command[1..].to_vec())),
        SandboxPolicy::External { prefix } => {
            let Some(program) = prefix.first() else {
                return Err(LabError::new("external sandbox prefix is empty"));
            };
            let mut args = prefix[1..].to_vec();
            args.extend(plan.command.iter().cloned());
            Ok((program.clone(), args))
        }
        SandboxPolicy::Bubblewrap => {
            let mut args = vec![
                "--die-with-parent".to_string(),
                "--new-session".to_string(),
                "--unshare-all".to_string(),
            ];
            if plan.network == NetworkPolicy::Allow {
                args.push("--share-net".to_string());
            }
            for root in ["/usr", "/lib", "/lib64", "/bin", "/sbin"] {
                if Path::new(root).exists() {
                    args.extend(["--ro-bind".to_string(), root.to_string(), root.to_string()]);
                }
            }
            args.extend([
                "--proc".to_string(),
                "/proc".to_string(),
                "--dev".to_string(),
                "/dev".to_string(),
                "--tmpfs".to_string(),
                "/tmp".to_string(),
                "--bind".to_string(),
                plan.work_dir.display().to_string(),
                "/work".to_string(),
                "--chdir".to_string(),
                "/work".to_string(),
                "--".to_string(),
            ]);
            args.extend(plan.command.iter().cloned());
            Ok(("bwrap".to_string(), args))
        }
    }
}

fn read_tail(path: &Path, max_bytes: u64) -> Result<(String, bool), LabError> {
    use std::io::{Seek, SeekFrom};
    let mut file = File::open(path)
        .map_err(|error| LabError::new(format!("read {}: {error}", path.display())))?;
    let len = file
        .metadata()
        .map_err(|error| LabError::new(format!("stat {}: {error}", path.display())))?
        .len();
    let start = len.saturating_sub(max_bytes);
    file.seek(SeekFrom::Start(start))
        .map_err(|error| LabError::new(format!("seek {}: {error}", path.display())))?;
    let mut bytes = Vec::with_capacity((len - start).min(usize::MAX as u64) as usize);
    file.read_to_end(&mut bytes)
        .map_err(|error| LabError::new(format!("read {}: {error}", path.display())))?;
    Ok((String::from_utf8_lossy(&bytes).into_owned(), start == 0))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plan(sandbox: SandboxPolicy) -> ExecutionPlan {
        ExecutionPlan {
            id: "test".into(),
            command: vec![
                "/bin/sh".into(),
                "-c".into(),
                "printf 'Done (1.0s)!'".into(),
            ],
            work_dir: std::env::temp_dir().join(format!(
                "intermed-exec-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            )),
            environment: BTreeMap::new(),
            sandbox,
            network: NetworkPolicy::Deny,
            limits: ExecutionLimits {
                wall_time_secs: 5,
                max_log_bytes: 1024,
                ..ExecutionLimits::default()
            },
            externally_enforced_limits: Vec::new(),
        }
    }

    #[test]
    fn required_sandbox_fails_closed() {
        let error = CommandExecutionBackend
            .execute(&plan(SandboxPolicy::Required))
            .unwrap_err();
        assert!(error.to_string().contains("sandbox"));
    }

    #[test]
    fn unsafe_host_is_explicit_and_still_bounded() {
        let plan = plan(SandboxPolicy::UnsafeHost);
        let work_dir = plan.work_dir.clone();
        let outcome = CommandExecutionBackend.execute(&plan).unwrap();
        assert!(outcome.exited_ok);
        assert_eq!(outcome.isolation, "unsafe-host");
        assert!(outcome.enforced_limits.contains(&"wall-time".to_string()));
        assert!(outcome.log.contains("Done"));
        std::fs::remove_dir_all(work_dir).ok();
    }
}
