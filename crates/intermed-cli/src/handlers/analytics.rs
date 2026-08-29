use crate::*;

pub(crate) fn run_db(args: DbArgs) -> ExitCode {
    if !duckdb_available() {
        eprintln!("error: `intermed db` requires building with --features duckdb");
        return ExitCode::from(2);
    }
    #[cfg(feature = "duckdb")]
    {
        match args.command {
            // Read-only at the engine level: an ad-hoc query can never DROP /
            // DELETE / mutate the analytics store (the `--help` promise is real).
            DbCommand::Query(query) => match intermed_duckdb::DuckStore::open_readonly(&query.db) {
                Ok(store) => match store.query(&query.sql) {
                    Ok(result) => {
                        if !result.columns.is_empty() {
                            println!("{}", result.columns.join("\t"));
                        }
                        for row in &result.rows {
                            println!("{}", row.join("\t"));
                        }
                        ExitCode::SUCCESS
                    }
                    Err(e) => {
                        eprintln!("error: query failed: {e}");
                        ExitCode::from(2)
                    }
                },
                Err(e) => {
                    eprintln!("error: could not open {}: {e}", query.db.display());
                    ExitCode::from(2)
                }
            },
        }
    }
    #[cfg(not(feature = "duckdb"))]
    {
        let _ = args;
        ExitCode::from(2)
    }
}

#[cfg(feature = "duckdb")]
fn print_history_diff_human(report: &intermed_duckdb::HistoryDiffReport) {
    println!("InterMed History — finding diff");
    if let Some(a) = &report.run_a {
        println!(
            "  A: {}  {}  errors={} warns={}",
            a.run_id, a.target_path, a.error_count, a.warn_count
        );
    }
    if let Some(b) = &report.run_b {
        println!(
            "  B: {}  {}  errors={} warns={}",
            b.run_id, b.target_path, b.error_count, b.warn_count
        );
    }
    let s = &report.summary;
    println!(
        "Summary: +{} added, -{} removed, ~{} severity, ~{} rule",
        s.added, s.removed, s.severity_changed, s.rule_changed
    );
    if report.deltas.is_empty() {
        println!("No finding changes between runs.");
        return;
    }
    println!("change\tcategory\tseverity\trule_id\tfinding_id\ttitle\taffected(a→b)");
    for row in &report.deltas {
        let kind = match row.change {
            intermed_duckdb::RunDeltaKind::Added => "added",
            intermed_duckdb::RunDeltaKind::Removed => "removed",
            intermed_duckdb::RunDeltaKind::SeverityChanged => "severity",
            intermed_duckdb::RunDeltaKind::RuleChanged => "rule",
            intermed_duckdb::RunDeltaKind::Unchanged => "unchanged",
        };
        let sev = match (&row.severity_a, &row.severity_b) {
            (Some(a), Some(b)) if a != b => format!("{a}→{b}"),
            _ => row.severity.clone(),
        };
        println!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}→{}",
            kind,
            row.category,
            sev,
            row.rule_id,
            row.finding_id,
            row.title,
            row.affected_a,
            row.affected_b
        );
    }
}

pub(crate) fn run_history(args: HistoryArgs) -> ExitCode {
    if !duckdb_available() {
        eprintln!("error: `intermed history` requires building with --features duckdb");
        return ExitCode::from(2);
    }
    #[cfg(feature = "duckdb")]
    {
        match args.command {
            HistoryCommand::Diff(diff) => match intermed_duckdb::AnalyticsStore::open(&diff.db) {
                Ok(store) => match store.history_diff_report(&diff.run_a, &diff.run_b) {
                    Ok(report) => {
                        if diff.json {
                            #[derive(serde::Serialize)]
                            struct HistoryDiffJson<'a> {
                                schema: &'static str,
                                #[serde(flatten)]
                                report: &'a intermed_duckdb::HistoryDiffReport,
                            }
                            let payload = HistoryDiffJson {
                                schema: "intermed-history-diff-v1",
                                report: &report,
                            };
                            match serde_json::to_string_pretty(&payload) {
                                Ok(text) => println!("{text}"),
                                Err(e) => {
                                    eprintln!("error: history diff json failed: {e}");
                                    return ExitCode::from(2);
                                }
                            }
                        } else {
                            print_history_diff_human(&report);
                        }
                        ExitCode::SUCCESS
                    }
                    Err(e) => {
                        eprintln!("error: history diff failed: {e}");
                        ExitCode::from(2)
                    }
                },
                Err(e) => {
                    eprintln!("error: could not open {}: {e}", diff.db.display());
                    ExitCode::from(2)
                }
            },
            HistoryCommand::Prune(prune) => {
                match intermed_duckdb::AnalyticsStore::open(&prune.db) {
                    Ok(store) => match store.history_prune(&prune.keep) {
                        Ok(removed) => {
                            println!("pruned {removed} run(s) older than keep={}", prune.keep);
                            ExitCode::SUCCESS
                        }
                        Err(e) => {
                            eprintln!("error: history prune failed: {e}");
                            ExitCode::from(2)
                        }
                    },
                    Err(e) => {
                        eprintln!("error: could not open {}: {e}", prune.db.display());
                        ExitCode::from(2)
                    }
                }
            }
            HistoryCommand::Conflicts(conflicts) => {
                match intermed_duckdb::AnalyticsStore::open(&conflicts.db) {
                    Ok(store) => match store.history_conflicts(&conflicts.since) {
                        Ok(rows) => {
                            println!(
                                "InterMed History — recurring conflicts (since {})",
                                conflicts.since
                            );
                            println!("Database: {}", conflicts.db.display());
                            if rows.is_empty() {
                                println!("No recurring conflicts in window.");
                                return ExitCode::SUCCESS;
                            }
                            println!(
                                "finding_id\trule_id\tseverity\trun_count\ttargets\tfirst_seen\tlast_seen"
                            );
                            for row in rows {
                                println!(
                                    "{}\t{}\t{}\t{}\t{}\t{}\t{}",
                                    row.finding_id,
                                    row.rule_id,
                                    row.severity,
                                    row.run_count,
                                    row.distinct_targets,
                                    row.first_seen,
                                    row.last_seen
                                );
                            }
                            ExitCode::SUCCESS
                        }
                        Err(e) => {
                            eprintln!("error: history query failed: {e}");
                            ExitCode::from(2)
                        }
                    },
                    Err(e) => {
                        eprintln!("error: could not open {}: {e}", conflicts.db.display());
                        ExitCode::from(2)
                    }
                }
            }
            HistoryCommand::Patterns(patterns) => {
                match intermed_duckdb::AnalyticsStore::open(&patterns.db) {
                    Ok(store) => match store.risk_patterns(patterns.limit) {
                        Ok(rows) => {
                            println!("InterMed History — risk patterns (rule × category)");
                            println!("Database: {}", patterns.db.display());
                            if rows.is_empty() {
                                println!("No persisted findings yet.");
                                return ExitCode::SUCCESS;
                            }
                            println!(
                                "rule_id\tcategory\tseverity_rank\toccurrences\tdistinct_findings\trun_count\tfirst_seen\tlast_seen"
                            );
                            for row in rows {
                                println!(
                                    "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                                    row.rule_id,
                                    row.category,
                                    row.severity_rank,
                                    row.occurrences,
                                    row.distinct_findings,
                                    row.run_count,
                                    row.first_seen,
                                    row.last_seen
                                );
                            }
                            ExitCode::SUCCESS
                        }
                        Err(e) => {
                            eprintln!("error: risk-patterns query failed: {e}");
                            ExitCode::from(2)
                        }
                    },
                    Err(e) => {
                        eprintln!("error: could not open {}: {e}", patterns.db.display());
                        ExitCode::from(2)
                    }
                }
            }
        }
    }
    #[cfg(not(feature = "duckdb"))]
    {
        let _ = args;
        ExitCode::from(2)
    }
}

pub(crate) fn run_trends(args: TrendsArgs) -> ExitCode {
    if !duckdb_available() {
        eprintln!("error: `intermed trends` requires building with --features duckdb");
        return ExitCode::from(2);
    }
    #[cfg(feature = "duckdb")]
    {
        match args.command {
            TrendsCommand::MixinRisk(trends) => {
                match intermed_duckdb::AnalyticsStore::open(&trends.db) {
                    Ok(store) => match store.trends_mixin_risk() {
                        Ok(rows) => {
                            println!("InterMed Trends — mixin-risk");
                            println!("Database: {}", trends.db.display());
                            println!("generated_at\ttarget_path\tmixin_findings");
                            for row in rows {
                                println!(
                                    "{}\t{}\t{}",
                                    row.generated_at, row.target_path, row.mixin_findings
                                );
                            }
                            ExitCode::SUCCESS
                        }
                        Err(e) => {
                            eprintln!("error: trends query failed: {e}");
                            ExitCode::from(2)
                        }
                    },
                    Err(e) => {
                        eprintln!("error: could not open {}: {e}", trends.db.display());
                        ExitCode::from(2)
                    }
                }
            }
            TrendsCommand::MixinOverlaps(trends) => {
                match intermed_duckdb::AnalyticsStore::open(&trends.db) {
                    Ok(store) => match store.top_mixin_overlaps(trends.limit) {
                        Ok(rows) => {
                            println!("InterMed Trends — top mixin overlaps");
                            println!("Database: {}", trends.db.display());
                            println!("mods\ttarget\toccurrences\trun_count");
                            for row in rows {
                                println!(
                                    "{}\t{}\t{}\t{}",
                                    row.mods, row.target, row.occurrences, row.run_count
                                );
                            }
                            ExitCode::SUCCESS
                        }
                        Err(e) => {
                            eprintln!("error: overlap query failed: {e}");
                            ExitCode::from(2)
                        }
                    },
                    Err(e) => {
                        eprintln!("error: could not open {}: {e}", trends.db.display());
                        ExitCode::from(2)
                    }
                }
            }
        }
    }
    #[cfg(not(feature = "duckdb"))]
    {
        let _ = args;
        ExitCode::from(2)
    }
}
