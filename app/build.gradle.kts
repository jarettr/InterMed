plugins {
    id("java")
    id("dev.architectury.loom") version "1.6-SNAPSHOT"
}

group = "org.intermed"
version = "9.0-ULTIMATE"
val archivesBaseName = "InterMedCore"

repositories {
    mavenCentral()
    maven {
        name = "LocalFallbackRepo"
        url = uri("file:///${project.projectDir}/../local-repo")
    }
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
        }
        named("server") {
            ideConfigGenerated(true)
            runDir("run/server")
        }
    }
}

val minecraftVersion: String by project
val forgeVersion: String by project

dependencies {
    // Minecraft and Forge dependencies are now managed by Loom
    minecraft("com.mojang:minecraft:$minecraftVersion")
    forge("net.minecraftforge:forge:$minecraftVersion-$forgeVersion")
    
    // Core Libraries (unchanged)
    implementation("net.bytebuddy:byte-buddy:1.14.12")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.12")
    implementation("net.fabricmc:tiny-remapper:0.10.3")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-tree:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("org.ow2.asm:asm-util:9.6")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    
    // GraalVM SDK
    implementation("org.graalvm.sdk:graal-sdk:23.1.2")
    implementation("org.graalvm.polyglot:polyglot:23.1.2")
    implementation("org.graalvm.polyglot:java:23.1.2")

    // Bridge compilation helpers
    compileOnly("io.netty:netty-all:4.1.82.Final")
    compileOnly("com.mojang:brigadier:1.0.18")
    
    // GSON for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.mattwelke.packdependencies:packdependencies:0.2.0")
    
    // Mixin dependency
    compileOnly("org.spongepowered:mixin:0.8.5")
    
    // Null safety annotations
    compileOnly("org.jetbrains:annotations:24.0.1")

    // Testing dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

sourceSets {
    main {
        java {
            srcDir("src/main/java")
            srcDir("api")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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

// New task to create the fat "Core" jar
tasks.register<Jar>("coreJar") {
    archiveBaseName.set(archivesBaseName)
    archiveClassifier.set("") // No classifier for the main artifact
    
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "org.intermed.core.InterMedKernel", 
                "Agent-Class" to "org.intermed.core.InterMedKernel",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true"
            )
        )
    }
    
    // Bundle all dependencies
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        // Exclude signature files
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        // Exclude mod metadata that will be provided by the loader
        exclude("mods.toml", "fabric.mod.json")
    }
}
// Make the coreJar the default build artifact
tasks.named("build") {
    dependsOn("coreJar")
}
