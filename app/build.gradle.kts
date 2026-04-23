plugins {
    id("java")
    id("jacoco")
    id("dev.architectury.loom") version "1.11.458"
}

group = "org.intermed"
version = providers.gradleProperty("intermedVersion").get()
val archivesBaseName = "InterMedCore"
val intermedBuildId = providers.gradleProperty("intermedBuildId")
    .orElse(version.toString())
    .get()

val localRepoDir = rootProject.layout.projectDirectory.dir("local-repo").asFile
val allowRemoteForgeRepo = providers.systemProperty("intermed.allowRemoteForgeRepo")
    .orNull
    ?.equals("true", ignoreCase = true) == true

if (!localRepoDir.exists() && !allowRemoteForgeRepo) {
    throw GradleException(
        "Local fallback repo not found at ${localRepoDir.path}. " +
            "Populate ./local-repo or re-run with -Dintermed.allowRemoteForgeRepo=true " +
            "to resolve Forge artifacts from remote repositories."
    )
}

repositories {
    if (localRepoDir.exists()) {
        maven {
            name = "LocalFallbackRepo"
            url = uri(localRepoDir)
            content {
                includeGroup("net.minecraftforge")
            }
        }
    }
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

loom {
    forge {
        // Use the @Mod annotation
        mixinConfigs.set(listOf("intermed.mixins.json"))
    }
    // Access-widen the game's code for deeper integration
    accessWidenerPath.set(project.file("src/main/resources/intermed.accesswidener"))

    runs {
        // Configure client and server runs
        named("client") {
            ideConfigGenerated(true)
            runDir("run")
            // Forge 1.20.1 + 21 mods needs at least 4-6 GB.
            // G1GC with short pause targets keeps the game smooth.
            vmArgs(
                "-Xmx6G", "-Xms2G",
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=50",
                "-XX:G1HeapRegionSize=32M",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:G1NewSizePercent=20",
                "-XX:G1ReservePercent=20",
                "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:G1MixedGCCountTarget=4"
            )
        }
        named("server") {
            ideConfigGenerated(true)
            runDir("run/server")
            vmArgs("-Xmx4G", "-Xms1G", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50")
        }
    }
}

val minecraftVersion: String by project
val forgeVersion: String by project
val graalVmVersion = "25.0.2"
val bootstrapSupport by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}


dependencies {
    // Minecraft and Forge dependencies are now managed by Loom
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    forge("net.minecraftforge:forge:$minecraftVersion-$forgeVersion")
    
    // Core Libraries (unchanged)
    implementation("net.bytebuddy:byte-buddy:1.14.12")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.12")
    implementation("net.fabricmc:tiny-remapper:0.10.3")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-tree:9.6")
    implementation("org.ow2.asm:asm-analysis:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("org.ow2.asm:asm-util:9.6")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    
    // GraalVM SDK
    implementation("org.graalvm.sdk:graal-sdk:$graalVmVersion")
    implementation("org.graalvm.polyglot:polyglot:$graalVmVersion")
    implementation("org.graalvm.polyglot:java:$graalVmVersion")
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("com.dylibso.chicory:wasm:1.7.5")
    implementation("com.dylibso.chicory:wasi:1.7.5")

    // Bridge compilation helpers
    compileOnly("io.netty:netty-all:4.1.82.Final")
    compileOnly("com.mojang:brigadier:1.0.18")
    
    // GSON for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Custom Mixin fork used by the runtime transmogrifier
    implementation(project(":mixin-fork"))
    implementation("io.github.llamalad7:mixinextras-common:0.5.0")

    // Mixin dependency
    compileOnly("org.spongepowered:mixin:0.8.5")
    
    // Null safety annotations
    compileOnly("org.jetbrains:annotations:24.0.1")

    // Testing dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    bootstrapSupport("net.bytebuddy:byte-buddy:1.14.12")
    bootstrapSupport("net.bytebuddy:byte-buddy-agent:1.14.12")
    // SecurityPolicy is loaded by the bootstrap classloader (appendToBootstrapClassLoaderSearch).
    // GSON must therefore also be in the bootstrap JAR so that SecurityPolicy.parseProfile can
    // resolve JsonObject.  Without this the bootstrap CL has no parent to delegate to and
    // NoClassDefFoundError: com/google/gson/JsonObject is thrown at the first GSON call.
    bootstrapSupport("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

jacoco {
    toolVersion = "0.8.12"
}

sourceSets {
    main {
        java {
            srcDir("src/main/java")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Integration tests spin up a full Forge environment; give them enough heap.
    maxHeapSize = "4g"
    jvmArgs("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100")
    systemProperty(
        "intermed.startup.outputDir",
        layout.buildDirectory.dir("reports/startup").get().asFile.absolutePath
    )
    systemProperty(
        "intermed.observability.outputDir",
        layout.buildDirectory.dir("reports/observability").get().asFile.absolutePath
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("microbench")
        excludeTags("soak")
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.20".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf(
                "org.intermed.core.config.RuntimeConfig",
                "org.intermed.core.report.CompatibilityCorpusGenerator",
                "org.intermed.core.report.CompatibilitySweepMatrixGenerator",
                "org.intermed.core.report.LaunchDiagnosticsBundle",
                "org.intermed.core.report.LaunchReadinessReportGenerator"
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

tasks.register("coverageGate") {
    group = "verification"
    description = "Runs the machine-enforced alpha coverage gate and emits JaCoCo reports."
    dependsOn(
        tasks.named("jacocoTestReport"),
        tasks.named("jacocoTestCoverageVerification")
    )
}

tasks.register<Test>("registryMicrobench") {
    group = "verification"
    description = "Runs internal microbenchmarks and emits report artifacts."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("microbench")
    }
    shouldRunAfter(tasks.named("test"))
    systemProperty(
        "intermed.microbench.outputDir",
        layout.buildDirectory.dir("reports/microbench").get().asFile.absolutePath
    )
}

tasks.register<Test>("compatibilitySmoke") {
    group = "verification"
    description = "Runs compatibility smoke tests for core loader boot paths."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("compat-smoke")
    }
    shouldRunAfter(tasks.named("test"))
}

tasks.register<Test>("strictSecurity") {
    group = "verification"
    description = "Runs strict security and sandbox regression tests with fail-closed defaults."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("strict-security")
    }
    shouldRunAfter(tasks.named("test"))
    systemProperty("security.strict.mode", "true")
    systemProperty("security.legacy.broad.permissions.enabled", "false")
    systemProperty("resolver.allow.fallback", "false")
    systemProperty("sandbox.espresso.enabled", "true")
    systemProperty("sandbox.wasm.enabled", "true")
    systemProperty("sandbox.native.fallback.enabled", "false")
    systemProperty(
        "intermed.security.outputDir",
        layout.buildDirectory.dir("reports/security").get().asFile.absolutePath
    )
}

tasks.register<Test>("runtimeSoak") {
    group = "verification"
    description = "Runs tagged runtime soak tests and emits stability artifacts."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("soak")
    }
    shouldRunAfter(tasks.named("test"))
    systemProperty(
        "intermed.soak.outputDir",
        layout.buildDirectory.dir("reports/soak").get().asFile.absolutePath
    )
}

tasks.register("verifyRuntime") {
    group = "verification"
    description = "Runs the full runtime verification lane: tests, compatibility smoke, microbenchmarks and soak."
    dependsOn(
        tasks.named("test"),
        tasks.named("compatibilitySmoke"),
        tasks.named("registryMicrobench"),
        tasks.named("runtimeSoak")
    )
}

tasks.named("check") {
    dependsOn(tasks.named("coverageGate"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// The custom JAR task for creating the fat agent is preserved
tasks.named<Jar>("jar") {
    // We rename the standard jar to avoid confusion
    archiveClassifier.set("thin") 
}

tasks.matching { it.name == "remapJar" }.configureEach {
    if (this is AbstractArchiveTask) {
        archiveClassifier.set("remapped")
    }
}

tasks.matching { it.name == "remapSourcesJar" }.configureEach {
    if (this is AbstractArchiveTask) {
        archiveClassifier.set("sources-remapped")
    }
}

fun Jar.configureCoreRuntimeManifest() {
    manifest {
        attributes(
            mapOf(
                "Main-Class" to "org.intermed.launcher.InterMedLauncher",
                "Premain-Class" to "org.intermed.core.InterMedKernel",
                "Agent-Class" to "org.intermed.core.InterMedKernel",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true",
                "Implementation-Version" to version,
                "InterMed-Build-Id" to intermedBuildId
            )
        )
    }
}

fun CopySpec.excludeProvidedRuntimeContent() {
    // The javaagent must not shadow Minecraft or loader-owned runtime classes.
    exclude(
        "net/minecraft/**",
        "com/mojang/**",
        "assets/**",
        "data/**",
        "pack.mcmeta",
        "version.json",
        "cpw/mods/**",
        "net/minecraftforge/**",
        "net/neoforged/**"
    )
    // Keep only InterMed's own metadata/resources from sourceSets.main; dependency
    // jars must not contribute extra loader descriptors or launch services.
    exclude(
        "mods.toml",
        "fabric.mod.json",
        "META-INF/mods.toml",
        "META-INF/neoforge.mods.toml",
        "META-INF/jarjar/**",
        "META-INF/accesstransformer.cfg",
        "META-INF/services/cpw.mods.*",
        "META-INF/services/net.minecraftforge.*",
        "META-INF/services/net.neoforged.*",
        "META-INF/services/com.mojang.*"
    )
}

// New task to create the fat "Core" jar
tasks.register<Jar>("coreJar") {
    archiveBaseName.set(archivesBaseName)
    archiveClassifier.set("") // No classifier for the main artifact

    // Fat JAR with GraalVM + Minecraft libraries exceeds 65535 entries
    isZip64 = true

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    configureCoreRuntimeManifest()
    
    // Bundle all dependencies — skip .pom/.xml artefacts that are not valid ZIPs
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get()
        .filter { it.isDirectory || it.name.endsWith(".jar") }
        .map { if (it.isDirectory) it else zipTree(it) }) {
        // Exclude signature files
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        excludeProvidedRuntimeContent()
        // Exclude oshi-core — Forge bundles its own copy; duplicate oshi.properties
        // on the classpath causes noisy warnings and potential classpath shadowing.
        exclude("oshi/**")
        exclude("oshi.properties")
        exclude("oshi.architecture.properties")
        // Exclude JPMS module descriptors from bundled deps.  If any module-info.class
        // ends up in the fat JAR the JVM may treat the whole JAR as a named module whose
        // descriptor doesn't export every bundled package, causing NoClassDefFoundError
        // for packages like com.google.gson at runtime.  Dropping all descriptors keeps
        // the fat JAR as a plain unnamed-module (classpath) JAR where every package is
        // accessible.
        exclude("**/module-info.class")
    }

    dependsOn(tasks.named("bootstrapJar"))
}

tasks.register<Jar>("coreFabricJar") {
    archiveBaseName.set(archivesBaseName)
    archiveClassifier.set("fabric")

    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    configureCoreRuntimeManifest()

    // Fabric baseline already provides real net.fabricmc.* classes.
    // Excluding the in-repo shims avoids duplicate loader/api classes on classpath.
    from(sourceSets.main.get().output) {
        exclude("net/fabricmc/loader/**")
        exclude("net/fabricmc/fabric/**")
        exclude("org/objectweb/asm/**")
        exclude("org/spongepowered/**")
        exclude("org/intermed/mixin/**")
        exclude("com/llamalad7/mixinextras/**")
        exclude("META-INF/services/org.spongepowered.asm.service.*")
        exclude("META-INF/services/com.llamalad7.mixinextras.*")
    }
    from(configurations.runtimeClasspath.get()
        .filter { it.isDirectory || it.name.endsWith(".jar") }
        .map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        excludeProvidedRuntimeContent()
        exclude("net/fabricmc/loader/**")
        exclude("net/fabricmc/fabric/**")
        exclude("org/objectweb/asm/**")
        exclude("org/spongepowered/**")
        exclude("org/intermed/mixin/**")
        exclude("com/llamalad7/mixinextras/**")
        exclude("META-INF/services/org.spongepowered.asm.service.*")
        exclude("META-INF/services/com.llamalad7.mixinextras.*")
        exclude("**/module-info.class")
    }

    dependsOn(tasks.named("bootstrapJar"))
}

tasks.register<Jar>("bootstrapJar") {
    archiveBaseName.set(archivesBaseName)
    archiveClassifier.set("bootstrap")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            mapOf(
                "Implementation-Title" to "InterMed Bootstrap Support",
                "Implementation-Version" to version,
                "InterMed-Build-Id" to intermedBuildId
            )
        )
    }

    from(sourceSets.main.get().output) {
        include("org/intermed/security/**")
        include("org/intermed/core/bridge/forge/**")
        include("org/intermed/core/security/**")
        include("org/intermed/core/registry/**")
        include("org/intermed/core/metadata/**")
        exclude("org/intermed/core/security/SecurityHookTransformer*")
        exclude("org/intermed/core/security/SecurityInjector*")
        exclude("org/intermed/core/registry/RegistryHookTransformer*")
    }
    from(bootstrapSupport.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        // Keep bootstrap JAR as unnamed-module JAR (same reason as fat JAR)
        exclude("**/module-info.class")
    }
}
// Make the coreJar the default build artifact
tasks.named("build") {
    dependsOn("coreJar", "coreFabricJar", "bootstrapJar")
}
