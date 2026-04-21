plugins {
    id("java")
    id("application")
}

group = "org.intermed"
version = providers.gradleProperty("intermedVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    // JSON parsing (Modrinth API responses, config files)
    implementation("com.google.code.gson:gson:2.10.1")

    // JUnit for harness self-tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("org.intermed.harness.HarnessMain")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Fat JAR so users can run it standalone with a single java -jar command
tasks.register<Jar>("harnessJar") {
    archiveBaseName.set("intermed-test-harness")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            mapOf(
                "Main-Class" to "org.intermed.harness.HarnessMain",
                "Implementation-Version" to version
            )
        )
    }

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.named("build") {
    dependsOn("harnessJar")
}
