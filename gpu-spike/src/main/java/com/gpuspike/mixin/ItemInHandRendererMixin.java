package com.gpuspike.mixin;

import com.gpuspike.GpuCubeScreen;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide the first-person hand while the cube test UI is open. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void gpuSpike$hideHands(float partialTick, PoseStack pose, SubmitNodeCollector collector,
                                    LocalPlayer player, int light, CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof GpuCubeScreen) {
            ci.cancel();
        }
    }
}
