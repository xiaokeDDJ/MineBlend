package mineblend;

import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTTP 服务（只监听 127.0.0.1），不依赖 jdk.httpserver 模块，避免启动器自带的精简 JRE 缺模块。
 *
 * GET /status                         游戏状态、玩家位置
 * GET /selection                      游戏内选区
 * GET /selection/set?x1&y1&z1&x2&y2&z2  从 Blender 设置游戏内选区
 * GET /topview?x1&z1&x2&z2&step&ppb  游戏俯视地图（PNG，Blender 中框选用）
 * GET /columns?x1&z1&x2&z2&step&ppb  选区内每列的方块段（Blender 地图窗口实时计算高度剖面用）
 * GET /export?x1&y1&z1&x2&y2&z2&plants&fluids&blockEntities&entities&mobs&cull
 *                                     导出带贴图的网格（二进制：int32 头长度 + JSON 头 + 各数据段 + PNG 贴图）
 */
public final class BridgeServer {
    private static volatile boolean running;

    private BridgeServer() {}

    public static boolean isRunning() {
        return running;
    }

    public static void start(int port) {
        Thread t = new Thread(() -> loop(port), "MineBlend-HTTP");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(int port) {
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread th = new Thread(r, "MineBlend-Worker");
            th.setDaemon(true);
            return th;
        });
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            String bind = MineBlendClient.CONFIG.bindAddress;
            ss.bind(bind == null || bind.isBlank() || bind.equals("127.0.0.1")
                    ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                    : new InetSocketAddress(InetAddress.getByName(bind), port));
            running = true;
            MineBlendClient.LOG.info("[MineBlend] 本地接口已启动，端口 {}", port);
            if (!ss.getInetAddress().isLoopbackAddress())
                MineBlendClient.LOG.warn("[MineBlend] 接口监听在非本机地址，局域网内的其他设备也能读取你的世界数据；不需要时请把 config/mineblend.json 的 bindAddress 改回 127.0.0.1");
            while (true) {
                Socket s = ss.accept();
                pool.submit(() -> handle(s));
            }
        } catch (IOException e) {
            running = false;
            MineBlendClient.LOG.error("[MineBlend] 无法监听端口 {}（被占用？可在 config/mineblend.json 修改）", port, e);
        }
    }

    record Response(int status, String contentType, byte[] body) {
        static Response json(int status, JsonObject o) {
            return new Response(status, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Response json(JsonObject o) {
            return json(200, o);
        }

        static Response binary(byte[] b) {
            return new Response(200, "application/octet-stream", b);
        }
    }

    /** 业务错误（参数错误、不在世界中等） */
    public static class ApiException extends RuntimeException {
        public ApiException(String msg) {
            super(msg);
        }
    }

    private static void handle(Socket s) {
        try (s) {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            int nHeaders = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (++nHeaders > 100) return;
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(java.util.Locale.ROOT), line.substring(c + 1).trim());
            }
            String[] parts = requestLine.split(" ");
            String reject = checkRequest(parts.length > 0 ? parts[0] : "", headers, s.getLocalPort());
            if (reject != null) {
                MineBlendClient.LOG.warn("[MineBlend] 拒绝请求：{}", reject);
                byte[] body = ("{\"ok\":false,\"error\":\"forbidden\"}").getBytes(StandardCharsets.UTF_8);
                OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                return;
            }
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int eq = kv.indexOf('=');
                    String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
                    String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
                    q.put(k, v);
                }
            }

            Response r;
            try {
                r = route(path, q);
            } catch (ApiException e) {
                JsonObject o = new JsonObject();
                o.addProperty("ok", false);
                o.addProperty("error", e.getMessage());
                r = Response.json(400, o);
            } catch (Throwable e) {
                MineBlendClient.LOG.error("[MineBlend] 请求处理失败: {}", target, e);
                JsonObject o = new JsonObject();
                o.addProperty("ok", false);
                o.addProperty("error", isOutOfMemory(e)
                        ? String.format("游戏内存不足（当前最多 %d MB），这个选区太大了。请缩小选区、分几次导入，或在启动器里给游戏分配更多内存",
                                Runtime.getRuntime().maxMemory() >> 20)
                        : e.getClass().getSimpleName() + ": " + e.getMessage());
                r = Response.json(500, o);
            }

            OutputStream out = s.getOutputStream();
            String head = "HTTP/1.1 " + r.status() + (r.status() == 200 ? " OK" : " Error") + "\r\n"
                    + "Content-Type: " + r.contentType() + "\r\n"
                    + "Content-Length: " + r.body().length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(r.body());
            out.flush();
        } catch (IOException e) {
            MineBlendClient.LOG.debug("[MineBlend] 连接异常", e);
        }
    }

    /**
     * 安全检查：只接受 Blender 插件这类本地程序的请求。
     * - 只允许 GET
     * - 浏览器发出的请求一律拒绝（带 Origin / Referer / Sec-Fetch-* 头），防止网页偷偷访问本机接口
     * - 监听本机时 Host 必须是 127.0.0.1 / localhost / [::1]，防止 DNS 重绑定攻击
     * 返回 null 表示通过，否则返回拒绝原因。
     */
    static String checkRequest(String method, Map<String, String> headers, int port) {
        if (!"GET".equals(method)) return "非 GET 请求";
        if (headers.containsKey("origin") || headers.containsKey("referer")) return "来自浏览器（Origin/Referer）";
        for (String k : headers.keySet()) if (k.startsWith("sec-fetch-")) return "来自浏览器（Sec-Fetch）";
        String bind = MineBlendClient.CONFIG.bindAddress;
        boolean loopback = bind == null || bind.isBlank() || bind.equals("127.0.0.1") || bind.equals("localhost") || bind.equals("::1");
        if (loopback) {
            String host = headers.getOrDefault("host", "").toLowerCase(java.util.Locale.ROOT);
            String p = ":" + port;
            if (!(host.equals("127.0.0.1" + p) || host.equals("localhost" + p) || host.equals("[::1]" + p)
                    || host.equals("127.0.0.1") || host.equals("localhost")))
                return "Host 不是本机地址：" + host;
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("请求行过长");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.UTF_8);
    }

    /** 异常链里有没有内存不足（世界线程上的错误会被包一层再抛出来） */
    static boolean isOutOfMemory(Throwable e) {
        for (int i = 0; e != null && i < 10; i++, e = e.getCause()) {
            if (e instanceof OutOfMemoryError) return true;
        }
        return false;
    }

    static int intArg(Map<String, String> q, String k) {
        String v = q.get(k);
        if (v == null) throw new ApiException("缺少参数 " + k);
        try {
            return (int) Math.floor(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            throw new ApiException("参数 " + k + " 不是数字: " + v);
        }
    }

    static int intArg(Map<String, String> q, String k, int def) {
        return q.containsKey(k) ? intArg(q, k) : def;
    }

    private static Response route(String path, Map<String, String> q) throws Exception {
        switch (path) {
            case "/", "/status":
                return Response.json(WorldReader.status());
            case "/selection":
                return Response.json(WorldReader.selection());
            case "/selection/set":
                return Response.json(WorldReader.setSelection(
                        intArg(q, "x1"), intArg(q, "y1"), intArg(q, "z1"),
                        intArg(q, "x2"), intArg(q, "y2"), intArg(q, "z2")));
            case "/topview":
                return Response.binary(WorldReader.topview(
                        intArg(q, "x1"), intArg(q, "z1"), intArg(q, "x2"), intArg(q, "z2"),
                        Math.max(1, intArg(q, "step", 1)), intArg(q, "ppb", 4)));
            case "/columns":
                return Response.binary(WorldReader.columns(
                        intArg(q, "x1"), intArg(q, "z1"), intArg(q, "x2"), intArg(q, "z2"),
                        Math.max(1, intArg(q, "step", 1)), intArg(q, "ppb", 8)));
            case "/export":
                return Response.binary(WorldReader.export(
                        intArg(q, "x1"), intArg(q, "y1"), intArg(q, "z1"),
                        intArg(q, "x2"), intArg(q, "y2"), intArg(q, "z2"),
                        new BlockMesher.Opts(
                                intArg(q, "plants", 1) != 0, intArg(q, "fluids", 1) != 0,
                                intArg(q, "blockEntities", 1) != 0, intArg(q, "entities", 1) != 0,
                                intArg(q, "mobs", 0) != 0, intArg(q, "cull", 0) != 0,
                                intArg(q, "inner", 1) != 0)));
            default:
                throw new ApiException("未知接口 " + path);
        }
    }
}
