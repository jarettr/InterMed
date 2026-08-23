//! Campaign-level metrics and mismatch triage report.

use std::collections::BTreeMap;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::campaign::{CampaignCaseStatus, CampaignState};
use crate::eval::RuleAccuracyReport;
use crate::observation::ExecutionObservation;
use crate::triage::{MismatchCluster, cluster_accuracy};
use crate::{LabError, read_json, write_atomic, write_json_atomic};

pub const CAMPAIGN_REPORT_SCHEMA: &str = "intermed-lab-campaign-report-v1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CampaignReport {
    pub schema: String,
    pub campaign_id: String,
    pub total_cases: usize,
    /// Cases for which a valid static Doctor report was produced.
    #[serde(default)]
    pub analyzed: usize,
    /// Cases with a completed runtime observation (excludes static-only cases).
    pub completed: usize,
    pub static_only: usize,
    pub infrastructure_failures: usize,
    pub harness_failures: usize,
    pub runtime_statuses: BTreeMap<String, usize>,
    pub failure_categories: BTreeMap<String, usize>,
    pub predictions: usize,
    pub true_positive: usize,
    pub false_positive: usize,
    pub false_negative: usize,
    pub abstained: usize,
    pub inconclusive_coverage: usize,
    pub artifact_bytes: u64,
    pub static_totals: StaticTotals,
    pub static_cases: Vec<StaticCaseSummary>,
    pub static_finding_clusters: Vec<StaticFindingCluster>,
    pub triage_clusters: Vec<MismatchCluster>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StaticFindingCluster {
    pub family: String,
    pub severity: String,
    pub disposition: String,
    pub occurrences: usize,
    pub cases: Vec<String>,
    pub representative_semantic_ids: Vec<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct StaticTotals {
    pub reports: usize,
    pub fatal: usize,
    pub error: usize,
    pub warn: usize,
    pub findings: usize,
    pub confirmed_problems: usize,
    pub needs_review: usize,
    pub incomplete_analysis: usize,
    pub operational_errors: usize,
    pub facts_generated: usize,
    pub facts_retained: usize,
    pub facts_dropped: usize,
    pub analyzed_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub maximum_peak_rss_bytes: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StaticCaseSummary {
    pub case_id: String,
    pub tool_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub loader: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minecraft_version: Option<String>,
    pub fatal: usize,
    pub error: usize,
    pub warn: usize,
    pub findings: usize,
    pub confirmed_problems: usize,
    pub needs_review: usize,
    pub incomplete_analysis: usize,
    pub operational_errors: usize,
    pub collector_statuses: BTreeMap<String, usize>,
    pub facts_generated: usize,
    pub facts_retained: usize,
    pub facts_dropped: usize,
    pub analyzed_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub peak_rss_bytes: Option<u64>,
    pub analyzer_fingerprint: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effective_config_sha256: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_manifest_sha256: Option<String>,
    pub report_bytes: u64,
}

pub fn build_campaign_report(state: &CampaignState) -> Result<CampaignReport, LabError> {
    let mut runtime_statuses = BTreeMap::new();
    let mut failure_categories = BTreeMap::new();
    let mut accuracy_reports = Vec::new();
    let mut completed = 0usize;
    let mut infrastructure_failures = 0usize;
    let mut harness_failures = 0usize;
    let mut static_only = 0usize;
    let mut artifact_bytes = 0u64;
    let mut static_totals = StaticTotals::default();
    let mut static_cases = Vec::new();
    let mut static_clusters = BTreeMap::<
        (String, String, String),
        (
            usize,
            std::collections::BTreeSet<String>,
            std::collections::BTreeSet<String>,
        ),
    >::new();
    for case in &state.cases {
        match case.status {
            CampaignCaseStatus::Complete => completed += 1,
            CampaignCaseStatus::StaticComplete => {
                static_only += 1;
            }
            CampaignCaseStatus::InfrastructureFailure => infrastructure_failures += 1,
            CampaignCaseStatus::HarnessFailure => harness_failures += 1,
            CampaignCaseStatus::Skipped => {
                // Compatibility with campaign states written before the case
                // status distinguished a skipped runtime from a skipped case.
                static_only += 1;
            }
            _ => {}
        }
        if let Some(path) = &case.observation {
            artifact_bytes = artifact_bytes.saturating_add(
                std::fs::metadata(path)
                    .map(|metadata| metadata.len())
                    .unwrap_or(0),
            );
            let observation: ExecutionObservation = read_json(path)?;
            *runtime_statuses
                .entry(observation.status.as_str().to_string())
                .or_default() += 1;
            for category in &observation.failure_categories {
                *failure_categories
                    .entry(category.as_str().to_string())
                    .or_default() += 1;
            }
        }
        if let Some(path) = &case.accuracy_report {
            artifact_bytes = artifact_bytes.saturating_add(
                std::fs::metadata(path)
                    .map(|metadata| metadata.len())
                    .unwrap_or(0),
            );
            accuracy_reports.push(read_json::<RuleAccuracyReport>(path)?);
        }
        if let Some(path) = &case.doctor_report {
            let report_bytes = std::fs::metadata(path)
                .map(|metadata| metadata.len())
                .unwrap_or(0);
            artifact_bytes = artifact_bytes.saturating_add(report_bytes);
            let report: intermed_doctor_core::DoctorReport = read_json(path)?;
            let profile = report.profile.as_ref();
            let facts_generated = profile
                .map(|profile| profile.facts_generated_by_kind.values().sum())
                .unwrap_or_else(|| report.fact_stats.values().sum());
            let facts_retained = profile
                .map(|profile| profile.facts_retained_by_kind.values().sum())
                .unwrap_or_else(|| report.fact_stats.values().sum());
            let facts_dropped = profile.map_or(0, |profile| profile.facts_dropped);
            let analyzed_ms = profile.map_or(0, |profile| profile.total_ms);
            let peak_rss_bytes = profile.and_then(|profile| profile.peak_rss_bytes);
            let mut collector_statuses = BTreeMap::new();
            for collector in &report.collectors {
                *collector_statuses
                    .entry(collector.status.clone())
                    .or_default() += 1;
            }
            let analyzer_fingerprint = crate::campaign::report_analyzer_fingerprint(&report)?;
            let fingerprint = &report.analysis_configuration.fingerprint;
            for finding in report.findings.iter().filter(|finding| {
                finding.visibility.shown_by_default()
                    && finding.severity >= intermed_doctor_core::evidence::Severity::Warn
            }) {
                let disposition = match finding.assessment.disposition {
                    intermed_doctor_core::evidence::AssessmentDisposition::Asserted => "asserted",
                    intermed_doctor_core::evidence::AssessmentDisposition::Downgraded => {
                        "downgraded"
                    }
                    intermed_doctor_core::evidence::AssessmentDisposition::Abstained => "abstained",
                };
                let key = (
                    if finding.family.is_empty() {
                        finding.rule_id.clone()
                    } else {
                        finding.family.clone()
                    },
                    finding.severity.as_str().to_string(),
                    disposition.to_string(),
                );
                let cluster = static_clusters.entry(key).or_default();
                cluster.0 += 1;
                cluster.1.insert(case.case_id.clone());
                cluster.2.insert(if finding.semantic_id.is_empty() {
                    finding.id.clone()
                } else {
                    finding.semantic_id.clone()
                });
            }
            let summary = StaticCaseSummary {
                case_id: case.case_id.clone(),
                tool_version: report.tool_version,
                loader: report
                    .environment
                    .loader
                    .map(|loader| loader.as_str().to_string()),
                minecraft_version: report.environment.minecraft_version,
                fatal: report.summary.fatal,
                error: report.summary.error,
                warn: report.summary.warn,
                findings: report.summary.total,
                confirmed_problems: report.summary.confirmed_problems,
                needs_review: report.summary.needs_review,
                incomplete_analysis: report.summary.incomplete_analysis,
                operational_errors: report.operational_errors.len(),
                collector_statuses,
                facts_generated,
                facts_retained,
                facts_dropped,
                analyzed_ms,
                peak_rss_bytes,
                analyzer_fingerprint,
                effective_config_sha256: fingerprint.effective_config_sha256.clone(),
                target_manifest_sha256: fingerprint.target_manifest_sha256.clone(),
                report_bytes,
            };
            static_totals.reports += 1;
            static_totals.fatal += summary.fatal;
            static_totals.error += summary.error;
            static_totals.warn += summary.warn;
            static_totals.findings += summary.findings;
            static_totals.confirmed_problems += summary.confirmed_problems;
            static_totals.needs_review += summary.needs_review;
            static_totals.incomplete_analysis += summary.incomplete_analysis;
            static_totals.operational_errors += summary.operational_errors;
            static_totals.facts_generated += summary.facts_generated;
            static_totals.facts_retained += summary.facts_retained;
            static_totals.facts_dropped += summary.facts_dropped;
            static_totals.analyzed_ms = static_totals
                .analyzed_ms
                .saturating_add(summary.analyzed_ms);
            static_totals.maximum_peak_rss_bytes =
                match (static_totals.maximum_peak_rss_bytes, summary.peak_rss_bytes) {
                    (Some(left), Some(right)) => Some(left.max(right)),
                    (None, value) | (value, None) => value,
                };
            static_cases.push(summary);
        }
    }
    let predictions = accuracy_reports
        .iter()
        .map(|report| report.finding_level.predictions)
        .sum();
    let true_positive = accuracy_reports
        .iter()
        .map(|report| report.finding_level.true_positive)
        .sum();
    let false_positive = accuracy_reports
        .iter()
        .map(|report| report.finding_level.false_positive)
        .sum();
    let false_negative = accuracy_reports
        .iter()
        .map(|report| report.finding_level.false_negative)
        .sum();
    let abstained = accuracy_reports
        .iter()
        .map(|report| report.finding_level.abstained)
        .sum();
    let inconclusive_coverage = accuracy_reports
        .iter()
        .map(|report| report.finding_level.inconclusive_coverage)
        .sum();
    let mut static_finding_clusters = static_clusters
        .into_iter()
        .map(
            |((family, severity, disposition), (occurrences, cases, semantic_ids))| {
                StaticFindingCluster {
                    family,
                    severity,
                    disposition,
                    occurrences,
                    cases: cases.into_iter().collect(),
                    representative_semantic_ids: semantic_ids.into_iter().take(10).collect(),
                }
            },
        )
        .collect::<Vec<_>>();
    static_finding_clusters.sort_by(|left, right| {
        right
            .occurrences
            .cmp(&left.occurrences)
            .then(left.family.cmp(&right.family))
    });
    let analyzed = static_totals.reports;
    Ok(CampaignReport {
        schema: CAMPAIGN_REPORT_SCHEMA.to_string(),
        campaign_id: state.campaign_id.clone(),
        total_cases: state.cases.len(),
        analyzed,
        completed,
        static_only,
        infrastructure_failures,
        harness_failures,
        runtime_statuses,
        failure_categories,
        predictions,
        true_positive,
        false_positive,
        false_negative,
        abstained,
        inconclusive_coverage,
        artifact_bytes,
        static_totals,
        static_cases,
        static_finding_clusters,
        triage_clusters: cluster_accuracy(&accuracy_reports).clusters,
    })
}

pub fn write_campaign_report(
    state: &CampaignState,
    out_dir: &Path,
) -> Result<CampaignReport, LabError> {
    let report = build_campaign_report(state)?;
    write_json_atomic(&out_dir.join("campaign-report.json"), &report)?;
    write_atomic(
        &out_dir.join("campaign-report.html"),
        render_campaign_html(&report).as_bytes(),
    )
    .map_err(|error| LabError::new(format!("write campaign HTML: {error}")))?;
    Ok(report)
}

fn render_campaign_html(report: &CampaignReport) -> String {
    let mut cases = String::new();
    for case in &report.static_cases {
        cases.push_str(&format!(
            "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
            escape(&case.case_id),
            escape(case.loader.as_deref().unwrap_or("unknown")),
            escape(case.minecraft_version.as_deref().unwrap_or("unknown")),
            case.confirmed_problems,
            case.needs_review,
            case.incomplete_analysis,
            case.findings,
            case.facts_generated,
            case.peak_rss_bytes
                .map(human_bytes)
                .unwrap_or_else(|| "unavailable".to_string())
        ));
    }
    let mut clusters = String::new();
    for cluster in &report.triage_clusters {
        clusters.push_str(&format!(
            "<tr><td>{}</td><td>{}</td><td>{:?}</td><td>{}</td></tr>",
            escape(&cluster.rule_id),
            escape(&cluster.category),
            cluster.outcome,
            cluster.occurrences
        ));
    }
    let mut static_clusters = String::new();
    for cluster in &report.static_finding_clusters {
        static_clusters.push_str(&format!(
            "<tr><td>{}</td><td>{}</td><td>{}</td><td>{}</td><td>{}</td></tr>",
            escape(&cluster.family),
            escape(&cluster.severity),
            escape(&cluster.disposition),
            cluster.occurrences,
            escape(&cluster.cases.join(", "))
        ));
    }
    format!(
        "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>InterMed Lab campaign</title><style>body{{font-family:system-ui;margin:2rem}}table{{border-collapse:collapse}}td,th{{border:1px solid #ccc;padding:.4rem}}</style><h1>Campaign {}</h1><p>Cases: {} · statically analyzed: {} · runtime complete: {} · static-only: {} · infrastructure failures: {} · harness failures: {}</p><h2>Static analysis</h2><p>Reports: {} · findings: {} · confirmed problems: {} · needs review: {} · incomplete: {} · operational errors: {}</p><p>Facts: {} generated · {} retained · {} compacted</p><table><tr><th>Case</th><th>Loader</th><th>Minecraft</th><th>Confirmed</th><th>Review</th><th>Incomplete</th><th>Findings</th><th>Facts generated</th><th>Peak RSS</th></tr>{}</table><h3>Static finding clusters</h3><table><tr><th>Family</th><th>Severity</th><th>Disposition</th><th>Occurrences</th><th>Cases</th></tr>{}</table><h2>Runtime evaluation</h2><p>Predictions: {} · TP: {} · FP: {} · FN: {} · abstained: {} · coverage-inconclusive: {}</p><h2>Runtime mismatch clusters</h2><table><tr><th>Rule</th><th>Category</th><th>Outcome</th><th>Occurrences</th></tr>{}</table></html>",
        escape(&report.campaign_id),
        report.total_cases,
        report.analyzed,
        report.completed,
        report.static_only,
        report.infrastructure_failures,
        report.harness_failures,
        report.static_totals.reports,
        report.static_totals.findings,
        report.static_totals.confirmed_problems,
        report.static_totals.needs_review,
        report.static_totals.incomplete_analysis,
        report.static_totals.operational_errors,
        report.static_totals.facts_generated,
        report.static_totals.facts_retained,
        report.static_totals.facts_dropped,
        cases,
        static_clusters,
        report.predictions,
        report.true_positive,
        report.false_positive,
        report.false_negative,
        report.abstained,
        report.inconclusive_coverage,
        clusters
    )
}

fn human_bytes(bytes: u64) -> String {
    const GIB: f64 = 1024.0 * 1024.0 * 1024.0;
    format!("{:.2} GiB", bytes as f64 / GIB)
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}
