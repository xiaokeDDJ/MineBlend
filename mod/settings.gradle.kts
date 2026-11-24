pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 多版本：一套源码，每个 Minecraft 版本一个构建节点（Stonecutter）。
// 1.21.11 及以前用 Mojang 官方命名并重映射；26.x 起 Minecraft 不再混淆，直接用官方命名。
// 源码以 1.20.1 为基准，新版本不同的地方用 //? if 条件注释区分。
plugins {
    id("dev.kikugie.stonecutter") version "0.10"
    id("dev.kikugie.loom-back-compat") version "0.4.3"
}

stonecutter {
    create(rootProject) {
        versions("1.20.1", "1.21.11", "26.3")
        vcsVersion = "1.20.1"
    }
}

rootProject.name = "mineblend"
