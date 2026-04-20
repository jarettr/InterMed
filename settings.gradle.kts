pluginManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.minecraftforge.net/")
        gradlePluginPortal()
    }
}

// Имя нашей глобальной платформы
rootProject.name = "InterMed"

// Подключаем модуль app (в будущем здесь появятся модули security, bridges и т.d.)
include("app")

// Custom Mixin fork — proper IMixinService + MixinExtras + @Local support
include("mixin-fork")

// Automated compatibility test harness (downloads MC + Forge/Fabric, tests top-N mods)
include("test-harness")