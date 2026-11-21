package com.karl.playertagger.mixin;

import com.karl.playertagger.data.TagManager;
import com.karl.playertagger.render.AlertManager;
import com.karl.playertagger.render.TabListHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(method = "runTick", at = @At("RETURN"))
    private void playertagger$onRunTickEnd(CallbackInfo ci) {
        TabListHandler.INSTANCE.onClientTick();
        AlertManager.INSTANCE.onClientTick();
    }

    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V", at = @At("HEAD"))
    private void playertagger$onLoadWorld(WorldClient worldClientIn, String loadingMessage, CallbackInfo ci) {
        TagManager.getInstance().invalidateLookupCache();
        TabListHandler.INSTANCE.onWorldChange();
        AlertManager.INSTANCE.onWorldChange(worldClientIn != null);
    }
}
