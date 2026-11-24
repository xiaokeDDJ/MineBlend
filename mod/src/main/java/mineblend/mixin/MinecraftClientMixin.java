package mineblend.mixin;

import mineblend.Selection;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 手持选区棒：左键沿视线远距离选点 1、右键选点 2（潜行时点 1），并取消原本的攻击/挖掘/使用。 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void mineblend$wandAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Selection.onWandAttack()) cir.setReturnValue(true);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void mineblend$wandUse(CallbackInfo ci) {
        if (Selection.onWandUse()) ci.cancel();
    }
}
