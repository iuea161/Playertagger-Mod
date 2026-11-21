package com.karl.playertagger.render;

import com.karl.playertagger.config.PlayerTaggerConfig;
import com.karl.playertagger.data.TagManager;
import com.karl.playertagger.data.TaggedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AlertManager {

    public static final AlertManager INSTANCE = new AlertManager();

    private final Set<String> alertedPlayers = new HashSet<String>();
    private String activeAlertMessage = null;
    private long alertExpireTime = 0;
    private int checkCooldown = 0;

    public void onWorldChange(boolean isNewWorldLoaded) {
        alertedPlayers.clear();
        activeAlertMessage = null;

        if (isNewWorldLoaded && PlayerTaggerConfig.enabled && PlayerTaggerConfig.firebaseEnabled) {
            // Rejoin / world join: Auto-sync Firebase list immediately in background (rate-limited to 10s)
            TagManager.getInstance().syncFromFirebase(false);
        }
    }

    public void onClientTick() {
        if (!PlayerTaggerConfig.enabled) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;

        TagManager tagManager = TagManager.getInstance();

        try {
            checkCooldown++;
            if (checkCooldown >= 20) { // Check once per second
                checkCooldown = 0;

                // 10-Minute Auto-Sync Check (Independent of whether we already have tags cached)
                if (PlayerTaggerConfig.firebaseEnabled) {
                    long now = System.currentTimeMillis();
                    long intervalMs = (long) (PlayerTaggerConfig.syncIntervalMinutes * 60 * 1000);
                    if (now - tagManager.getLastSyncTime() >= intervalMs) {
                        tagManager.syncFromFirebase(false);
                    }
                }

                // Scan players (Only if alerts are enabled and we have tags)
                if (PlayerTaggerConfig.alertsEnabled && tagManager.hasAnyTags()) {
                    List<EntityPlayer> playerList = mc.theWorld.playerEntities;
                    if (playerList != null) {
                        for (int i = 0; i < playerList.size(); i++) {
                            EntityPlayer player = playerList.get(i);
                            if (player != null && player.getName() != null) {
                                checkAndTriggerAlert(player.getName(), tagManager);
                            }
                        }
                    }

                    if (mc.getNetHandler() != null) {
                        Collection<NetworkPlayerInfo> playerInfoMap = mc.getNetHandler().getPlayerInfoMap();
                        if (playerInfoMap != null && !playerInfoMap.isEmpty()) {
                            for (NetworkPlayerInfo info : playerInfoMap) {
                                if (info != null && info.getGameProfile() != null && info.getGameProfile().getName() != null) {
                                    checkAndTriggerAlert(info.getGameProfile().getName(), tagManager);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void checkAndTriggerAlert(String name, TagManager tagManager) {
        try {
            if (name == null || name.isEmpty()) return;
            TaggedPlayer tag = tagManager.getTaggedPlayer(name);
            if (tag == null) return;

            String clean = name.toLowerCase(Locale.ROOT);
            if (alertedPlayers.add(clean)) {
                showAlert(tag);
            }
        } catch (Exception ignored) {}
    }

    private void showAlert(TaggedPlayer tag) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return;

            if (PlayerTaggerConfig.alertSound) {
                try {
                    mc.thePlayer.playSound("random.orb", 1.0F, 0.6F);
                } catch (Exception ignored) {}
            }

            StringBuilder infoText = new StringBuilder(32);
            if (!tag.getGameMode().isEmpty()) {
                infoText.append(tag.getGameMode());
            }
            if (!tag.getCustomInfo().isEmpty()) {
                if (infoText.length() > 0) infoText.append(" • ");
                infoText.append(tag.getCustomInfo());
            }
            if (infoText.length() == 0) {
                infoText.append(tag.getCategory());
            }

            this.activeAlertMessage = "§c§l⚠️ WARNUNG: " + tag.getColorCode() + tag.getName() + " §7(" + infoText + ") §fist in der Runde!";
            this.alertExpireTime = System.currentTimeMillis() + 5000;

            mc.thePlayer.addChatMessage(new ChatComponentText(activeAlertMessage));
        } catch (Exception ignored) {}
    }

    public void onRenderOverlay() {
        if (!PlayerTaggerConfig.enabled || !PlayerTaggerConfig.alertsEnabled) return;
        if (activeAlertMessage == null || System.currentTimeMillis() > alertExpireTime) return;

        try {
            Minecraft mc = Minecraft.getMinecraft();
            FontRenderer font = mc.fontRendererObj;
            if (font == null) return;

            ScaledResolution sr = new ScaledResolution(mc);
            int screenWidth = sr.getScaledWidth();
            int textWidth = font.getStringWidth(activeAlertMessage);
            int x = (screenWidth - textWidth) / 2;
            int y = 35;

            // Banner background
            Gui.drawRect(x - 8, y - 4, x + textWidth + 8, y + 13, 0xCC000000);
            Gui.drawRect(x - 8, y - 4, x + textWidth + 8, y - 3, 0xFFFF5555);

            font.drawString(activeAlertMessage, x, y, 0xFFFFFF);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Exception ignored) {}
    }
}
