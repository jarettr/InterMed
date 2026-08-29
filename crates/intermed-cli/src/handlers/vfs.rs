use crate::*;

pub(crate) fn run_vfs(args: VfsArgs) -> ExitCode {
    match args.command {
        VfsCommand::Scan(args) => {
            let target = match detect_target_or_exit(&args.target) {
                Ok(target) => target,
                Err(code) => return code,
            };
            match intermed_vfs::scan_target(&target) {
                Ok(scan) => {
                    print_vfs_scan(&scan);
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    eprintln!("error: {e}");
                    ExitCode::from(2)
                }
            }
        }
        VfsCommand::Explain(args) => {
            let target = match detect_target_or_exit(&args.target) {
                Ok(target) => target,
                Err(code) => return code,
            };
            if args.ast || args.path.is_some() {
                return run_vfs_explain_ast(&target, &args);
            }
            match intermed_vfs::scan_target(&target) {
                Ok(scan) => {
                    print_vfs_explain(&scan);
                    ExitCode::SUCCESS
                }
                Err(e) => {
                    eprintln!("error: {e}");
                    ExitCode::from(2)
                }
            }
        }
        VfsCommand::Overlay(args) => {
            let target = match detect_target_or_exit(&args.target) {
                Ok(target) => target,
                Err(code) => return code,
            };
            let Some(mods_dir) = cli_mods_dir(&target) else {
                eprintln!("error: target has no mods directory");
                return ExitCode::from(2);
            };
            if args.explain_plan {
                match intermed_packops::build_overlay_plan_v2(&mods_dir) {
                    Ok(plan) => {
                        println!(
                            "{}",
                            serde_json::to_string_pretty(&plan)
                                .unwrap_or_else(|e| format!("{{\"error\":\"{e}\"}}"))
                        );
                        return ExitCode::SUCCESS;
                    }
                    Err(e) => {
                        eprintln!("error: {e}");
                        return ExitCode::from(2);
                    }
                }
            }
            match intermed_packops::write_overlay_preview(
                &mods_dir,
                &args.out,
                args.include_unsafe_winners,
            ) {
                Ok(plan) => {
                    println!(
                        "Overlay preview written to {} ({} item(s){})",
                        plan.out_dir,
                        plan.manifest.items.len(),
                        if plan.manifest.safe_to_apply {
                            ", safe to apply"
                        } else {
                            ", contains unsafe winner previews — NOT safe to apply as-is"
                        }
                    );
                    if !plan.manifest.skipped.is_empty() {
                        println!(
                            "Skipped {} order-dependent collision(s); rerun with \
                             --include-unsafe-winners to stage winner previews.",
                            plan.manifest.skipped.len()
                        );
                    }
                    println!("Manifest: {}/intermed-overlay-manifest.json", plan.out_dir);
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

/// `vfs explain --path <p> --ast`: the Layer-M typed view of one resource —
/// domain, every writer, the semantic diff between writers, and the outgoing
/// reference graph.
fn run_vfs_explain_ast(
    target: &intermed_doctor_core::Target,
    args: &intermed_cli::command::VfsTargetArgs,
) -> ExitCode {
    use intermed_cli::command::ResourceLevelArg;
    use intermed_resource_ast::ResourceLevel;

    let Some(path) = args.path.as_deref() else {
        eprintln!("error: --ast requires --path <resource-path>");
        return ExitCode::from(2);
    };
    let Some(mods_dir) = cli_mods_dir(target) else {
        eprintln!("error: target has no mods directory");
        return ExitCode::from(2);
    };
    let level = match args.resource_level {
        ResourceLevelArg::Basic => ResourceLevel::Semantic, // basic = no AST; promote so explain has data
        ResourceLevelArg::Semantic => ResourceLevel::Semantic,
        ResourceLevelArg::Full => ResourceLevel::Full,
    };

    let scan = match intermed_resource_ast::scan_mods_dir(&mods_dir, level) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };

    let records: Vec<&intermed_resource_ast::ResourceAstRecord> = scan
        .records
        .iter()
        .filter(|r| r.ast.resource_path == path)
        .collect();
    if records.is_empty() {
        println!(
            "No parsed resource at `{path}` (not present, binary, or not parsed at this level)."
        );
        return ExitCode::SUCCESS;
    }

    let domain = records[0].ast.domain.as_str();
    println!("Resource: {path}");
    println!("Domain:   {domain}");

    println!("Writers:");
    let mut writers: Vec<&str> = records.iter().map(|r| r.writer.as_str()).collect();
    writers.sort_unstable();
    writers.dedup();
    for w in &writers {
        println!("  - {w}");
    }

    // Semantic diff across writers (recipe output / lang key conflicts only).
    let diffs = intermed_resource_ast::diff::compute(&scan.records);
    if let Some(d) = diffs.iter().find(|d| d.path == path) {
        println!("Semantic diff:");
        println!("  kind:   {}", d.kind.as_str());
        println!("  detail: {}", d.detail);
    } else if writers.len() > 1 {
        let hashes: std::collections::BTreeSet<&str> = records
            .iter()
            .map(|r| r.ast.semantic_hash.as_str())
            .collect();
        if hashes.len() == 1 {
            println!("Semantic diff: none (all writers semantically identical — safe)");
        } else {
            println!(
                "Semantic diff: writers differ but not in a behaviour-changing way \
                 (benign union / single-doc override — see Layer E for the merge class)"
            );
        }
    }

    // Outgoing references from the (first writer's) AST.
    let refs = &records[0].ast.references;
    if !refs.is_empty() {
        println!("References:");
        for r in refs {
            let opt = if !r.conditions.is_empty() {
                " (conditioned)"
            } else if !r.required {
                " (optional)"
            } else {
                ""
            };
            let tag = if r.is_tag { " [tag]" } else { "" };
            println!("  - {}: {}{tag}{opt}", r.relation.as_str(), r.target);
        }
    }

    ExitCode::SUCCESS
}
fn print_vfs_scan(scan: &intermed_vfs::ResourceScan) {
    println!("InterMed VFS");
    println!("Target: {}", scan.target);
    println!("Resource writers: {}", scan.writes.len());
    println!("Collisions: {}", scan.collisions.len());
    println!("Scan failures: {}", scan.failures.len());
    let mut by_class: BTreeMap<&'static str, usize> = BTreeMap::new();
    for c in &scan.collisions {
        *by_class.entry(c.class.as_str()).or_default() += 1;
    }
    for (class, count) in by_class {
        println!("  {class}: {count}");
    }
}

fn print_vfs_explain(scan: &intermed_vfs::ResourceScan) {
    print_vfs_scan(scan);
    if scan.collisions.is_empty() {
        return;
    }
    println!();
    for c in &scan.collisions {
        println!("{} [{}]", c.path, c.class.as_str());
        println!("  writers: {}", c.writers.join(", "));
        println!("  archives: {}", c.archives.join(", "));
        println!("  reason: {}", c.reason);
    }
    if !scan.failures.is_empty() {
        println!();
        for failure in &scan.failures {
            println!("{} [scan-failure]", failure.archive);
            println!("  reason: {}", failure.reason);
        }
    }
}
