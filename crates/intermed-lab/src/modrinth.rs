//! Authoritative Modrinth `.mrpack` manifest ingestion.

use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Component, Path};

use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::corpus::{CorpusEnvironment, CorpusLock, LockedPackFile, PackIdentity};
use crate::{LabError, write_json_atomic};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModrinthIndex {
    format_version: u32,
    game: String,
    name: String,
    version_id: String,
    dependencies: BTreeMap<String, String>,
    #[serde(default)]
    files: Vec<ModrinthFile>,
}

#[derive(Debug, Deserialize)]
struct ModrinthFile {
    path: String,
    hashes: BTreeMap<String, String>,
    #[serde(default)]
    downloads: Vec<String>,
    #[serde(default)]
    env: Option<ModrinthEnvironment>,
}

#[derive(Debug, Deserialize)]
struct ModrinthEnvironment {
    #[serde(default)]
    client: Option<String>,
    #[serde(default)]
    server: Option<String>,
}

pub fn lock_modrinth_manifest(path: &Path, out: &Path) -> Result<CorpusLock, LabError> {
    let bytes = read_manifest(path)?;
    let manifest: ModrinthIndex = serde_json::from_slice(&bytes)
        .map_err(|error| LabError::new(format!("parse {}: {error}", path.display())))?;
    if manifest.format_version != 1 || manifest.game != "minecraft" {
        return Err(LabError::new(format!(
            "unsupported Modrinth pack format/game: {} / {}",
            manifest.format_version, manifest.game
        )));
    }
    let minecraft = manifest
        .dependencies
        .get("minecraft")
        .cloned()
        .ok_or_else(|| LabError::new("Modrinth manifest does not declare Minecraft"))?;
    let loader_candidates = [
        ("fabric-loader", "fabric"),
        ("quilt-loader", "quilt"),
        ("forge", "forge"),
        ("neoforge", "neoforge"),
    ]
    .into_iter()
    .filter_map(|(key, family)| {
        manifest
            .dependencies
            .get(key)
            .map(|version| (family.to_string(), version.clone()))
    })
    .collect::<Vec<_>>();
    if loader_candidates.len() != 1 {
        return Err(LabError::new(format!(
            "Modrinth manifest must declare exactly one loader, found {}",
            loader_candidates.len()
        )));
    }
    let (loader, loader_version) = loader_candidates[0].clone();
    let mut files = Vec::with_capacity(manifest.files.len());
    for file in manifest.files {
        validate_relative_path(&file.path)?;
        let sha512 = file.hashes.get("sha512").cloned();
        if sha512.is_none() {
            return Err(LabError::new(format!(
                "Modrinth file `{}` has no sha512 content identity",
                file.path
            )));
        }
        let (client_required, server_required) = match file.env {
            None => (true, true),
            Some(env) => (
                env.client.as_deref() == Some("required"),
                env.server.as_deref() == Some("required"),
            ),
        };
        files.push(LockedPackFile {
            path: file.path,
            sha512,
            sha256: file.hashes.get("sha256").cloned(),
            downloads: file.downloads,
            client_required,
            server_required,
        });
    }
    files.extend(read_override_files(path)?);
    let mut by_path = BTreeMap::<String, LockedPackFile>::new();
    for mut file in files {
        match by_path.entry(file.path.clone()) {
            std::collections::btree_map::Entry::Vacant(entry) => {
                file.downloads.sort();
                file.downloads.dedup();
                entry.insert(file);
            }
            std::collections::btree_map::Entry::Occupied(mut entry) => {
                let current = entry.get_mut();
                if current.sha512 != file.sha512 || current.sha256 != file.sha256 {
                    return Err(LabError::new(format!(
                        "mrpack contains conflicting payloads for `{}`",
                        file.path
                    )));
                }
                current.client_required |= file.client_required;
                current.server_required |= file.server_required;
                current.downloads.append(&mut file.downloads);
                current.downloads.sort();
                current.downloads.dedup();
            }
        }
    }
    let files = by_path.into_values().collect::<Vec<_>>();
    let lock = CorpusLock::from_pack_manifest(
        CorpusEnvironment {
            loader,
            mc_version: minecraft,
            side: "both".to_string(),
            loader_version: Some(loader_version),
        },
        files,
        PackIdentity {
            provider: "modrinth".to_string(),
            name: manifest.name,
            version_id: manifest.version_id,
            manifest_sha256: format!("{:x}", Sha256::digest(&bytes)),
        },
    );
    write_json_atomic(out, &lock)?;
    Ok(lock)
}

fn read_override_files(path: &Path) -> Result<Vec<LockedPackFile>, LabError> {
    const MAX_ENTRIES: usize = 100_000;
    const MAX_TOTAL_BYTES: u64 = 4 * 1024 * 1024 * 1024;
    if path.extension().and_then(|value| value.to_str()) != Some("mrpack") {
        return Ok(Vec::new());
    }
    let file = std::fs::File::open(path)
        .map_err(|error| LabError::new(format!("open {}: {error}", path.display())))?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|error| LabError::new(format!("open mrpack {}: {error}", path.display())))?;
    if archive.len() > MAX_ENTRIES {
        return Err(LabError::new("mrpack exceeds the ZIP entry-count budget"));
    }
    let mut total = 0u64;
    let mut files = Vec::new();
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|error| LabError::new(format!("read mrpack entry {index}: {error}")))?;
        if entry.is_dir() {
            continue;
        }
        let entry_name = entry.name().to_string();
        let Some((prefix, relative)) = entry_name
            .split_once('/')
            .filter(|(prefix, _)| matches!(*prefix, "overrides" | "client-overrides"))
        else {
            continue;
        };
        let client_required = matches!(prefix, "client-overrides" | "overrides");
        let server_required = prefix == "overrides";
        validate_relative_path(relative)?;
        // Overrides may legitimately be large resource/audio packs. Hash them
        // as a stream instead of conflating entry size with peak memory. The
        // cumulative uncompressed budget still bounds total work. Some
        // ecosystem ZIP writers put a stale size in the central directory
        // while retaining a valid deflate stream and CRC, so entry.size()
        // cannot be treated as content identity or as the authoritative
        // budget counter. Charge bytes as the decoder actually produces them.
        let mut sha256 = Sha256::new();
        let mut sha512 = sha2::Sha512::new();
        let mut buffer = [0u8; 128 * 1024];
        loop {
            let read = entry
                .read(&mut buffer)
                .map_err(|error| LabError::new(format!("read override `{relative}`: {error}")))?;
            if read == 0 {
                break;
            }
            total = total
                .checked_add(read as u64)
                .ok_or_else(|| LabError::new("mrpack override byte count overflow"))?;
            if total > MAX_TOTAL_BYTES {
                return Err(LabError::new(
                    "mrpack overrides exceed the total byte budget",
                ));
            }
            sha256.update(&buffer[..read]);
            sha512.update(&buffer[..read]);
        }
        files.push(LockedPackFile {
            path: relative.to_string(),
            sha512: Some(format!("{:x}", sha512.finalize())),
            sha256: Some(format!("{:x}", sha256.finalize())),
            downloads: Vec::new(),
            client_required,
            server_required,
        });
    }
    Ok(files)
}

fn read_manifest(path: &Path) -> Result<Vec<u8>, LabError> {
    const MAX_MANIFEST_BYTES: u64 = 8 * 1024 * 1024;
    if path.extension().and_then(|value| value.to_str()) != Some("mrpack") {
        let metadata = std::fs::metadata(path)
            .map_err(|error| LabError::new(format!("stat {}: {error}", path.display())))?;
        if metadata.len() > MAX_MANIFEST_BYTES {
            return Err(LabError::new("Modrinth manifest exceeds the 8 MiB limit"));
        }
        return std::fs::read(path)
            .map_err(|error| LabError::new(format!("read {}: {error}", path.display())));
    }
    let file = std::fs::File::open(path)
        .map_err(|error| LabError::new(format!("open {}: {error}", path.display())))?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|error| LabError::new(format!("open mrpack {}: {error}", path.display())))?;
    let entry = archive
        .by_name("modrinth.index.json")
        .map_err(|error| LabError::new(format!("read modrinth.index.json: {error}")))?;
    if entry.size() > MAX_MANIFEST_BYTES {
        return Err(LabError::new("Modrinth manifest exceeds the 8 MiB limit"));
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry
        .take(MAX_MANIFEST_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| LabError::new(format!("read modrinth.index.json: {error}")))?;
    if bytes.len() as u64 > MAX_MANIFEST_BYTES {
        return Err(LabError::new("Modrinth manifest exceeds the 8 MiB limit"));
    }
    Ok(bytes)
}

fn validate_relative_path(value: &str) -> Result<(), LabError> {
    let path = Path::new(value);
    if value.is_empty()
        || value.contains('\\')
        || path.is_absolute()
        || path
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err(LabError::new(format!(
            "unsafe Modrinth pack path: `{value}`"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn rejects_parent_traversal() {
        assert!(validate_relative_path("../mods/escape.jar").is_err());
        assert!(validate_relative_path("./mods/good.jar").is_err());
        assert!(validate_relative_path("mods\\..\\escape.jar").is_err());
        assert!(validate_relative_path("mods/good.jar").is_ok());
    }

    #[test]
    fn large_override_is_streamed_instead_of_rejected_by_entry_size() {
        let path = std::env::temp_dir().join(format!(
            "intermed-large-override-{}-{}.mrpack",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let file = std::fs::File::create(&path).expect("create mrpack");
        let mut archive = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        archive
            .start_file("modrinth.index.json", options)
            .expect("start manifest");
        archive
            .write_all(
                br#"{"formatVersion":1,"game":"minecraft","name":"large override","versionId":"1","dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.0"},"files":[]}"#,
            )
            .expect("write manifest");
        archive
            .start_file("overrides/resourcepacks/large.zip", options)
            .expect("start override");
        let zeroes = [0_u8; 128 * 1024];
        // Exceeds the former 128 MiB per-entry cap while retaining a tiny
        // compressed fixture and constant peak memory.
        for _ in 0..=1024 {
            archive.write_all(&zeroes).expect("write override chunk");
        }
        archive.finish().expect("finish mrpack");

        let files = read_override_files(&path).expect("large override should be accepted");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].path, "resourcepacks/large.zip");
        assert!(files[0].sha256.is_some());
        assert!(files[0].sha512.is_some());
        std::fs::remove_file(path).expect("remove fixture");
    }

    #[test]
    fn stale_central_directory_size_does_not_reject_valid_override_stream() {
        let path = std::env::temp_dir().join(format!(
            "intermed-stale-zip-size-{}-{}.mrpack",
            std::process::id(),
            std::thread::current().name().unwrap_or("test")
        ));
        let file = std::fs::File::create(&path).expect("create mrpack");
        let mut archive = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        archive
            .start_file("modrinth.index.json", options)
            .expect("start manifest");
        archive
            .write_all(
                br#"{"formatVersion":1,"game":"minecraft","name":"stale size","versionId":"1","dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.0"},"files":[]}"#,
            )
            .expect("write manifest");
        archive
            .start_file("overrides/mods/example.jar", options)
            .expect("start override");
        archive
            .write_all(&vec![0x5a; 128 * 1024])
            .expect("write override");
        archive.finish().expect("finish mrpack");

        // Reproduce a real Modrinth archive whose central-directory
        // uncompressed size is stale while the local stream and CRC remain
        // valid. The decoder can recover the complete entry.
        let mut bytes = std::fs::read(&path).expect("read mrpack");
        let signature = [0x50, 0x4b, 0x01, 0x02];
        let mut patched = false;
        for offset in 0..bytes.len().saturating_sub(46) {
            if bytes[offset..offset + 4] != signature {
                continue;
            }
            let name_len = u16::from_le_bytes([bytes[offset + 28], bytes[offset + 29]]) as usize;
            let name_start = offset + 46;
            let name_end = name_start + name_len;
            if name_end <= bytes.len()
                && &bytes[name_start..name_end] == b"overrides/mods/example.jar"
            {
                bytes[offset + 24..offset + 28].copy_from_slice(&1_u32.to_le_bytes());
                patched = true;
                break;
            }
        }
        assert!(patched, "override central-directory entry must be found");
        std::fs::write(&path, bytes).expect("patch mrpack");

        let files = read_override_files(&path).expect("valid decoded stream should win");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].path, "mods/example.jar");
        std::fs::remove_file(path).expect("remove fixture");
    }
}
