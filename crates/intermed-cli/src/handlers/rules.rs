use crate::*;

pub(crate) fn run_rules(args: RulesArgs) -> ExitCode {
    match args.command {
        RulesCommand::Check(args) => run_rules_check(args),
        RulesCommand::Generate(args) => run_rules_generate(args),
        RulesCommand::Sign(args) => run_rules_sign(args),
        RulesCommand::Verify(args) => run_rules_verify(args),
        RulesCommand::Update(args) => run_rules_update(args),
        RulesCommand::Registry(args) => run_rules_registry(args),
        RulesCommand::Install(args) => run_rules_install(args),
        RulesCommand::Explain(args) => run_rules_explain(args),
    }
}

fn run_rules_generate(args: intermed_cli::command::RulesGenerateArgs) -> ExitCode {
    use intermed_cli::command::RulesGenerateBackend;

    let pack = match if args.pack.as_os_str().is_empty() || !args.pack.exists() {
        Ok(intermed_rules::default_core_pack_v3())
    } else if args.pack.is_file() {
        load_rule_pack(&args.pack)
    } else {
        Err(intermed_rules::RulePackError::new(format!(
            "pack not found: {}",
            args.pack.display()
        )))
    } {
        Ok(pack) => pack,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };

    let backend = match args.backend {
        RulesGenerateBackend::Sql => GenerateBackend::Sql,
        RulesGenerateBackend::Rust => GenerateBackend::Rust,
        RulesGenerateBackend::Datalog => GenerateBackend::Datalog,
        RulesGenerateBackend::Explain => GenerateBackend::Explain,
    };
    let output = generate_rules(&pack, backend);

    if let Some(path) = args.out {
        if let Err(e) = std::fs::write(&path, &output) {
            eprintln!("error: write {}: {e}", path.display());
            return ExitCode::from(2);
        }
        println!("wrote {} ({} bytes)", path.display(), output.len());
    } else {
        print!("{output}");
    }
    ExitCode::SUCCESS
}

fn run_rules_explain(args: intermed_cli::command::RulesExplainArgs) -> ExitCode {
    let pack = match if args.pack.as_os_str().is_empty() || !args.pack.exists() {
        Ok(intermed_rules::default_core_pack_v3())
    } else if args.pack.is_file() {
        load_rule_pack(&args.pack)
    } else {
        Err(intermed_rules::RulePackError::new(format!(
            "pack not found: {}",
            args.pack.display()
        )))
    } {
        Ok(pack) => pack,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };

    // Optional fact dump enables EXPLAIN ANALYZE on real facts.
    let facts: Option<Vec<intermed_doctor_core::facts::Fact>> = match &args.facts {
        Some(path) => match std::fs::read_to_string(path)
            .map_err(|e| e.to_string())
            .and_then(|t| serde_json::from_str(&t).map_err(|e| e.to_string()))
        {
            Ok(f) => Some(f),
            Err(e) => {
                eprintln!("error: read facts {}: {e}", path.display());
                return ExitCode::from(2);
            }
        },
        None => None,
    };

    let output = intermed_rules::explain_plans(&pack, args.rule.as_deref(), facts.as_deref());
    if output.trim().is_empty() {
        eprintln!(
            "no lowerable rule to explain{}",
            args.rule
                .as_deref()
                .map(|r| format!(" matching `{r}`"))
                .unwrap_or_default()
        );
        return ExitCode::from(1);
    }
    print!("{output}");
    ExitCode::SUCCESS
}

fn run_rules_check(args: intermed_cli::command::RulesCheckArgs) -> ExitCode {
    let check = check_rule_packs(&args.path);
    println!("InterMed Rules");
    println!("Path: {}", args.path.display());
    println!("Files: {}", check.files);
    println!("Rules: {}", check.rules);
    if !check.is_ok() {
        println!("Status: failed");
        for error in check.errors {
            println!("error: {error}");
        }
        return ExitCode::from(2);
    }
    if args.require_signature || args.trusted_keys.is_some() {
        let trusted = match &args.trusted_keys {
            Some(path) => match load_trusted_keys(path) {
                Ok(keys) => keys,
                Err(e) => {
                    eprintln!("error: {e}");
                    return ExitCode::from(2);
                }
            },
            None => Vec::new(),
        };
        let mut files = Vec::new();
        if args.path.is_file() {
            files.push(args.path.clone());
        } else if let Ok(rd) = std::fs::read_dir(&args.path) {
            for entry in rd.flatten() {
                let p = entry.path();
                if p.is_file() {
                    files.push(p);
                }
            }
        }
        for file in files {
            if let Ok(pack) = load_rule_pack(&file) {
                if args.require_signature && pack.signature.is_none() {
                    eprintln!("error: {}: signature required but missing", file.display());
                    return ExitCode::from(2);
                }
                if pack.signature.is_some()
                    && let Err(e) = verify_rule_pack_signature(&pack, &trusted)
                {
                    eprintln!("error: {}: {e}", file.display());
                    return ExitCode::from(2);
                }
            }
        }
    }
    if args.trace {
        let facts_path = match &args.facts {
            Some(p) => p,
            None => {
                eprintln!("error: --trace requires --facts FILE");
                return ExitCode::from(2);
            }
        };
        let text = match std::fs::read_to_string(facts_path) {
            Ok(t) => t,
            Err(e) => {
                eprintln!("error: read {}: {e}", facts_path.display());
                return ExitCode::from(2);
            }
        };
        let facts: Vec<Fact> = match serde_json::from_str(&text) {
            Ok(f) => f,
            Err(e) => {
                eprintln!("error: parse facts json: {e}");
                return ExitCode::from(2);
            }
        };
        let store = intermed_doctor_core::facts::FactStore::from_snapshot(facts);
        let target = Target {
            path: ".".into(),
            kind: TargetKind::ModsDir,
            mods_dir: None,
            game_root: None,
            layout: None,
            instance_type: None,
            spark_report: None,
        };
        let ctx = intermed_doctor_core::RuleCtx::for_test(&store, &target);
        let pack = match load_rule_pack(&args.path) {
            Ok(p) => p,
            Err(_) => default_core_pack_v2_from_path(&args.path),
        };
        let lines = trace_pack(&pack, &ctx);
        print!("{}", format_trace(&lines));
    }
    println!("Status: ok");
    ExitCode::SUCCESS
}

fn default_core_pack_v2_from_path(path: &Path) -> intermed_rules::RulePack {
    if path.is_file() {
        load_rule_pack(path).unwrap_or_else(|_| intermed_rules::default_core_pack_v3())
    } else {
        intermed_rules::default_core_pack_v3()
    }
}

fn run_rules_install(args: intermed_cli::command::RulesInstallArgs) -> ExitCode {
    let policy = intermed_rules::TrustPolicy {
        allow_insecure_registry: args.allow_insecure_registry,
        allow_unsigned_rules: args.allow_unsigned_rules,
    };
    let registry = match args.registry.as_deref() {
        Some(src) => match load_registry_from_source(src, &policy) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("error: {e}");
                return ExitCode::from(2);
            }
        },
        None => merged_default_registry(),
    };
    let install_dir = match args
        .install_dir
        .or_else(|| default_rule_pack_install_dir().ok())
    {
        Some(d) => d,
        None => {
            eprintln!("error: could not resolve rule pack install directory");
            return ExitCode::from(2);
        }
    };
    if let Err(e) = std::fs::create_dir_all(&install_dir) {
        eprintln!("error: create {}: {e}", install_dir.display());
        return ExitCode::from(2);
    }
    let trusted = match &args.trusted_keys {
        Some(path) => match load_trusted_keys(path) {
            Ok(k) => k,
            Err(e) => {
                eprintln!("error: {e}");
                return ExitCode::from(2);
            }
        },
        None => Vec::new(),
    };
    match install_pack_with_dependencies(&registry, &args.pack_id, &install_dir, &trusted, &policy)
    {
        Ok(paths) => {
            for path in paths {
                println!("installed {}", path.display());
            }
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(2)
        }
    }
}
fn run_rules_sign(args: intermed_cli::command::RulesSignArgs) -> ExitCode {
    let pack = match load_rule_pack(&args.pack) {
        Ok(mut pack) => {
            if pack.schema != RULE_PACK_SCHEMA_V2 && pack.schema != RULE_PACK_SCHEMA_V3 {
                pack.schema = RULE_PACK_SCHEMA_V2.to_string();
                if pack.version.is_empty() {
                    pack.version = env!("CARGO_PKG_VERSION").to_string();
                }
                if pack.publisher.is_none() {
                    pack.publisher = Some("intermed".to_string());
                }
            }
            pack
        }
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };
    if let Err(e) = validate_rule_pack(&pack) {
        eprintln!("error: {e}");
        return ExitCode::from(2);
    }
    let key_bytes = match std::fs::read(&args.key) {
        Ok(bytes) => bytes,
        Err(e) => {
            eprintln!("error: read {}: {e}", args.key.display());
            return ExitCode::from(2);
        }
    };
    let signing_key = match load_signing_key(&key_bytes) {
        Ok(key) => key,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };
    let signature = match intermed_rules::sign_rule_pack_now(&pack, &signing_key) {
        Ok(sig) => sig,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };
    let mut signed = pack;
    signed.signature = Some(signature);
    let out = args.out.as_ref().unwrap_or(&args.pack);
    let json = match serde_json::to_string_pretty(&signed) {
        Ok(text) => text,
        Err(e) => {
            eprintln!("error: serialize signed pack: {e}");
            return ExitCode::from(2);
        }
    };
    if let Err(e) = std::fs::write(out, json) {
        eprintln!("error: write {}: {e}", out.display());
        return ExitCode::from(2);
    }
    println!("Signed rule pack written to {}", out.display());
    ExitCode::SUCCESS
}

fn run_rules_verify(args: intermed_cli::command::RulesVerifyArgs) -> ExitCode {
    let pack = match load_rule_pack(&args.pack) {
        Ok(pack) => pack,
        Err(e) => {
            eprintln!("error: {e}");
            return ExitCode::from(2);
        }
    };
    let trusted = match &args.trusted_keys {
        Some(path) => match load_trusted_keys(path) {
            Ok(keys) => keys,
            Err(e) => {
                eprintln!("error: {e}");
                return ExitCode::from(2);
            }
        },
        None => Vec::new(),
    };
    match verify_rule_pack_signature(&pack, &trusted) {
        Ok(()) => {
            println!("Signature valid for pack `{}` v{}", pack.id, pack.version);
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(2)
        }
    }
}

fn run_rules_update(args: intermed_cli::command::RulesUpdateArgs) -> ExitCode {
    let policy = intermed_rules::TrustPolicy {
        allow_insecure_registry: args.allow_insecure_registry,
        allow_unsigned_rules: args.allow_unsigned_rules,
    };
    let registry = match &args.registry {
        Some(source) => match load_registry_from_source(source, &policy) {
            Ok(reg) => reg,
            Err(e) => {
                eprintln!("error: {e}");
                return ExitCode::from(2);
            }
        },
        None => merged_default_registry(),
    };
    if registry.schema != RULE_REGISTRY_SCHEMA {
        eprintln!("error: unsupported registry schema: {}", registry.schema);
        return ExitCode::from(2);
    }
    let install_dir = match args
        .install_dir
        .clone()
        .or_else(|| default_rule_pack_install_dir().ok())
    {
        Some(dir) => dir,
        None => {
            eprintln!("error: could not resolve rule-pack install directory");
            return ExitCode::from(2);
        }
    };
    let trusted = match &args.trusted_keys {
        Some(path) => match load_trusted_keys(path) {
            Ok(keys) => keys,
            Err(e) => {
                eprintln!("error: {e}");
                return ExitCode::from(2);
            }
        },
        None => Vec::new(),
    };
    match install_pack_from_registry(&registry, &args.pack_id, &install_dir, &trusted, &policy) {
        Ok(path) => {
            println!(
                "Updated pack `{}` → {} (digest verified)",
                args.pack_id,
                path.display()
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(2)
        }
    }
}

fn run_rules_registry(args: intermed_cli::command::RulesRegistryArgs) -> ExitCode {
    let policy = intermed_rules::TrustPolicy {
        allow_insecure_registry: args.allow_insecure_registry,
        allow_unsigned_rules: false,
    };
    let registry = match &args.registry {
        Some(source) => match load_registry_from_source(source, &policy) {
            Ok(reg) => reg,
            Err(e) => {
                eprintln!("error: {e}");
                return ExitCode::from(2);
            }
        },
        None => merged_default_registry(),
    };
    println!("{}", registry_to_json(&registry));
    ExitCode::SUCCESS
}
