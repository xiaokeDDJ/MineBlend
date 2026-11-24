package mineblend;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** 选区棒：左键 = 点1，右键 = 点2，可远距离选点（具体逻辑在客户端 Selection 中）。不能挖掘方块。 */
public class SelectionWandItem extends Item {
    public SelectionWandItem(Properties settings) {
        super(settings);
    }

    //? if <1.21.5 {
    @Override
    public boolean canAttackBlock(BlockState state, Level world, BlockPos pos, Player miner) {
        return false;
    }
    //?} else {
    /*@Override
    public boolean canDestroyBlock(ItemStack stack, BlockState state, Level world, BlockPos pos, net.minecraft.world.entity.LivingEntity miner) {
        return false;
    }
    *///?}

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    //? if <1.21 {
    public void appendHoverText(ItemStack stack, @Nullable Level world, List<Component> tooltip, TooltipFlag context) {
        tooltip.addAll(lines());
    }
    //?} else if <1.21.5 {
    /*public void appendHoverText(ItemStack stack, Item.TooltipContext world, List<Component> tooltip, TooltipFlag context) {
        tooltip.addAll(lines());
    }
    *///?} else {
    /*public void appendHoverText(ItemStack stack, Item.TooltipContext world, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltip, TooltipFlag context) {
        lines().forEach(tooltip);
    }
    *///?}

    private static List<Component> lines() {
        return List.of(
                Component.literal("左键：点1   右键：点2").withStyle(ChatFormatting.GRAY),
                Component.literal("对准远处方块也可以（最远 512 格）").withStyle(ChatFormatting.GRAY),
                Component.literal("Blender「读取游戏内选区」后即可导入").withStyle(ChatFormatting.DARK_AQUA));
    }
}
