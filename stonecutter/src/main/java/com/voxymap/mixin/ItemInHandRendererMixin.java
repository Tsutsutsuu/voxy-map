package com.voxymap.mixin;

import com.voxymap.MapScreen;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide first-person hands while the GPU 3D map owns the screen. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(
            //? if >=26.2 {
            method = "submitHandsWithItems",
            //?} else {
            /*method = "renderHandsWithItems",*/
            //?}
            at = @At("HEAD"), cancellable = true)
    private void voxymap$hideHandsIn3d(float partialTick, PoseStack pose, SubmitNodeCollector collector,
                                       LocalPlayer player, int light, CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof MapScreen map && map.isGpu3dView()) {
            ci.cancel();
        }
    }
}
