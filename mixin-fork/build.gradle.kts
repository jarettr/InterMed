plugins {
    id("java")
}

group = "org.intermed"
version = "8.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

dependencies {
    // SpongePowered Mixin — provided at runtime by Minecraft/Forge/Fabric
    compileOnly("org.spongepowered:mixin:0.8.5")

    // MixinExtras — adds @Local, @WrapOperation, @ModifyExpressionValue, etc.
    // compileOnly here; the runtime JAR must be on the classpath (bundled by launchers)
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.0")

    // ASM for bytecode manipulation in the bytecode provider
    compileOnly("org.ow2.asm:asm:9.6")
    compileOnly("org.ow2.asm:asm-tree:9.6")

    // Guava (transitively available from Minecraft at runtime)
    compileOnly("com.google.guava:guava:32.1.2-jre")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}
