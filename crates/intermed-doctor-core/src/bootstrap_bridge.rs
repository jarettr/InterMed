//! Exact identity recognition for descriptorless loader bootstrap artifacts.
//!
//! These outer JARs deliberately keep their normal mod descriptor in a nested
//! artifact. Recognition therefore requires both manifest identity and the
//! loader service-provider entries; filenames are never evidence.

use std::io::{Read, Seek};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BootstrapBridgeIdentity {
    pub id: String,
    pub version: Option<String>,
    pub loader_family: String,
}

#[must_use]
pub fn detect_connector<R: Read + Seek>(
    archive: &mut zip::ZipArchive<R>,
) -> Option<BootstrapBridgeIdentity> {
    let manifest = crate::bounded_zip::read_zip_text_opt(
        archive,
        "META-INF/MANIFEST.MF",
        crate::bounded_zip::MAX_MANIFEST_BYTES,
    )?;
    let title = manifest_attribute(&manifest, "Specification-Title")
        .or_else(|| manifest_attribute(&manifest, "Implementation-Title"))?;
    if !title.eq_ignore_ascii_case("connector") {
        return None;
    }
    let transformer = crate::bounded_zip::read_zip_text_opt(
        archive,
        "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
        crate::bounded_zip::MAX_MANIFEST_BYTES,
    )?;
    let has_transformer = transformer.lines().map(str::trim).any(|provider| {
        provider == "org.sinytra.connector.service.ConnectorLoaderService"
            || provider.starts_with("org.sinytra.connector.")
    });
    let has_candidate_locator = archive
        .by_name("META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator")
        .is_ok();
    // Connector 1.x on Forge 1.20.1 predates NeoForge's candidate-locator SPI.
    // Its outer bootstrap JAR instead registers both Forge locator services.
    // Require the exact Sinytra providers so a generic ModLauncher transformer
    // is not promoted to a compatibility bridge merely because of its title.
    let forge_mod_locator = crate::bounded_zip::read_zip_text_opt(
        archive,
        "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
        crate::bounded_zip::MAX_MANIFEST_BYTES,
    );
    let forge_dependency_locator = crate::bounded_zip::read_zip_text_opt(
        archive,
        "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator",
        crate::bounded_zip::MAX_MANIFEST_BYTES,
    );
    let has_forge_locators = forge_mod_locator.is_some_and(|providers| {
        providers
            .lines()
            .map(str::trim)
            .any(|provider| provider == "org.sinytra.connector.locator.ConnectorEarlyLocator")
    }) && forge_dependency_locator.is_some_and(|providers| {
        providers
            .lines()
            .map(str::trim)
            .any(|provider| provider == "org.sinytra.connector.locator.ConnectorLocator")
    });
    if !has_transformer || (!has_candidate_locator && !has_forge_locators) {
        return None;
    }
    Some(BootstrapBridgeIdentity {
        id: "connector".to_string(),
        version: manifest_attribute(&manifest, "Implementation-Version").map(str::to_string),
        loader_family: if has_candidate_locator {
            "neoforge".to_string()
        } else {
            "forge".to_string()
        },
    })
}

/// Recognize Essential's descriptorless stage-0 ModLauncher container. The
/// outer artifact intentionally has no `mods.toml`; identity is carried by its
/// exact transformation service and bounded loader properties.
#[must_use]
pub fn detect_essential_loader<R: Read + Seek>(
    archive: &mut zip::ZipArchive<R>,
) -> Option<BootstrapBridgeIdentity> {
    let service = crate::bounded_zip::read_zip_text_opt(
        archive,
        "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
        crate::bounded_zip::MAX_MANIFEST_BYTES,
    )?;
    if !service
        .lines()
        .map(str::trim)
        .any(|line| line == "gg.essential.container.loader.stage0.EssentialTransformationService")
    {
        return None;
    }
    let properties = crate::bounded_zip::read_zip_text_opt(
        archive,
        "essential-loader.properties",
        crate::bounded_zip::MAX_MANIFEST_BYTES,
    )?;
    let values = properties
        .lines()
        .filter_map(|line| line.split_once('='))
        .map(|(key, value)| (key.trim(), value.trim()))
        .collect::<std::collections::BTreeMap<_, _>>();
    if values.get("publisherSlug") != Some(&"essential")
        || values.get("modSlug") != Some(&"essential")
    {
        return None;
    }
    Some(BootstrapBridgeIdentity {
        id: "essential".to_string(),
        version: values
            .get("pinnedFileVersion")
            .map(|value| value.to_string()),
        loader_family: "forge".to_string(),
    })
}

fn manifest_attribute<'a>(manifest: &'a str, key: &str) -> Option<&'a str> {
    manifest.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        name.trim().eq_ignore_ascii_case(key).then(|| value.trim())
    })
}
