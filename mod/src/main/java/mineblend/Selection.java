package mineblend;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//? if <1.21.9 {
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?} else if <26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*///?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.shapes.Shapes;
*///?}
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if <1.21.2 {
import net.minecraft.world.InteractionResultHolder;
//?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 游戏内选区：手持选区工具，左键 = 点1，右键 = 点2，都是沿视线远距离选点（最远 512 格）；潜行右键 = 点1。 */
public final class Selection {
    public static volatile BlockPos pos1, pos2;
    public static volatile String dimension = "";
    private static long lastAttackMs;
    private static BlockPos lastAttackPos;

    private Selection() {}

    static boolean isWand(Player player, InteractionHand hand) {
        ItemStack s = player.getItemInHand(hand);
        if (s.isEmpty()) return false;
        if (s.is(MineBlend.SELECTION_WAND)) return true;
        String extra = MineBlendClient.CONFIG.extraWandItem;
        return extra != null && !extra.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(extra);
    }

    public static synchronized void set(int which, BlockPos pos, Level world, boolean announce) {
        String dim = Mc.dimensionId(world);
        if (!dim.equals(dimension)) {
            // 换了维度，旧选区作废
            pos1 = null;
            pos2 = null;
            dimension = dim;
        }
        if (which == 1) pos1 = pos.immutable();
        else pos2 = pos.immutable();
        if (announce) announce(which);
    }

    public static synchronized void setBoth(BlockPos a, BlockPos b, String dim) {
        pos1 = a.immutable();
        pos2 = b.immutable();
        dimension = dim;
    }

    public static synchronized void clear() {
        pos1 = null;
        pos2 = null;
    }

    static void announce(int which) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        BlockPos p = which == 1 ? pos1 : pos2;
        String msg = String.format("§6[MineBlend]§r 点%d: §b%d %d %d", which, p.getX(), p.getY(), p.getZ());
        long vol = 0;
        if (pos1 != null && pos2 != null) {
            int[] s = size();
            vol = (long) s[0] * s[1] * s[2];
            msg += String.format("  §7选区 %d×%d×%d = %,d 方块", s[0], s[1], s[2], vol);
        }
        Mc.chat(mc.player, Component.literal(msg));
        if (vol > MineBlendClient.CONFIG.warnVolume) {
            Mc.chat(mc.player, Component.literal(String.format(
                    "§e[MineBlend] ⚠ 导入选区过大（%,d 方块），导入可能很慢并占用大量内存，请注意", vol)));
        }
    }

    public static int[] size() {
        BlockPos a = pos1, b = pos2;
        if (a == null || b == null) return new int[]{0, 0, 0};
        return new int[]{
                Math.abs(a.getX() - b.getX()) + 1,
                Math.abs(a.getY() - b.getY()) + 1,
                Math.abs(a.getZ() - b.getZ()) + 1};
    }

    static final double REACH = 512;

    /** 沿视线找方块（与右键相同的远距离选点）；没找到返回 null 并在快捷栏上方提示 */
    static BlockPos aimed(Minecraft mc) {
        HitResult hr = mc.player.pick(REACH, Mc.partialTick(), false);
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) return bhr.getBlockPos();
        Mc.actionBar(mc.player, Component.literal("§6[MineBlend]§r 视线 " + (int) REACH + " 格内没有方块"));
        return null;
    }

    /** 左键（MinecraftClient.doAttack 开头调用）：手持选区棒时远距离设点1，返回 true 表示已处理、取消原本的攻击 */
    public static boolean onWandAttack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !isWand(mc.player, InteractionHand.MAIN_HAND)) return false;
        long now = System.currentTimeMillis();
        BlockPos pos = aimed(mc);
        // 按住左键时有些情况会连续触发，做个去抖
        if (pos != null && (!pos.equals(lastAttackPos) || now - lastAttackMs > 400)) {
            set(1, pos, mc.level, true);
            //? if <26.3 {
            mc.player.swing(InteractionHand.MAIN_HAND);
            //?}
        }
        lastAttackMs = now;
        lastAttackPos = pos;
        return true;
    }

    /** 右键（Minecraft.startUseItem 开头调用）：手持选区棒时远距离设点2（潜行时点1），返回 true 表示已处理、取消原本的使用 */
    public static boolean onWandUse() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !isWand(mc.player, InteractionHand.MAIN_HAND)) return false;
        BlockPos pos = aimed(mc);
        if (pos != null) set(mc.player.isShiftKeyDown() ? 1 : 2, pos, mc.level, true);
        return true;
    }

    static void init() {
        // 左右键选点由 MinecraftClientMixin 处理；这里只保证手持选区棒时不会挖掘、放置或与方块交互
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
                isWand(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS);

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!isWand(player, hand)) return InteractionResult.PASS;
            return InteractionResult.FAIL; // 取消放置/交互，且不发包给服务器
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            //? if <1.21.2 {
            if (!isWand(player, hand)) return InteractionResultHolder.pass(stack);
            //?} else {
            /*if (!isWand(player, hand)) return InteractionResult.PASS;
            *///?}
            //? if <1.21.2 {
            return InteractionResultHolder.fail(stack);
            //?} else {
            /*return InteractionResult.FAIL;
            *///?}
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("mb")
                        .then(ClientCommandManager.literal("pos1").executes(ctx -> {
                            var p = ctx.getSource().getPlayer();
                            set(1, p.blockPosition(), p.level(), true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("pos2").executes(ctx -> {
                            var p = ctx.getSource().getPlayer();
                            set(2, p.blockPosition(), p.level(), true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            clear();
                            ctx.getSource().sendFeedback(Component.literal("[MineBlend] 选区已清除").withStyle(ChatFormatting.GOLD));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("expand")
                                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(-1024, 1024)).executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                    BlockPos a = pos1, b = pos2;
                                    if (a == null || b == null) {
                                        ctx.getSource().sendError(Component.literal("[MineBlend] 请先选好两个点"));
                                        return 0;
                                    }
                                    BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()) - n, Math.min(a.getY(), b.getY()) - n, Math.min(a.getZ(), b.getZ()) - n);
                                    BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()) + n, Math.max(a.getY(), b.getY()) + n, Math.max(a.getZ(), b.getZ()) + n);
                                    setBoth(lo, hi, dimension);
                                    announce(2);
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("wand").executes(ctx -> giveWand()))
                        .then(ClientCommandManager.literal("info").executes(ctx -> {
                            String wand = "选区棒" + (MineBlendClient.CONFIG.extraWandItem.isEmpty() ? "" : " / " + MineBlendClient.CONFIG.extraWandItem);
                            ctx.getSource().sendFeedback(Component.literal(String.format(
                                    "§6[MineBlend]§r 端口 %d  %s  选区工具: %s\n点1=%s 点2=%s",
                                    MineBlendClient.CONFIG.port,
                                    BridgeServer.isRunning() ? "§a接口运行中§r" : "§c接口未运行§r",
                                    wand, fmt(pos1), fmt(pos2))));
                            return 1;
                        }))
        ));

        //? if <1.21.9 {
        WorldRenderEvents.LAST.register(ctx -> {
            PoseStack ms = ctx.matrixStack();
            Vec3 cam = ctx.camera().getPosition();
            MultiBufferSource.BufferSource imm = Minecraft.getInstance().renderBuffers().bufferSource();
            VertexConsumer vc = imm.getBuffer(Mc.lines());
            drawSelection(ms, cam, (box, r, g, b, a) -> Mc.lineBox(ms, vc, box, r, g, b, a));
            imm.endBatch(Mc.lines());
        });
        //?} else if <26.1 {
        /*WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ctx -> {
            if (pos1 == null && pos2 == null) return;
            // 这个事件里 consumers() 可能为空：那就用游戏的主缓冲，画完立即提交
            MultiBufferSource.BufferSource own = ctx.consumers() == null ? Minecraft.getInstance().renderBuffers().bufferSource() : null;
            MultiBufferSource buffers = own != null ? own : ctx.consumers();
            VertexConsumer vc = buffers.getBuffer(Mc.lines());
            drawSelection(ctx.matrices(), Minecraft.getInstance().gameRenderer.getMainCamera().position(),
                    (box, r, g, b, a) -> Mc.lineBox(ctx.matrices(), vc, box, r, g, b, a));
            if (own != null) own.endBatch(Mc.lines());
        });
        *///?} else {
        /*LevelRenderEvents.COLLECT_SUBMITS.register(ctx ->
                drawSelection(ctx.poseStack(), Minecraft.getInstance().gameRenderer.mainCamera().position(),
                        (box, r, g, b, a) -> ctx.submitNodeCollector().submitShapeOutline(ctx.poseStack(), Shapes.create(box),
                                RenderTypes.lines(), Mc.argb(r, g, b, a), 2f, false)));
        *///?}
    }

    /** 画一个线框盒（各版本画法不同） */
    interface BoxSink {
        void box(AABB box, float r, float g, float b, float a);
    }

    /** 在游戏里画出选区线框（橙色）和两个点（红、蓝） */
    private static void drawSelection(PoseStack ms, Vec3 cam, BoxSink sink) {
        {
            BlockPos a = pos1, b = pos2;
            if (a == null && b == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !Mc.dimensionId(mc.level).equals(dimension)) return;
            if (ms == null || cam == null) return;
            ms.pushPose();
            ms.translate(-cam.x, -cam.y, -cam.z);
            if (a != null && b != null) {
                AABB box = new AABB(
                        Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1)
                        .inflate(0.01);
                sink.box(box, 1f, 0.75f, 0.1f, 1f);
            }
            if (a != null) sink.box(new AABB(a).inflate(0.02), 1f, 0.2f, 0.2f, 1f);
            if (b != null) sink.box(new AABB(b).inflate(0.02), 0.2f, 0.5f, 1f, 1f);
            ms.popPose();
        }
    }

    /** 单人游戏：在集成服务器上直接给予；多人创造模式：通过创造物品栏放入当前手持格 */
    static int giveWand() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        var srv = mc.getSingleplayerServer();
        if (srv != null) {
            java.util.UUID id = mc.player.getUUID();
            srv.execute(() -> {
                var sp = srv.getPlayerList().getPlayer(id);
                if (sp != null) sp.addItem(new ItemStack(MineBlend.SELECTION_WAND));
            });
        } else if (mc.player.getAbilities().instabuild && mc.gameMode != null) {
            //? if <1.21.5 {
            int slot = mc.player.getInventory().selected;
            //?} else {
            /*int slot = mc.player.getInventory().getSelectedSlot();
            *///?}
            ItemStack st = new ItemStack(MineBlend.SELECTION_WAND);
            mc.player.getInventory().setItem(slot, st);
            mc.gameMode.handleCreativeModeItemAdd(st, 36 + slot);
        } else {
            Mc.chat(mc.player, Component.literal("§c[MineBlend] 多人生存模式下无法直接获取，请在创造物品栏「工具」页找到选区棒"));
            return 0;
        }
        Mc.chat(mc.player, Component.literal("§6[MineBlend]§r 已获得 §b选区棒§r：左键=点1，右键=点2（对准远处方块也可以）"));
        return 1;
    }

    static String fmt(BlockPos p) {
        return p == null ? "无" : p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
