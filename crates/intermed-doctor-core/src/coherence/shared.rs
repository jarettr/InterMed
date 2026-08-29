use super::*;

pub(super) fn hash_tagged(digest: &mut Sha256, tag: &str, value: &str) {
    digest.update((tag.len() as u32).to_be_bytes());
    digest.update(tag.as_bytes());
    digest.update((value.len() as u64).to_be_bytes());
    digest.update(value.as_bytes());
}

pub(super) fn class_under_package(class: &str, package: &str) -> bool {
    let class = class.replace('/', ".");
    class == package
        || class
            .strip_prefix(package)
            .is_some_and(|suffix| suffix.starts_with('.'))
}

pub(super) fn unresolved_mod_entity(mod_id: &str) -> EntityRef {
    EntityRef::Mod(ModInstanceId {
        artifact: ArtifactId::unresolved(&format!("runtime-owner:{mod_id}")),
        declared_id: mod_id.to_string(),
        descriptor_kind: DescriptorKind::Unknown,
        ordinal: 0,
    })
}
pub(super) fn artifact_for(
    locator: &str,
    by_locator: &mut BTreeMap<String, ArtifactId>,
    graph: &mut EvidenceGraph,
) -> ArtifactId {
    if let Some(id) = by_locator.get(locator) {
        return id.clone();
    }
    let id = ArtifactId::unresolved(locator);
    by_locator.insert(locator.to_string(), id.clone());
    graph.artifacts.push(ArtifactNode {
        id: id.clone(),
        locators: vec![locator.to_string()],
        embedded_artifacts: Vec::new(),
    });
    graph.entities.push(EntityRef::Artifact(id.clone()));
    id
}

pub(super) fn class_entity(name: &str, namespace: Option<&str>) -> EntityRef {
    EntityRef::Class(ClassSymbol::new(
        name,
        namespace
            .map(MappingNamespace::from_token)
            .unwrap_or(MappingNamespace::Unknown),
        MappingGraphId::new(UNMAPPED_GRAPH),
    ))
}

pub(super) fn link(
    from: EntityRef,
    relation: EvidenceRelation,
    to: EntityRef,
    origin: EvidenceOrigin,
    strength: EvidenceStrength,
    source_fact: FactId,
) -> EvidenceLink {
    EvidenceLink {
        from,
        relation,
        to,
        origin,
        strength,
        source_fact,
    }
}
