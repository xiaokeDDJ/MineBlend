plugins {
    id("dev.kikugie.loom-back-compat")
}

/** 每个 Minecraft 版本节点的依赖与兼容范围 */
data class VersionDeps(val fabricApi: String, val java: Int, val minecraft: String)

val mcVersion: String = stonecutter.current.version
val deps = mapOf(
    "1.20.1" to VersionDeps("0.92.12+1.20.1", 17, "~1.20.1"),
    "1.21.11" to VersionDeps("0.141.6+1.21.11", 21, "~1.21.11"),
    "26.3" to VersionDeps("0.166.2+26.3", 25, ">=26.3 <26.4"),
)[mcVersion] ?: error("未配置的 Minecraft 版本 $mcVersion")

version = "${property("mod_version")}+mc$mcVersion"
group = property("maven_group") as String
base { archivesName.set(property("archives_base_name") as String) }

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${deps.fabricApi}")
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "minecraft" to deps.minecraft, "java" to deps.java.toString())
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

java {
    sourceCompatibility = JavaVersion.toVersion(deps.java)
    targetCompatibility = JavaVersion.toVersion(deps.java)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(deps.java)
}

// 构建好的 jar 统一收集到根目录 build/libs/
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(loomx.modJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs"))
    dependsOn("build")
}

// 本地开发测试用的额外设置（不随源码发布）
if (rootProject.file("dev.gradle").exists()) {
    apply(from = rootProject.file("dev.gradle"))
}
