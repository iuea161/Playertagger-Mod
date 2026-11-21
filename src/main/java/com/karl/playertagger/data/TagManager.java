package com.karl.playertagger.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.karl.playertagger.config.PlayerTaggerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class TagManager {

    private static final TagManager INSTANCE = new TagManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Daemon thread pool so it never blocks or slows down Minecraft shutdown/performance
    private static final ExecutorService ASYNC_POOL = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "PlayerTagger-AsyncPool");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    private static final TaggedPlayer NO_TAG_SENTINEL = new TaggedPlayer();
    private static final int MAX_LOOKUP_CACHE_SIZE = 1024;

    private final Map<String, TaggedPlayer> localTags = new ConcurrentHashMap<String, TaggedPlayer>();
    private final Map<String, TaggedPlayer> remoteTags = new ConcurrentHashMap<String, TaggedPlayer>();
    private final Set<String> localHiddenPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, TaggedPlayer> exactLookupCache = new ConcurrentHashMap<String, TaggedPlayer>();

    private File localFile;
    private File hiddenFile;
    private volatile boolean lastFirebaseEnabled = PlayerTaggerConfig.firebaseEnabled;
    private volatile long lastSyncTime = 0;
    private volatile boolean isSyncing = false;

    private TagManager() {}

    public static TagManager getInstance() {
        return INSTANCE;
    }

    public void invalidateLookupCache() {
        exactLookupCache.clear();
    }

    public void init(File configDir) {
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        localFile = new File(configDir, "local_tagged_players.json");
        hiddenFile = new File(configDir, "hidden_players.json");

        loadLocalTags();
        loadHiddenPlayers();

        // Initial Google Firebase sync in background
        if (PlayerTaggerConfig.firebaseEnabled) {
            syncFromFirebase(false);
        }
    }

    public boolean hasAnyTags() {
        return !localTags.isEmpty() || (PlayerTaggerConfig.firebaseEnabled && !remoteTags.isEmpty());
    }

    public TaggedPlayer getTaggedPlayer(String username) {
        if (username == null || !hasAnyTags()) return null;

        boolean firebaseOn = PlayerTaggerConfig.firebaseEnabled;
        if (firebaseOn != lastFirebaseEnabled) {
            lastFirebaseEnabled = firebaseOn;
            exactLookupCache.clear();
        }

        TaggedPlayer cached = exactLookupCache.get(username);
        if (cached != null) {
            return cached == NO_TAG_SENTINEL ? null : cached;
        }

        String cleanName = username.toLowerCase(Locale.ROOT);
        TaggedPlayer resolved = null;

        if (localHiddenPlayers.isEmpty() || !localHiddenPlayers.contains(cleanName)) {
            resolved = localTags.get(cleanName);
            if (resolved == null && firebaseOn) {
                resolved = remoteTags.get(cleanName);
            }
        }

        if (exactLookupCache.size() >= MAX_LOOKUP_CACHE_SIZE) {
            exactLookupCache.clear();
        }
        exactLookupCache.put(username, resolved != null ? resolved : NO_TAG_SENTINEL);
        return resolved;
    }

    public void addPlayer(TaggedPlayer player) {
        if (player == null || player.getName() == null) return;
        String cleanName = player.getName().trim().toLowerCase(Locale.ROOT);
        if (localHiddenPlayers.remove(cleanName)) {
            saveHiddenPlayers();
        }

        localTags.put(cleanName, player);
        invalidateLookupCache();
        saveLocalTags();

        if (PlayerTaggerConfig.firebaseEnabled && PlayerTaggerConfig.firebaseAutoUpload) {
            uploadSinglePlayerToFirebase(player);
        }
    }

    public boolean removePlayer(String username) {
        if (username == null) return false;
        String cleanName = username.trim().toLowerCase(Locale.ROOT);

        boolean wasRemoved = localTags.remove(cleanName) != null;
        if (remoteTags.containsKey(cleanName)) {
            localHiddenPlayers.add(cleanName);
            saveHiddenPlayers();
            wasRemoved = true;
        }

        if (wasRemoved) {
            invalidateLookupCache();
            saveLocalTags();
            com.karl.playertagger.render.TabListHandler.resetPlayer(cleanName);
            if (PlayerTaggerConfig.firebaseEnabled && PlayerTaggerConfig.firebaseAutoUpload) {
                deletePlayerFromFirebase(cleanName);
            }
            return true;
        }
        return false;
    }

    public List<TaggedPlayer> getAllPlayers() {
        Map<String, TaggedPlayer> combined = new HashMap<String, TaggedPlayer>();
        if (PlayerTaggerConfig.firebaseEnabled) {
            for (Map.Entry<String, TaggedPlayer> entry : remoteTags.entrySet()) {
                if (!localHiddenPlayers.contains(entry.getKey())) {
                    combined.put(entry.getKey(), entry.getValue());
                }
            }
        }
        combined.putAll(localTags);
        return new ArrayList<TaggedPlayer>(combined.values());
    }

    public void loadLocalTags() {
        if (localFile == null || !localFile.exists()) {
            seedSampleEntries();
            saveLocalTags();
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(localFile), StandardCharsets.UTF_8)) {
            Type mapType = new TypeToken<Map<String, TaggedPlayer>>() {}.getType();
            Map<String, TaggedPlayer> loaded = GSON.fromJson(reader, mapType);
            if (loaded != null) {
                localTags.clear();
                for (Map.Entry<String, TaggedPlayer> entry : loaded.entrySet()) {
                    if (entry.getValue() != null) {
                        localTags.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                    }
                }
                invalidateLookupCache();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void saveLocalTags() {
        if (localFile == null) return;
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(localFile), StandardCharsets.UTF_8)) {
            GSON.toJson(localTags, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadHiddenPlayers() {
        if (hiddenFile == null || !hiddenFile.exists()) return;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(hiddenFile), StandardCharsets.UTF_8)) {
            Type setType = new TypeToken<Set<String>>() {}.getType();
            Set<String> loaded = GSON.fromJson(reader, setType);
            if (loaded != null) {
                localHiddenPlayers.clear();
                for (String s : loaded) {
                    localHiddenPlayers.add(s.toLowerCase(Locale.ROOT));
                }
                invalidateLookupCache();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveHiddenPlayers() {
        if (hiddenFile == null) return;
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(hiddenFile), StandardCharsets.UTF_8)) {
            GSON.toJson(localHiddenPlayers, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getNormalizedFirebaseUrl() {
        String urlStr = PlayerTaggerConfig.firebaseUrl;
        if (urlStr == null || urlStr.trim().isEmpty()) return null;
        urlStr = urlStr.trim();
        if (!urlStr.startsWith("http")) return null;

        urlStr = urlStr.replaceAll("/+$", "");

        if (!urlStr.endsWith(".json")) {
            urlStr = urlStr + "/players.json";
        }

        if (PlayerTaggerConfig.firebaseAuth != null && !PlayerTaggerConfig.firebaseAuth.trim().isEmpty()) {
            String token = PlayerTaggerConfig.firebaseAuth.trim();
            if (urlStr.contains("?")) {
                urlStr += "&auth=" + token;
            } else {
                urlStr += "?auth=" + token;
            }
        }
        return urlStr;
    }

    private volatile long lastSyncAttemptTime = 0;

    public void syncFromFirebase(final boolean notifyInChat) {
        long now = System.currentTimeMillis();
        // Prevent making requests more than once every 10 seconds under any circumstances
        if (now - lastSyncAttemptTime < 10000) {
            return;
        }
        if (isSyncing) return;
        isSyncing = true;
        lastSyncAttemptTime = now;

        ASYNC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String urlStr = getNormalizedFirebaseUrl();
                    if (urlStr == null) {
                        isSyncing = false;
                        if (notifyInChat) {
                            sendChatMsg("§c[PlayerTagger] Keine gültige Google Firebase URL in OneConfig hinterlegt.");
                        }
                        return;
                    }

                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("User-Agent", "PlayerTagger-Minecraft-1.8.9");
                    conn.setRequestProperty("Accept", "application/json");

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = in.readLine()) != null) {
                            response.append(line);
                        }
                        in.close();

                        String responseBody = response.toString().trim();
                        if (responseBody.equals("null") || responseBody.isEmpty()) {
                            remoteTags.clear();
                            invalidateLookupCache();
                            if (notifyInChat) {
                                sendChatMsg("§e[PlayerTagger] Google Firebase Datenbank ist aktuell leer.");
                            }
                        } else {
                            parseRemoteJson(responseBody);
                            lastSyncTime = System.currentTimeMillis();
                            if (notifyInChat) {
                                sendChatMsg("§a[PlayerTagger] 🔥 Google Firebase synchronisiert! (" + remoteTags.size() + " Spieler aktiv)");
                            }
                        }
                    } else {
                        if (notifyInChat) {
                            sendChatMsg("§c[PlayerTagger] Firebase Sync fehlgeschlagen (HTTP " + code + "). Überprüfe deine Firebase URL & Berechtigungen!");
                        }
                    }
                } catch (Exception e) {
                    if (notifyInChat) {
                        sendChatMsg("§c[PlayerTagger] Firebase Fehler: " + e.getMessage());
                    }
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                    isSyncing = false;
                }
            }
        });
    }

    public void uploadAllToFirebase(final boolean notifyInChat) {
        ASYNC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String urlStr = getNormalizedFirebaseUrl();
                    if (urlStr == null) {
                        if (notifyInChat) {
                            sendChatMsg("§c[PlayerTagger] Ungültige Google Firebase URL in OneConfig!");
                        }
                        return;
                    }

                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                    String jsonPayload = GSON.toJson(localTags);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                    }

                    int code = conn.getResponseCode();
                    if (code == 200 || code == 204) {
                        if (notifyInChat) {
                            sendChatMsg("§a[PlayerTagger] 🔥 " + localTags.size() + " Spieler erfolgreich in Google Firebase veröffentlicht!");
                        }
                        syncFromFirebase(false);
                    } else {
                        if (notifyInChat) {
                            sendChatMsg("§c[PlayerTagger] Firebase Upload failed: HTTP " + code + ". check URL / rules!");
                        }
                    }
                } catch (Exception e) {
                    if (notifyInChat) {
                        sendChatMsg("§c[PlayerTagger] Firebase Upload-Fehler: " + e.getMessage());
                    }
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    public void uploadSinglePlayerToFirebase(final TaggedPlayer player) {
        ASYNC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String baseUrl = PlayerTaggerConfig.firebaseUrl;
                    if (baseUrl == null) return;
                    baseUrl = baseUrl.trim().replaceAll("/+$", "");

                    if (baseUrl.endsWith("/players.json")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 13);
                    } else if (baseUrl.endsWith(".json")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 5);
                    }

                    String targetUrl = baseUrl + "/players/" + player.getName().toLowerCase() + ".json";
                    if (PlayerTaggerConfig.firebaseAuth != null && !PlayerTaggerConfig.firebaseAuth.trim().isEmpty()) {
                        targetUrl += "?auth=" + PlayerTaggerConfig.firebaseAuth.trim();
                    }

                    URL url = new URL(targetUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                    String jsonPayload = GSON.toJson(player);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                    }

                    int code = conn.getResponseCode();
                    if (code == 200 || code == 204) {
                        sendChatMsg("§a[PlayerTagger] 🔥 '§e" + player.getName() + "§a' wurde sofort in Google Firebase geteilt!");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    public void deletePlayerFromFirebase(final String username) {
        ASYNC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String baseUrl = PlayerTaggerConfig.firebaseUrl;
                    if (baseUrl == null) return;
                    baseUrl = baseUrl.trim().replaceAll("/+$", "");

                    if (baseUrl.endsWith("/players.json")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 13);
                    } else if (baseUrl.endsWith(".json")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 5);
                    }

                    String targetUrl = baseUrl + "/players/" + username.toLowerCase() + ".json";
                    if (PlayerTaggerConfig.firebaseAuth != null && !PlayerTaggerConfig.firebaseAuth.trim().isEmpty()) {
                        targetUrl += "?auth=" + PlayerTaggerConfig.firebaseAuth.trim();
                    }

                    URL url = new URL(targetUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("DELETE");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    conn.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private void parseRemoteJson(String json) {
        try {
            Type rawMapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> root = GSON.fromJson(json, rawMapType);
            if (root == null) return;

            Map<String, TaggedPlayer> parsed = new HashMap<String, TaggedPlayer>();

            if (root.containsKey("players") && root.get("players") instanceof Map) {
                String innerJson = GSON.toJson(root.get("players"));
                Type mapType = new TypeToken<Map<String, TaggedPlayer>>() {}.getType();
                Map<String, TaggedPlayer> inner = GSON.fromJson(innerJson, mapType);
                if (inner != null) {
                    for (Map.Entry<String, TaggedPlayer> entry : inner.entrySet()) {
                        TaggedPlayer tp = entry.getValue();
                        tp.setShared(true);
                        if (tp.getName() == null || tp.getName().isEmpty()) {
                            tp.setName(entry.getKey());
                        }
                        parsed.put(entry.getKey().toLowerCase(), tp);
                    }
                }
            } else {
                Type mapType = new TypeToken<Map<String, TaggedPlayer>>() {}.getType();
                Map<String, TaggedPlayer> flat = GSON.fromJson(json, mapType);
                if (flat != null) {
                    for (Map.Entry<String, TaggedPlayer> entry : flat.entrySet()) {
                        TaggedPlayer tp = entry.getValue();
                        tp.setShared(true);
                        if (tp.getName() == null || tp.getName().isEmpty()) {
                            tp.setName(entry.getKey());
                        }
                        parsed.put(entry.getKey().toLowerCase(), tp);
                    }
                }
            }

            remoteTags.clear();
            remoteTags.putAll(parsed);
            invalidateLookupCache();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void seedSampleEntries() {
        TaggedPlayer sample1 = new TaggedPlayer("SweatyPlayer99", "BedWars", "500★ / Fireball God", "§6", "⚡", "Sweat", 4, false);
        TaggedPlayer sample2 = new TaggedPlayer("BHopper_X", "SkyWars", "Blatant Fly & Killaura", "§c", "☠", "Cheater", 5, false);
        localTags.put(sample1.getName().toLowerCase(Locale.ROOT), sample1);
        localTags.put(sample2.getName().toLowerCase(Locale.ROOT), sample2);
        invalidateLookupCache();
    }

    private void sendChatMsg(final String msg) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText(msg));
                }
            }
        });
    }

    public long getLastSyncTime() {
        return lastSyncAttemptTime;
    }

    public boolean isSyncing() {
        return isSyncing;
    }
}
