use super::*;

pub fn complete_evidence_graph(store: &FactStore, graph: &mut EvidenceGraph, findings: &[Finding]) {
    let mut linked = graph
        .links
        .iter()
        .map(|link| link.source_fact)
        .collect::<BTreeSet<_>>();
    for fact_id in findings
        .iter()
        .flat_map(|finding| finding.evidence.iter().map(|edge| edge.fact))
    {
        // Several findings commonly cite the same environment or coverage
        // fact. Record one canonical self-link, not one copy per finding.
        if !linked.insert(fact_id) {
            continue;
        }
        let Some(fact) = store.get(fact_id) else {
            continue;
        };
        // Environment facts are cited directly by the finding and retained as
        // coverage evidence, but `EntityRef` deliberately has no environment
        // variant in report-v2. Do not fabricate a mod identity merely to put
        // them in the graph. A future schema can model environments explicitly.
        if matches!(
            fact.kind.as_str(),
            kind::ENVIRONMENT | kind::ANALYSIS_ENVIRONMENT | kind::JAVA_RUNTIME
        ) {
            continue;
        }
        let entity = if matches!(
            fact.kind.as_str(),
            kind::RUNTIME_EVENT | kind::CRASH_ANCHOR | kind::STACK_FRAME | kind::THROWABLE_NODE
        ) {
            EntityRef::RuntimeEvent(RuntimeOccurrenceId::new(&fact.subject))
        } else if let Some(path) = fact.attr("path").or_else(|| {
            fact.kind
                .contains("resource")
                .then_some(fact.subject.as_str())
        }) {
            EntityRef::Resource(ResourceKey::new(path))
        } else {
            unresolved_mod_entity(&fact.subject)
        };
        graph.entities.push(entity.clone());
        graph.links.push(link(
            entity.clone(),
            EvidenceRelation::Corroborates,
            entity,
            if fact.extractor == "log-analyzer" {
                EvidenceOrigin::ObservedRuntime
            } else {
                EvidenceOrigin::StaticExact
            },
            EvidenceStrength::Exact,
            fact.id,
        ));
    }
}
