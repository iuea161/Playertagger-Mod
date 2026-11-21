package com.karl.playertagger.data;

public class TaggedPlayer {
    private String name;
    private String uuid;
    private String gameMode;   // BedWars, SkyWars, Duels, UHC, etc.
    private String customInfo;  // 3.4 Reach, Fireball God, etc.
    private String colorCode;   // e.g. "§c", "§6", "§d", "§b", "§a", "§e", "§7"
    private String icon;        // e.g. "☠", "⚡", "☄", "⚔", "🛡", "🎯"
    private String category;    // "Cheater", "Sweat", "BedWars", "Custom", etc.
    private int threatLevel;    // 1 to 5
    private long addedTime;
    private boolean isShared;   // true if synced from cloud

    private transient String cachedBadge;
    private transient String cachedFormattedName;
    private transient String cachedFormattedIconName;
    private transient String cachedTabPrefix;
    private transient String cachedTabSubInfo;

    public TaggedPlayer() {}

    public TaggedPlayer(String name, String gameMode, String customInfo, String colorCode, String icon, String category, int threatLevel, boolean isShared) {
        this.name = name;
        this.gameMode = gameMode == null ? "" : gameMode.trim();
        this.customInfo = customInfo == null ? "" : customInfo.trim();
        this.colorCode = (colorCode == null || colorCode.isEmpty()) ? "§c" : colorCode;
        this.icon = icon == null ? "" : icon.trim();
        this.category = (category == null || category.isEmpty()) ? "Custom" : category;
        this.threatLevel = threatLevel < 1 ? 1 : Math.min(threatLevel, 5);
        this.addedTime = System.currentTimeMillis();
        this.isShared = isShared;
    }

    private void invalidateCache() {
        this.cachedBadge = null;
        this.cachedFormattedName = null;
        this.cachedFormattedIconName = null;
        this.cachedTabPrefix = null;
        this.cachedTabSubInfo = null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        invalidateCache();
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getGameMode() {
        return gameMode == null ? "" : gameMode;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode == null ? "" : gameMode.trim();
        invalidateCache();
    }

    public String getCustomInfo() {
        return customInfo == null ? "" : customInfo;
    }

    public void setCustomInfo(String customInfo) {
        this.customInfo = customInfo == null ? "" : customInfo.trim();
        invalidateCache();
    }

    public String getColorCode() {
        return colorCode == null ? "§c" : colorCode;
    }

    public void setColorCode(String colorCode) {
        this.colorCode = colorCode;
        invalidateCache();
    }

    public String getIcon() {
        return icon == null ? "" : icon;
    }

    public void setIcon(String icon) {
        this.icon = icon == null ? "" : icon.trim();
        invalidateCache();
    }

    public String getCategory() {
        return category == null ? "Custom" : category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getThreatLevel() {
        return threatLevel;
    }

    public void setThreatLevel(int threatLevel) {
        this.threatLevel = threatLevel;
    }

    public long getAddedTime() {
        return addedTime;
    }

    public void setAddedTime(long addedTime) {
        this.addedTime = addedTime;
    }

    public boolean isShared() {
        return isShared;
    }

    public void setShared(boolean shared) {
        isShared = shared;
    }

    /**
     * Builds (and caches) the overhead sub-badge line.
     * e.g. "§c[☠ BedWars • 3.4 Reach]§r"
     */
    public String getFormattedBadge() {
        String cached = this.cachedBadge;
        if (cached != null) return cached;

        String color = getColorCode();
        String trimmedMode = gameMode == null ? "" : gameMode.trim();
        String trimmedInfo = customInfo == null ? "" : customInfo.trim();
        String trimmedIcon = icon == null ? "" : icon.trim();

        StringBuilder content = new StringBuilder();
        if (!trimmedMode.isEmpty()) {
            content.append(trimmedMode);
        }
        if (!trimmedInfo.isEmpty()) {
            if (content.length() > 0) {
                content.append(" • ");
            }
            content.append(trimmedInfo);
        }

        String result;
        if (content.length() == 0) {
            result = trimmedIcon.isEmpty() ? "" : color + "[" + trimmedIcon + "]§r";
        } else {
            StringBuilder sb = new StringBuilder(content.length() + 16);
            sb.append(color).append("[");
            if (!trimmedIcon.isEmpty()) {
                sb.append(trimmedIcon).append(" ");
            }
            sb.append(content).append("]§r");
            result = sb.toString();
        }

        this.cachedBadge = result;
        return result;
    }

    /**
     * Formats (and caches) player name in their chosen color.
     */
    public String getFormattedName() {
        String cached = this.cachedFormattedName;
        if (cached != null) return cached;

        String result = getColorCode() + name + "§r";
        this.cachedFormattedName = result;
        return result;
    }

    /**
     * Formats (and caches) icon + colored player name for single-line nametag modes.
     */
    public String getFormattedIconName() {
        String cached = this.cachedFormattedIconName;
        if (cached != null) return cached;

        String trimmedIcon = icon == null ? "" : icon.trim();
        String result = trimmedIcon.isEmpty()
                ? getFormattedName()
                : getColorCode() + trimmedIcon + " " + name + "§r";
        this.cachedFormattedIconName = result;
        return result;
    }

    /**
     * Prefix for Tab-List.
     */
    public String getTabPrefix() {
        String cached = this.cachedTabPrefix;
        if (cached != null) return cached;

        String trimmedIcon = icon == null ? "" : icon.trim();
        String result = trimmedIcon.isEmpty()
                ? getColorCode()
                : getColorCode() + "[" + trimmedIcon + "] ";
        this.cachedTabPrefix = result;
        return result;
    }

    /**
     * Cached extra tab-list suffix (e.g. " §7[§cBedWars • 3.4 Reach§7]§r") or empty string.
     */
    public String getFormattedTabSubInfo() {
        String cached = this.cachedTabSubInfo;
        if (cached != null) return cached;

        String trimmedMode = getGameMode();
        String trimmedInfo = getCustomInfo();
        String result;
        if (trimmedMode.isEmpty() && trimmedInfo.isEmpty()) {
            result = "";
        } else {
            StringBuilder sb = new StringBuilder(32);
            sb.append(" §7[").append(getColorCode());
            if (!trimmedMode.isEmpty()) {
                sb.append(trimmedMode);
            }
            if (!trimmedInfo.isEmpty()) {
                if (!trimmedMode.isEmpty()) sb.append(" • ");
                sb.append(trimmedInfo);
            }
            sb.append("§7]§r");
            result = sb.toString();
        }

        this.cachedTabSubInfo = result;
        return result;
    }
}
