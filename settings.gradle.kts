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