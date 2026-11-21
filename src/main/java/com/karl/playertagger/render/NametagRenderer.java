package com.karl.playertagger.render;

import com.karl.playertagger.config.PlayerTaggerConfig;
import com.karl.playertagger.data.TaggedPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Scoreboard;
import org.lwjgl.opengl.GL11;

/**
 * Argentum / OneClient-compatible nametag renderer with exact vanilla 1.8.9
 * visibility, sneaking depth-testing, and F5 camera orientation semantics.
 *
 * Key guarantees:
 * - Standing nametags are delegated to vanilla Render.renderOffsetLivingLabel /
 *   Render.renderLivingLabel from MixinRendererLivingEntity, giving full compatibility
 *   with Argentum's NameTagBatch + NameTagInstancing, Sarcio's F5 front-view pitch fix,
 *   PolyNametag styling, and below-name scoreboard objectives.
 * - Sneaking nametags keep GL_DEPTH_TEST enabled (no wall X-ray!), use raw
 *   RenderManager.playerViewY / playerViewX (no double pitch flip in F5 front view),
 *   draw backgrounds via Tessellator + DefaultVertexFormats.POSITION_COLOR (captured
 *   by Argentum's NameTagBatch with zero mid-batch flushes), keep GL_BLEND enabled for
 *   text draws, and restore every GL state inside a try/finally block.
 */
public class NametagRenderer {

    public static final NametagRenderer INSTANCE = new NametagRenderer();

    public static final float VANILLA_NAMETAG_SCALE = 0.016666668F * 1.6F;
    private static final float SNEAK_TRANSLATE_Y = 0.25F / VANILLA_NAMETAG_SCALE;

    public String resolvePrimaryLine(EntityPlayer player, TaggedPlayer tag) {
        int style = PlayerTaggerConfig.nametagStyle;
        boolean colorName = PlayerTaggerConfig.colorNametag;

        if (style == 2) {
            // "Nur Farbe"
            return colorName ? tag.getFormattedName() : player.getDisplayName().getFormattedText();
        }

        if (style == 1) {
            // "Nur Icon vor Name"
            return formatIconAndName(player, tag, colorName);
        }

        // style == 0: "Multi-Zeilen (Titel + Name)"
        if (PlayerTaggerConfig.showSubBadge && !tag.getFormattedBadge().isEmpty()) {
            return colorName ? tag.getFormattedName() : player.getDisplayName().getFormattedText();
        }

        return formatIconAndName(player, tag, colorName);
    }

    public String resolveBadgeLine(TaggedPlayer tag) {
        if (PlayerTaggerConfig.nametagStyle != 0 || !PlayerTaggerConfig.showSubBadge) {
            return "";
        }
        return tag.getFormattedBadge();
    }

    private String formatIconAndName(EntityPlayer player, TaggedPlayer tag, boolean colorName) {
        if (colorName) {
            return tag.getFormattedIconName();
        }
        String icon = tag.getIcon();
        String baseName = player.getDisplayName().getFormattedText();
        return icon.isEmpty() ? baseName : icon + " " + baseName;
    }

    public boolean hasBelowNameObjective(EntityPlayer player) {
        Scoreboard scoreboard = player.getWorldScoreboard();
        return scoreboard != null && scoreboard.getObjectiveInDisplaySlot(2) != null;
    }

    /**
     * Renders a sneaking player's nametag matching vanilla 1.8.9 RendererLivingEntity.renderName:
     * - Depth test stays ENABLED (depthMask(false) only) so sneaking tags cannot be X-rayed through walls.
     * - Raw rm.playerViewY / rm.playerViewX (no manual negation that would fight Sarcio's F5 fix).
     * - Backgrounds batched in a single Tessellator draw using DefaultVertexFormats.POSITION_COLOR so
     *   Argentum's NameTagBatch captures them without flushing.
     * - GL_BLEND remains enabled for FontRenderer.drawString so Argentum coalesces text passes cleanly.
     */
    public void renderSneakingNametag(
            RenderManager rm,
            FontRenderer font,
            EntityPlayer player,
            String primaryLine,
            String badgeLine,
            double x,
            double y,
            double z
    ) {
        if (rm == null || font == null) return;

        boolean hasBadge = badgeLine != null && !badgeLine.isEmpty();
        int primaryHalfWidth = font.getStringWidth(primaryLine) / 2;
        int badgeHalfWidth = hasBadge ? (font.getStringWidth(badgeLine) / 2) : 0;

        float yOffset = player.height + 0.5F - (player.isChild() ? player.height / 2.0F : 0.0F);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) x, (float) y + yOffset, (float) z);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
            GlStateManager.scale(-VANILLA_NAMETAG_SCALE, -VANILLA_NAMETAG_SCALE, VANILLA_NAMETAG_SCALE);
            GlStateManager.translate(0.0F, SNEAK_TRANSLATE_Y, 0.0F);

            GlStateManager.disableLighting();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

            GlStateManager.disableTexture2D();
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer wr = tessellator.getWorldRenderer();
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            addBackgroundQuad(wr, primaryHalfWidth, 0);
            if (hasBadge) {
                addBackgroundQuad(wr, badgeHalfWidth, -11);
            }
            tessellator.draw();
            GlStateManager.enableTexture2D();

            font.drawString(primaryLine, -primaryHalfWidth, 0, 0x20FFFFFF);
            if (hasBadge) {
                font.drawString(badgeLine, -badgeHalfWidth, -11, 0x20FFFFFF);
            }
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableLighting();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private void addBackgroundQuad(WorldRenderer wr, int halfWidth, int yTop) {
        wr.pos((double) (-halfWidth - 1), (double) (yTop - 1), 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        wr.pos((double) (-halfWidth - 1), (double) (yTop + 8), 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        wr.pos((double) (halfWidth + 1), (double) (yTop + 8), 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        wr.pos((double) (halfWidth + 1), (double) (yTop - 1), 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
    }
}
