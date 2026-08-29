use crate::*;

pub(crate) fn detect_target_or_exit(path: &Path) -> Result<Target, ExitCode> {
    if !path.exists() {
        eprintln!("error: target does not exist: {}", path.display());
        return Err(ExitCode::from(2));
    }
    Ok(detect_target(path))
}

pub(crate) fn cli_mods_dir(target: &Target) -> Option<PathBuf> {
    target.mods_dir()
}
