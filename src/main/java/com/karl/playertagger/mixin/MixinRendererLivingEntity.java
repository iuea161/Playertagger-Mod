package com.karl.playertagger.mixin;

import com.karl.playertagger.config.PlayerTaggerConfig;
import com.karl.playertagger.data.TagManager;
import com.karl.playertagger.data.TaggedPlayer;
import com.karl.playertagger.render.NametagRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Priority 1100 ensures Argentum's celeritas$deferNameTag (EntityInstancing deferral + shadow pass skip)
// and Argentum Extras' hideNameTags (both default priority 1000) always run before PlayerTagger.
@Mixin(value = RendererLivingEntity.class, priority = 1100)
public abstract class MixinRendererLivingEntity<T extends EntityLivingBase> extends Render<T> {

    protected MixinRendererLivingEntity(RenderManager renderManagerIn) {
        super(renderManagerIn);
    }

    @Inject(method = "renderName(Lnet/minecraft/entity/EntityLivingBase;DDD)V", at = @At("HEAD"), cancellable = true)
    private void playertagger$onRenderName(T entity, double x, double y, double z, CallbackInfo ci) {
        if (!PlayerTaggerConfig.enabled || !PlayerTaggerConfig.nametagTagging) return;
        if (!(entity instanceof EntityPlayer)) return;

        TagManager tagManager = TagManager.getInstance();
        if (!tagManager.hasAnyTags()) return;

        EntityPlayer player = (EntityPlayer) entity;
        TaggedPlayer tag = tagManager.getTaggedPlayer(player.getName());
        if (tag == null) return;

        // Enforce vanilla visibility rules (invisibility, team visibility, F1 HUD hide, spectators, riders)
        if (!this.canRenderName(entity)) {
            ci.cancel();
            return;
        }

        // Enforce vanilla range check against the active render view entity using squared distance
        Entity viewer = this.renderManager != null ? this.renderManager.livingPlayer : null;
        if (viewer == null) {
            ci.cancel();
            return;
        }

        double distanceSq = entity.getDistanceSqToEntity(viewer);
        float maxDist = entity.isSneaking() ? 32.0F : 64.0F;
        if (distanceSq >= (double) (maxDist * maxDist)) {
            ci.cancel();
            return;
        }

        String primaryLine = NametagRenderer.INSTANCE.resolvePrimaryLine(player, tag);
        String badgeLine = NametagRenderer.INSTANCE.resolveBadgeLine(tag);

        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);

        if (entity.isSneaking()) {
            NametagRenderer.INSTANCE.renderSneakingNametag(
                this.renderManager,
                this.getFontRendererFromRenderManager(),
                player,
                primaryLine,
                badgeLine,
                x, y, z
            );
        } else {
            double baseY = y - (entity.isChild() ? (double) (entity.height / 2.0F) : 0.0D);
            this.renderOffsetLivingLabel(entity, x, baseY, z, primaryLine, NametagRenderer.VANILLA_NAMETAG_SCALE, distanceSq);

            if (!badgeLine.isEmpty()) {
                FontRenderer font = this.getFontRendererFromRenderManager();
                if (font != null) {
                    double lineStep = (double) ((float) font.FONT_HEIGHT * 1.15F * NametagRenderer.VANILLA_NAMETAG_SCALE);
                    double badgeY = baseY + lineStep;
                    if (distanceSq < 100.0D && NametagRenderer.INSTANCE.hasBelowNameObjective(player)) {
                        badgeY += lineStep;
                    }
                    this.renderLivingLabel(entity, badgeLine, x, badgeY, z, 64);
                }
            }
        }

        ci.cancel();
    }

    @Inject(method = "canRenderName(Lnet/minecraft/entity/EntityLivingBase;)Z", at = @At("HEAD"), cancellable = true)
    private void playertagger$onCanRenderName(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (!PlayerTaggerConfig.enabled || !PlayerTaggerConfig.nametagTagging) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (entity == mc.thePlayer
                && entity instanceof EntityPlayer
                && mc.gameSettings != null
                && mc.gameSettings.thirdPersonView > 0
                && Minecraft.isGuiEnabled()
                && !((EntityPlayer) entity).isSpectator()
                && entity.riddenByEntity == null) {
            if (TagManager.getInstance().getTaggedPlayer(entity.getName()) != null) {
                cir.setReturnValue(true);
            }
        }
    }
}
