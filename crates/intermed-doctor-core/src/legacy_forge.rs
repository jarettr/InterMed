//! Bounded, loader-independent parsing helpers for legacy Forge `mcmod.info`.
//!
//! Forge before `mods.toml` used a small JSON descriptor at the archive root.
//! Keeping this parser in the core crate lets metadata, SBOM, and identity
//! collectors agree that the same legacy jar has a real declared identity.

use serde::{Deserialize, Serialize};

/// The identity-bearing subset of one `mcmod.info` entry.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LegacyForgeMod {
    pub mod_id: String,
    pub version: Option<String>,
    pub name: Option<String>,
    pub description: Option<String>,
    pub authors: Vec<String>,
    pub logo_file: Option<String>,
    pub update_url: Option<String>,
    pub required_mods: Vec<String>,
}

/// Parse the two shapes used in the wild: a root array and the older
/// `{ "modList": [...] }` wrapper. Unknown fields are deliberately ignored.
pub fn parse_mcmod_info(text: &str) -> Result<Vec<LegacyForgeMod>, serde_json::Error> {
    // Legacy FML used Gson's lenient reader in practice. A sizeable body of
    // 1.12-era descriptors contains literal newlines/tabs inside description
    // strings and loads successfully in Forge. Reuse the bounded compatibility
    // parser while retaining genuine structural JSON errors.
    let value = crate::fabric_json::parse_value(text)?;
    let entries = match &value {
        serde_json::Value::Array(entries) => entries.as_slice(),
        serde_json::Value::Object(root) => root
            .get("modList")
            .and_then(serde_json::Value::as_array)
            .map(Vec::as_slice)
            .unwrap_or(&[]),
        _ => &[],
    };

    Ok(entries
        .iter()
        .filter_map(|entry| {
            let object = entry.as_object()?;
            let mod_id = object
                .get("modid")
                .or_else(|| object.get("modId"))
                .and_then(serde_json::Value::as_str)?
                .trim();
            if mod_id.is_empty() {
                return None;
            }
            Some(LegacyForgeMod {
                mod_id: mod_id.to_string(),
                version: string_field(object, &["version"]),
                name: string_field(object, &["name"]),
                description: string_field(object, &["description"]),
                authors: string_array_field(object, &["authorList", "authors"]),
                logo_file: string_field(object, &["logoFile"]),
                update_url: string_field(object, &["updateUrl", "updateJSON"]),
                required_mods: string_array_field(object, &["requiredMods"]),
            })
        })
        .collect())
}

fn string_field(
    object: &serde_json::Map<String, serde_json::Value>,
    names: &[&str],
) -> Option<String> {
    names.iter().find_map(|name| {
        object
            .get(*name)
            .and_then(serde_json::Value::as_str)
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_string)
    })
}

fn string_array_field(
    object: &serde_json::Map<String, serde_json::Value>,
    names: &[&str],
) -> Vec<String> {
    names
        .iter()
        .find_map(|name| object.get(*name).and_then(serde_json::Value::as_array))
        .into_iter()
        .flatten()
        .filter_map(serde_json::Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_root_array_and_legacy_fields() {
        let parsed = parse_mcmod_info(
            r#"[{"modid":"creativecore","name":"CreativeCore","version":"1.10","authorList":["CreativeMD"],"requiredMods":["forge@[14.23.5.2847,)"]}]"#,
        )
        .unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].mod_id, "creativecore");
        assert_eq!(parsed[0].version.as_deref(), Some("1.10"));
        assert_eq!(parsed[0].authors, ["CreativeMD"]);
        assert_eq!(parsed[0].required_mods, ["forge@[14.23.5.2847,)"]);
    }

    #[test]
    fn parses_mod_list_wrapper_and_skips_missing_ids() {
        let parsed = parse_mcmod_info(
            r#"{"modList":[{"modId":"wrapped","version":"2"},{"name":"broken"}]}"#,
        )
        .unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].mod_id, "wrapped");
    }

    #[test]
    fn accepts_literal_control_characters_used_by_legacy_forge() {
        let parsed = parse_mcmod_info(
            "[{\"modid\":\"legacy\",\"description\":\"first line\nsecond line\"}]",
        )
        .unwrap();
        assert_eq!(
            parsed[0].description.as_deref(),
            Some("first line\nsecond line")
        );
    }
}
