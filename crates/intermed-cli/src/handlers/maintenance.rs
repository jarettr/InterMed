use crate::*;

pub(crate) fn run_cache(args: CacheArgs) -> ExitCode {
    let cache_dir = match &args.command {
        CacheCommand::Stats(a) | CacheCommand::Prune(a) | CacheCommand::Clear(a) => {
            a.cache_dir.clone()
        }
    };
    let cache = match JarCache::new(true, cache_dir) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("error: cache init: {e}");
            return ExitCode::from(2);
        }
    };
    match args.command {
        CacheCommand::Stats(stats) => {
            let s = cache.stats_with_disk_usage();
            println!("InterMed Jar Cache");
            println!("root: {}", cache.root().display());
            println!("hits: {}", s.hits);
            println!("misses: {}", s.misses);
            println!("writes: {}", s.writes);
            println!("fast_hits: {}", s.fast_hits);
            println!("coalesced: {}", s.coalesced);
            println!("bytes_on_disk: {}", s.bytes_on_disk);
            let _ = stats;
            ExitCode::SUCCESS
        }
        CacheCommand::Prune(_) => match cache.prune_now() {
            Ok(freed) => {
                println!("pruned {freed} bytes");
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("error: prune failed: {e}");
                ExitCode::from(2)
            }
        },
        CacheCommand::Clear(_) => match cache.clear_all() {
            Ok(freed) => {
                println!("cleared {freed} bytes");
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("error: clear failed: {e}");
                ExitCode::from(2)
            }
        },
    }
}

pub(crate) fn run_demo(args: DemoArgs) -> ExitCode {
    match args.command {
        DemoCommand::Report(report_args) => {
            if !report_args.run_dir.is_dir() {
                eprintln!(
                    "error: demo run directory does not exist: {}",
                    report_args.run_dir.display()
                );
                return ExitCode::from(2);
            }
            let version = env!("CARGO_PKG_VERSION");
            let tool_version = format!("intermed {version}");
            match write_demo_artifacts(&report_args.run_dir, &report_args.out, &tool_version) {
                Ok((_report, artifacts)) => {
                    println!("wrote {}", artifacts.summary_md.display());
                    println!("wrote {}", artifacts.report_html.display());
                    println!("wrote {}", artifacts.report_json.display());
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    eprintln!("error: {e}");
                    ExitCode::from(2)
                }
            }
        }
    }
}

pub(crate) fn run_sbom(args: SbomArgs) -> ExitCode {
    match args.command {
        SbomCommand::Export(export) => {
            let mut target = match detect_target_or_exit(&export.target) {
                Ok(t) => t,
                Err(code) => return code,
            };
            if let Some(md) = export.mods_dir {
                target.mods_dir = Some(md);
            }
            let mods_dir = match cli_mods_dir(&target) {
                Some(d) => d,
                None => {
                    eprintln!("error: no mods directory found for SBOM export");
                    return ExitCode::from(2);
                }
            };
            let scan = match scan_mods_dir(&mods_dir) {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("error: {e}");
                    return ExitCode::from(2);
                }
            };
            let format = match export.format {
                SbomExportFormatCli::SpdxJson => SbomExportFormat::SpdxJson,
                SbomExportFormatCli::CycloneDxJson => SbomExportFormat::CycloneDxJson,
            };
            let text = match export_scan(&scan, format) {
                Ok(t) => t,
                Err(e) => {
                    eprintln!("error: export failed: {e}");
                    return ExitCode::from(2);
                }
            };
            if let Some(path) = export.out {
                if let Err(e) = write_atomic(&path, text.as_bytes()) {
                    eprintln!("error: write {}: {e}", path.display());
                    return ExitCode::from(2);
                }
                println!("wrote {}", path.display());
            } else {
                print!("{text}");
            }
            ExitCode::SUCCESS
        }
    }
}
