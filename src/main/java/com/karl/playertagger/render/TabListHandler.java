package com.karl.playertagger.render;

import com.karl.playertagger.config.PlayerTaggerConfig;
import com.karl.playertagger.data.TagManager;
import com.karl.playertagger.data.TaggedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TabListHandler {

    public static final TabListHandler INSTANCE = new TabListHandler();

    private static final Set<String> modifiedPlayerNames = new HashSet<String>();
    private static final Map<String, IChatComponent> originalDisplayNames = new HashMap<String, IChatComponent>();
    private static final Map<String, IChatComponent> appliedDisplayNames = new HashMap<String, IChatComponent>();
    private static final Map<String, String> appliedDisplayTexts = new HashMap<String, String>();

    public void onWorldChange() {
        // Clear caches when switching servers, lobbies or loading a new game world
        originalDisplayNames.clear();
        appliedDisplayNames.clear();
        appliedDisplayTexts.clear();
        modifiedPlayerNames.clear();
    }

    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) return;

        boolean active = PlayerTaggerConfig.enabled && PlayerTaggerConfig.tabListTagging;
        TagManager tagManager = TagManager.getInstance();

        if (!active || !tagManager.hasAnyTags()) {
            if (!modifiedPlayerNames.isEmpty()) {
                resetAllModifiedNames();
            }
            return;
        }

        // Check every 5 ticks (0.25s) for instant response when players join/leave/update
        if (mc.theWorld.getTotalWorldTime() % 5 == 0) {
            updateTabDisplayNames(tagManager);
        }
    }

    private void updateTabDisplayNames(TagManager tagManager) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getNetHandler() == null) return;

            Collection<NetworkPlayerInfo> playerInfoMap = mc.getNetHandler().getPlayerInfoMap();
            if (playerInfoMap == null || playerInfoMap.isEmpty()) return;

            for (NetworkPlayerInfo info : playerInfoMap) {
                if (info == null || info.getGameProfile() == null) continue;
                String realName = info.getGameProfile().getName();
                if (realName == null) continue;

                TaggedPlayer tag = tagManager.getTaggedPlayer(realName);
                String lowerName = null;

                if (tag != null) {
                    lowerName = realName.toLowerCase(Locale.ROOT);
                    IChatComponent currentComp = info.getDisplayName();
                    IChatComponent lastApplied = appliedDisplayNames.get(lowerName);
                    IChatComponent origComp = originalDisplayNames.get(lowerName);

                    // If the displayName is still the exact component we set and no team/tag text changed,
                    // we only need to check whether the desired string still matches.
                    boolean serverReplacedName = currentComp != null && currentComp != lastApplied;
                    if (serverReplacedName) {
                        origComp = currentComp;
                        originalDisplayNames.put(lowerName, origComp);
                    }

                    String prefix = "";
                    String suffix = "";

                    if (origComp != null) {
                        String origFormatted = origComp.getFormattedText();
                        if (origFormatted != null && !origFormatted.isEmpty()) {
                            int nameIndex = origFormatted.toLowerCase(Locale.ROOT).indexOf(lowerName);
                            if (nameIndex != -1) {
                                prefix = origFormatted.substring(0, nameIndex);
                                suffix = origFormatted.substring(nameIndex + realName.length());
                            }
                        }
                    } else if (info.getPlayerTeam() != null) {
                        String formattedTeam = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), realName);
                        int nameIndex = formattedTeam.toLowerCase(Locale.ROOT).indexOf(lowerName);
                        if (nameIndex != -1) {
                            prefix = formattedTeam.substring(0, nameIndex);
                            suffix = formattedTeam.substring(nameIndex + realName.length());
                        }
                    }

                    StringBuilder formatted = new StringBuilder(64);
                    formatted.append(prefix);
                    formatted.append(tag.getTabPrefix());
                    formatted.append(tag.getColorCode()).append(realName).append("§r");
                    formatted.append(suffix);
                    formatted.append(tag.getFormattedTabSubInfo());

                    String formattedStr = formatted.toString();
                    String lastText = appliedDisplayTexts.get(lowerName);

                    if (currentComp != lastApplied || !formattedStr.equals(lastText)) {
                        IChatComponent newComp = new ChatComponentText(formattedStr);
                        info.setDisplayName(newComp);
                        appliedDisplayNames.put(lowerName, newComp);
                        appliedDisplayTexts.put(lowerName, formattedStr);
                        modifiedPlayerNames.add(lowerName);
                    }
                } else if (!modifiedPlayerNames.isEmpty()) {
                    lowerName = realName.toLowerCase(Locale.ROOT);
                    if (modifiedPlayerNames.remove(lowerName)) {
                        IChatComponent original = originalDisplayNames.remove(lowerName);
                        appliedDisplayNames.remove(lowerName);
                        appliedDisplayTexts.remove(lowerName);
                        info.setDisplayName(original);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static void resetPlayer(String username) {
        if (username == null) return;
        try {
            String lower = username.toLowerCase(Locale.ROOT);
            modifiedPlayerNames.remove(lower);
            appliedDisplayNames.remove(lower);
            appliedDisplayTexts.remove(lower);
            IChatComponent original = originalDisplayNames.remove(lower);

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getNetHandler() == null) return;

            Collection<NetworkPlayerInfo> playerInfoMap = mc.getNetHandler().getPlayerInfoMap();
            if (playerInfoMap != null) {
                for (NetworkPlayerInfo info : playerInfoMap) {
                    if (info != null && info.getGameProfile() != null && info.getGameProfile().getName() != null) {
                        if (info.getGameProfile().getName().equalsIgnoreCase(username)) {
                            info.setDisplayName(original);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void resetAllModifiedNames() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getNetHandler() == null) return;

            Collection<NetworkPlayerInfo> playerInfoMap = mc.getNetHandler().getPlayerInfoMap();
            if (playerInfoMap != null) {
                for (NetworkPlayerInfo info : playerInfoMap) {
                    if (info != null && info.getGameProfile() != null && info.getGameProfile().getName() != null) {
                        String lower = info.getGameProfile().getName().toLowerCase(Locale.ROOT);
                        if (modifiedPlayerNames.contains(lower)) {
                            IChatComponent original = originalDisplayNames.remove(lower);
                            info.setDisplayName(original);
                        }
                    }
                }
            }
            modifiedPlayerNames.clear();
            originalDisplayNames.clear();
            appliedDisplayNames.clear();
            appliedDisplayTexts.clear();
        } catch (Exception ignored) {}
    }
}
