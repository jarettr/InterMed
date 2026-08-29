use crate::*;

pub(crate) fn run_mixin_map(args: MixinMapArgs) -> ExitCode {
    let target = match detect_target_or_exit(&args.target) {
        Ok(target) => target,
        Err(code) => return code,
    };
    match intermed_mixin_intel::scan_target(&target) {
        Ok(scan) => {
            if args.graph_format == GraphExportFormat::Json {
                print_mixin_scan(&scan);
                return ExitCode::SUCCESS;
            }
            let payload = match args.graph_format {
                GraphExportFormat::GraphData => intermed_mixin_intel::graph_to_json(&scan),
                GraphExportFormat::Dot => intermed_mixin_intel::graph_to_dot(&scan),
                GraphExportFormat::Graphml => intermed_mixin_intel::graph_to_graphml(&scan),
                GraphExportFormat::Html => {
                    intermed_mixin_intel::graph_to_html(&scan, "InterMed Mixin Graph")
                }
                GraphExportFormat::Json => None,
            };
            let Some(text) = payload else {
                eprintln!("error: failed to serialize mixin graph");
                return ExitCode::from(2);
            };
            if let Some(path) = args.graph_out {
                if let Err(e) = write_atomic(&path, text.as_bytes()) {
                    eprintln!("error: write {}: {e}", path.display());
                    return ExitCode::from(2);
                }
                println!("wrote {} ({} bytes)", path.display(), text.len());
            } else {
                print!("{text}");
            }
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(2)
        }
    }
}

fn mixin_cluster_actions(r: &intermed_mixin_intel::MixinRiskAssessment) -> Vec<String> {
    let mut actions = Vec::new();
    if r.apply_failure > 0 {
        actions.push(
            "verify the target class/method exists in this version; run with --minecraft-jar for full apply verification".to_string(),
        );
    }
    if r.hot_path {
        actions
            .push("test with each conflicting mod disabled and compare Spark profiles".to_string());
    }
    if r.unresolved_points > 0 {
        actions.push("provide mappings/refmap so the injection points resolve".to_string());
    }
    if r.certainty < 60 {
        actions.push("low certainty — confirm the mixins actually apply before acting".to_string());
    }
    if actions.is_empty() {
        actions.push("inspect the exact injection sites for ordering conflicts".to_string());
    }
    actions
}

fn print_mixin_scan(scan: &intermed_mixin_intel::MixinScan) {
    println!("InterMed Mixin Map");
    println!("Target: {}", scan.target);
    println!("Configs: {}", scan.configs.len());
    println!("Mixin classes: {}", scan.classes.len());
    println!("Overlaps: {}", scan.overlaps.len());
    println!("Interactions: {}", scan.interactions.len());
    println!("Risk assessments: {}", scan.risk_assessments.len());
    println!("High-risk overwrites: {}", scan.high_risk_overwrites.len());
    println!("Apply failures: {}", scan.apply_failures.len());
    println!("Scan failures: {}", scan.failures.len());

    // Cluster-first: lead with the highest-risk targets (clusters), each with its
    // axes, top reasons, and suggested triage actions. Detailed per-injection
    // effect summaries live below, in the Overlaps expansion.
    if !scan.risk_assessments.is_empty() {
        let mut clusters: Vec<_> = scan.risk_assessments.iter().collect();
        clusters.sort_by(|a, b| {
            b.score
                .cmp(&a.score)
                .then_with(|| a.subject.cmp(&b.subject))
        });
        println!();
        println!("Top Mixin Clusters:");
        for (i, r) in clusters.iter().enumerate() {
            println!(
                "{}. {} [{}]",
                i + 1,
                r.subject,
                if r.hot_path { "hot" } else { "normal" }
            );
            println!("   Mods: {}", r.mods.join(", "));
            println!(
                "   Risk: {} (certainty {}, apply-failure {}, semantic {}, blast {}, fragility {})",
                r.score,
                r.certainty,
                r.apply_failure,
                r.semantic_conflict,
                r.blast_radius,
                r.fragility
            );
            if r.unresolved_points > 0 {
                println!("   Unresolved points: {}", r.unresolved_points);
            }
            if !r.reasons.is_empty() {
                println!("   Main reasons:");
                for reason in r.reasons.iter().take(5) {
                    println!("   - {reason}");
                }
            }
            println!("   Actions:");
            for action in mixin_cluster_actions(r) {
                println!("   - {action}");
            }
        }
    }

    if !scan.overlaps.is_empty() {
        println!();
        println!("Overlaps:");
        for overlap in &scan.overlaps {
            println!(
                "{} [{}]",
                overlap.target,
                if overlap.hot_path { "hot" } else { "normal" }
            );
            println!("  mods: {}", overlap.mods.join(", "));
            println!("  classes: {}", overlap.classes.join(", "));
            println!(
                "  operations: {}",
                overlap
                    .operations
                    .iter()
                    .map(intermed_mixin_intel::MixinOperation::as_str)
                    .collect::<Vec<_>>()
                    .join(", ")
            );
            println!("  method_conflict: {}", overlap.method_conflict);
            if !overlap.effect_summaries.is_empty() {
                for summary in &overlap.effect_summaries {
                    println!("  effect: {summary}");
                }
            }
        }
    }

    if !scan.mod_complexity.is_empty() {
        println!();
        println!("Mixin Complexity Score (per mod):");
        let mut mods: Vec<_> = scan.mod_complexity.iter().collect();
        mods.sort_by(|a, b| b.score.cmp(&a.score).then_with(|| a.mod_id.cmp(&b.mod_id)));
        for mc in mods {
            println!(
                "{} — {}/100 ({} class(es), {} target(s), {} site(s))",
                mc.mod_id, mc.score, mc.class_count, mc.target_count, mc.total_injection_sites
            );
        }
    }

    if !scan.bloat.is_empty() {
        println!();
        println!("Mixin bloat (low-yield handlers):");
        let mut bloat: Vec<_> = scan.bloat.iter().collect();
        bloat.sort_by(|a, b| b.score.cmp(&a.score).then_with(|| a.mod_id.cmp(&b.mod_id)));
        for bl in bloat {
            println!(
                "{} — {}/100 ({}/{} handler(s) inert, ~{} instr)",
                bl.mod_id, bl.score, bl.inert_handlers, bl.total_handlers, bl.inert_instructions
            );
        }
    }

    if !scan.interactions.is_empty() {
        println!();
        println!("Interactions:");
        for interaction in &scan.interactions {
            println!(
                "{} ↔ {} on {} ({})",
                interaction.mod_a, interaction.mod_b, interaction.target, interaction.detail
            );
        }
    }

    if !scan.conflict_edges.is_empty() {
        println!();
        println!("Conflict edges:");
        for edge in &scan.conflict_edges {
            println!(
                "{} — {} ({}) @ {}",
                edge.edge_type.as_str(),
                edge.source_mixin,
                edge.target_mixin,
                edge.target_class
            );
            if !edge.site.is_empty() {
                println!("  site: {}", edge.site);
            }
        }
    }

    if !scan.recommendations.is_empty() {
        println!();
        println!("Recommendations:");
        for rec in &scan.recommendations {
            println!(
                "{} — {} ({})",
                rec.recommendation.title, rec.target, rec.site_key
            );
            println!("  {}", rec.recommendation.description);
            if let Some(url) = &rec.recommendation.doc_url {
                println!("  Docs: {url}");
            }
            if let Some(example) = &rec.recommendation.example {
                println!("  Example:");
                for line in example.lines() {
                    println!("    {line}");
                }
            }
        }
    }

    if !scan.mixin_effects.is_empty() {
        println!();
        println!("Mixin effects:");
        for effect in &scan.mixin_effects {
            println!("{} — {}#{}", effect.mod_id, effect.target, effect.method);
            if !effect.site_key.is_empty() {
                println!("  site_key: {}", effect.site_key);
            }
            println!("  {}", effect.effect_description);
            if !effect.effect_kinds.is_empty() {
                println!(
                    "  kinds: {}",
                    effect
                        .effect_kinds
                        .iter()
                        .map(|k| k.as_str())
                        .collect::<Vec<_>>()
                        .join(", ")
                );
            }
        }
    }

    if !scan.high_risk_overwrites.is_empty() {
        println!();
        println!("High-risk overwrites:");
        for overwrite in &scan.high_risk_overwrites {
            println!("{} -> {}", overwrite.mod_id, overwrite.target);
            println!("  mixin: {}", overwrite.class_name);
            if !overwrite.site_key.is_empty() {
                println!("  site_key: {}", overwrite.site_key);
            }
            println!("  hot_path: {}", overwrite.hot_path);
            if !overwrite.effect_description.is_empty() {
                println!("  effect: {}", overwrite.effect_description);
            }
        }
    }

    if !scan.failures.is_empty() {
        println!();
        println!("Scan failures:");
        for failure in &scan.failures {
            println!("{}", failure.archive);
            if let Some(path) = &failure.path {
                println!("  path: {path}");
            }
            println!("  reason: {}", failure.reason);
        }
    }
}
