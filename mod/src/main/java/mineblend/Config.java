package mineblend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/mineblend.json */
public class Config {
    /** Blender 插件连接的本地端口 */
    public int port = 25599;
    /** 监听地址：默认只允许本机连接；Blender 在另一台机器/虚拟机时可改为 "0.0.0.0" */
    public String bindAddress = "127.0.0.1";
    /** 除「选区棒」外额外当作选区工具的物品 id（留空 = 只用选区棒），例如 "minecraft:blaze_rod" */
    public String extraWandItem = "";
    /** 选区超过这个方块数时在游戏里提醒「选区过大」（只是提醒，不会阻止导入） */
    public long warnVolume = 8_000_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Config load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("mineblend.json");
        // 改名前（Mine2Blend）的配置文件：新文件不存在时沿用旧设置
        Path legacy = FabricLoader.getInstance().getConfigDir().resolve("mine2blend.json");
        Config c = new Config();
        try {
            Path src = Files.exists(p) ? p : Files.exists(legacy) ? legacy : null;
            if (src != null) {
                c = GSON.fromJson(Files.readString(src, StandardCharsets.UTF_8), Config.class);
                if (c == null) c = new Config();
            }
            Files.writeString(p, GSON.toJson(c), StandardCharsets.UTF_8);
        } catch (Exception e) {
            MineBlendClient.LOG.warn("[MineBlend] 读取配置失败，使用默认值", e);
        }
        return c;
    }
}
