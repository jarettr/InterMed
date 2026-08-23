use intermed_doctor_core::facts::{FactStore, kind};
use intermed_doctor_core::{Rule, RuleCtx, Target, TargetKind};
use intermed_security_audit::rule;

fn dummy_target() -> Target {
    Target {
        path: ".".into(),
        kind: TargetKind::ModsDir,
        mods_dir: None,
        game_root: None,
        layout: None,
        instance_type: None,
        spark_report: None,
    }
}

#[test]
fn grouped_finding_emits_verbose_capability_note_for_process_spawn() {
    let mut store = FactStore::new();
    store
        .fact("security-scanner", kind::USES_PROCESS_SPAWN)
        .subject("risky")
        .attr("archive", "risky.jar")
        .emit();

    let target = dummy_target();
    let ctx = RuleCtx::for_test(&store, &target);
    let findings = rule().evaluate(&ctx).unwrap();

    assert_eq!(findings.len(), 1);
    assert_eq!(findings[0].id, "security-api-risk:risky");
    assert_eq!(
        findings[0].severity,
        intermed_doctor_core::evidence::Severity::Note
    );
    assert_eq!(
        findings[0].visibility,
        intermed_doctor_core::evidence::FindingVisibility::Verbose
    );
    assert!(findings[0].title.contains("1 security API signal"));
    assert!(findings[0].confidence > 0.5);
}

#[test]
fn single_note_signal_does_not_emit_finding() {
    let mut store = FactStore::new();
    store
        .fact("security-scanner", kind::USES_SOCKET)
        .subject("netty")
        .attr("archive", "netty.jar")
        .emit();

    let target = dummy_target();
    let ctx = RuleCtx::for_test(&store, &target);
    let findings = rule().evaluate(&ctx).unwrap();

    assert!(findings.is_empty());
}

#[test]
fn two_note_signals_emit_grouped_note_finding() {
    let mut store = FactStore::new();
    store
        .fact("security-scanner", kind::USES_SOCKET)
        .subject("netty")
        .attr("archive", "netty.jar")
        .emit();
    store
        .fact("security-scanner", kind::USES_NATIVE_LIBRARY)
        .subject("netty")
        .attr("archive", "netty.jar")
        .emit();

    let target = dummy_target();
    let ctx = RuleCtx::for_test(&store, &target);
    let findings = rule().evaluate(&ctx).unwrap();

    assert_eq!(findings.len(), 1);
    assert_eq!(findings[0].id, "security-api-risk:netty");
    assert_eq!(
        findings[0].severity,
        intermed_doctor_core::evidence::Severity::Note
    );
}

#[test]
fn unsafe_is_a_capability_note_not_a_standalone_security_warning() {
    let mut store = FactStore::new();
    store
        .fact("security-scanner", kind::USES_UNSAFE)
        .subject("performance-mod")
        .attr("archive", "performance-mod.jar")
        .emit();

    let target = dummy_target();
    let ctx = RuleCtx::for_test(&store, &target);
    assert!(rule().evaluate(&ctx).unwrap().is_empty());

    store
        .fact("security-scanner", kind::USES_REFLECTIVE_INVOCATION)
        .subject("performance-mod")
        .attr("archive", "performance-mod.jar")
        .emit();
    let ctx = RuleCtx::for_test(&store, &target);
    let findings = rule().evaluate(&ctx).unwrap();
    assert_eq!(findings.len(), 1);
    assert_eq!(
        findings[0].severity,
        intermed_doctor_core::evidence::Severity::Note
    );
}

#[test]
fn corroborated_process_spawn_remains_a_note_and_is_marked_inferred() {
    let mut store = FactStore::new();
    // Structural reflection machinery.
    store
        .fact("security-scanner", kind::USES_REFLECTION_SET_ACCESSIBLE)
        .subject("sneaky")
        .attr("archive", "sneaky.jar")
        .attr("provenance", "structural")
        .emit();
    // Process spawn established only by string corroboration (low confidence).
    store
        .fact("security-scanner", kind::USES_PROCESS_SPAWN)
        .subject("sneaky")
        .attr("archive", "sneaky.jar")
        .attr("provenance", "reflection-corroborated")
        .confidence(0.4)
        .emit();

    let target = dummy_target();
    let ctx = RuleCtx::for_test(&store, &target);
    let findings = rule().evaluate(&ctx).unwrap();

    assert_eq!(findings.len(), 1);
    let finding = &findings[0];
    // Even a corroborated API capability is not a harmful-behaviour verdict.
    assert_eq!(
        finding.severity,
        intermed_doctor_core::evidence::Severity::Note
    );
    // …but it is transparently labelled as inferred, not asserted as fact.
    assert!(finding.explanation.contains("low confidence"));
    assert!(
        finding
            .machine_tags
            .iter()
            .any(|t| t == "reflection-corroborated")
    );
}
