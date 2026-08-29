use super::*;

/// Synthesize terminal runtime occurrences into causal incidents. Strict
/// fingerprints preserve exact identity while fuzzy fingerprints group rebuilds
/// without merging their physical evidence.
#[must_use]
pub fn synthesize_incidents(store: &FactStore, graph: &EvidenceGraph) -> Vec<Incident> {
    let mut groups = BTreeMap::<String, Vec<&Fact>>::new();
    for anchor in store.by_kind(kind::CRASH_ANCHOR) {
        let key = anchor
            .attr("fuzzy_fingerprint")
            .or_else(|| anchor.attr("semantic_fingerprint"))
            .unwrap_or(&anchor.subject)
            .to_string();
        groups.entry(key).or_default().push(anchor);
    }
    let background_events = store
        .by_kind(kind::RUNTIME_EVENT)
        .filter(|event| {
            !matches!(
                event.attr("terminality"),
                Some(
                    "process-fatal"
                        | "loader-abort"
                        | "crash-report-root"
                        | "watchdog-termination"
                        | "server-stopped"
                )
            )
        })
        .map(|event| RuntimeOccurrenceId::new(&event.subject))
        .collect::<Vec<_>>();
    let mut incidents = Vec::new();
    for (fuzzy, anchors) in groups {
        let first = anchors[0];
        let strict = first.attr("semantic_fingerprint").unwrap_or("").to_string();
        let occurrences = anchors
            .iter()
            .map(|anchor| RuntimeOccurrenceId::new(&anchor.subject))
            .collect::<Vec<_>>();
        let primary_fact = store
            .by_kind(kind::THROWABLE_NODE)
            .filter(|node| anchors.iter().any(|anchor| anchor.subject == node.subject))
            .filter(|node| node.attr_bool("deepest") == Some(true))
            .min_by_key(|node| node.id);
        let primary_cause = primary_fact.map(|node| CausalNode {
            throwable_type: node.attr("type").unwrap_or("unknown").to_string(),
            message: node
                .attr("message")
                .filter(|message| !message.is_empty())
                .map(str::to_string),
            entity: EntityRef::Throwable(ThrowableId::new(format!(
                "{}:{}",
                node.subject,
                node.attr_int("index").unwrap_or_default()
            ))),
        });
        let frames = store
            .by_kind(kind::STACK_FRAME)
            .filter(|frame| frame.subject == first.subject)
            .collect::<Vec<_>>();
        let owned = frames
            .iter()
            .filter(|frame| frame.attr("mod_id").is_some_and(|id| !id.is_empty()))
            .collect::<Vec<_>>();
        // Java stack traces list the currently executing callee before its
        // callers. Therefore the deepest listed owned frame is the caller and
        // the first owned frame is the callee it reached.
        let caller_transition = owned
            .last()
            .and_then(|caller| {
                let callee = owned.first()?;
                let caller_entity = method_entity_from_frame(caller);
                let callee_entity = method_entity_from_frame(callee);
                Some(CausalTransition {
                    caller: (caller.id != callee.id).then_some(caller_entity),
                    callee: callee_entity,
                    rationale: "ordered runtime stack ownership transition".to_string(),
                    ambiguous: false,
                })
            })
            .or_else(|| {
                frames
                    .iter()
                    .find(|frame| frame.attr("ownership") == Some("ambiguous"))
                    .map(|frame| CausalTransition {
                        caller: None,
                        callee: method_entity_from_frame(frame),
                        rationale: format!(
                            "runtime frame ownership is shared by {}",
                            frame
                                .attr("owner_candidates")
                                .unwrap_or("multiple artifacts")
                        ),
                        ambiguous: true,
                    })
            });
        let mut contributor_facts = BTreeMap::<String, (String, Vec<FactId>)>::new();
        let runtime_events = store
            .by_kind(kind::RUNTIME_EVENT)
            .filter(|event| anchors.iter().any(|anchor| anchor.subject == event.subject))
            .collect::<Vec<_>>();
        for event in &runtime_events {
            if let Some(mod_id) = event.attr("mod_id").filter(|mod_id| !mod_id.is_empty()) {
                let entry = contributor_facts
                    .entry(mod_id.to_string())
                    .or_insert_with(|| ("loader-rejected-mod".to_string(), Vec::new()));
                entry.1.push(event.id);
            }
        }
        for frame in &owned {
            if let Some(mod_id) = frame.attr("mod_id") {
                contributor_facts
                    .entry(mod_id.to_string())
                    .or_insert_with(|| ("runtime-stack-owner".to_string(), Vec::new()))
                    .1
                    .push(frame.id);
            }
        }
        let contributors = contributor_facts
            .into_iter()
            .map(|(mod_id, (role, evidence))| Contributor {
                entity: unresolved_mod_entity(&mod_id),
                role,
                evidence,
            })
            .collect::<Vec<_>>();
        let mut affected_entities = contributors
            .iter()
            .map(|c| c.entity.clone())
            .collect::<Vec<_>>();
        if let Some(cause) = &primary_cause {
            affected_entities.push(cause.entity.clone());
        }
        affected_entities.sort();
        affected_entities.dedup();
        let evidence_ids = anchors
            .iter()
            .map(|anchor| anchor.id)
            .chain(primary_fact.into_iter().map(|fact| fact.id))
            .chain(frames.iter().map(|frame| frame.id))
            .chain(runtime_events.iter().map(|event| event.id))
            .collect::<BTreeSet<_>>();
        let evidence_path = graph
            .links
            .iter()
            .filter(|link| evidence_ids.contains(&link.source_fact))
            .cloned()
            .collect();
        let mut assessment = FindingAssessment {
            disposition: AssessmentDisposition::Asserted,
            impact: Impact::RuntimeFailure,
            certainty: CertaintyTier::Confirmed,
            proof_kind: ProofKind::Observation,
            ..FindingAssessment::default()
        };
        assessment
            .provenance
            .insert(EvidenceOrigin::ObservedRuntime);
        incidents.push(Incident {
            semantic_id: format!("incident:fuzzy:{fuzzy}"),
            strict_fingerprint: strict,
            fuzzy_fingerprint: fuzzy,
            occurrences,
            primary_cause,
            caller_transition,
            contributors,
            background_events: background_events.clone(),
            affected_entities,
            evidence_path,
            recommendations: Vec::new(),
            assessment,
        });
    }
    incidents.sort_by(|a, b| a.semantic_id.cmp(&b.semantic_id));
    incidents
}

fn method_entity_from_frame(frame: &Fact) -> EntityRef {
    EntityRef::Method(MethodSymbol {
        owner: ClassSymbol::new(
            frame.attr("class").unwrap_or("unknown"),
            MappingNamespace::Unknown,
            MappingGraphId::new(UNMAPPED_GRAPH),
        ),
        name: frame.attr("method").unwrap_or("unknown").to_string(),
        descriptor: MethodDescriptor::unknown(),
    })
}
