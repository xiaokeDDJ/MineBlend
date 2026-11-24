plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.20.1"

// 构建全部版本，jar 收集到 build/libs/
tasks.register("chiseledBuild") {
    group = "project"
    description = "构建所有 Minecraft 版本的 jar"
    dependsOn(stonecutter.tasks.names("buildAndCollect") { true }.map { it.values })
}

// 纯类名改名：源码写旧名字，各版本生成的源码里自动换成新名字（只影响生成的副本，不改 src/）
stonecutter parameters {
    replacements {
        // 1.21.11 起 ResourceLocation 改名为 Identifier
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
            // RenderType 移到了 rendertype 包
            replace("import net.minecraft.client.renderer.RenderType;", "import net.minecraft.client.renderer.rendertype.RenderType;")
        }
        // 26.1 起：类换包 / 改名
        string(current.parsed >= "26.1") {
            replace("import net.minecraft.world.level.BlockAndTintGetter;", "import net.minecraft.client.renderer.block.BlockAndTintGetter;")
            replace("net.fabricmc.fabric.api.renderer.v1.", "net.fabricmc.fabric.api.client.renderer.v1.")
            replace("import net.minecraft.client.renderer.LightTexture;", "import net.minecraft.util.LightCoordsUtil;")
            replace("LightTexture.FULL_BRIGHT", "LightCoordsUtil.FULL_BRIGHT")
            replace("net.minecraft.client.renderer.state.CameraRenderState", "net.minecraft.client.renderer.state.level.CameraRenderState")
            replace("net.minecraft.client.resources.model.WeightedVariants", "net.minecraft.client.renderer.block.dispatch.WeightedVariants")
            replace("ClientCommandManager", "ClientCommands")
        }
    }
}
