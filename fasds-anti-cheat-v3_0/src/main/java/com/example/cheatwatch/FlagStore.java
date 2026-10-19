package com.example.cheatwatch;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Saves confirmed flags per server so they survive relogging and restarting the game.
 * One file per server address in config/fasds_anti_cheat/.
 */
final class FlagStore {
    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("fasds_anti_cheat");
    }

    private static Path file(String serverKey) {
        String safe = serverKey.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        return dir().resolve(safe + ".json");
    }

    static void save(String serverKey, Collection<Track> tracks) {
        if (serverKey == null) return;
        try {
            JsonObject players = new JsonObject();
            for (Track t : tracks) {
                if (t.uuid == null || !t.isFlagged()) continue;
                JsonObject p = new JsonObject();
                p.addProperty("name", t.name);
                JsonObject cheats = new JsonObject();
                for (Cheat c : Cheat.values()) {
                    int i = c.ordinal();
                    if (!t.confirmed[i]) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("vl", Math.round(t.vl[i] * 100.0) / 100.0);
                    o.addProperty("count", t.count[i]);
                    o.addProperty("detail", t.detail[i] == null ? "" : t.detail[i]);
                    o.addProperty("first", t.firstWall[i]);
                    o.addProperty("last", t.lastWall[i]);
                    cheats.add(c.name(), o);
                }
                p.add("cheats", cheats);
                p.addProperty("maxReach", Math.round(t.maxReach * 100.0) / 100.0);
                p.addProperty("lastSeen", t.lastSeenWall);
                players.add(t.uuid.toString(), p);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("players", players);
            Path f = file(serverKey);
            Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {
            // not critical
        }
    }

    static void load(String serverKey, Map<UUID, Track> out, int now) {
        if (serverKey == null) return;
        try {
            Path f = file(serverKey);
            if (!Files.exists(f)) return;
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            if (players == null) return;
            for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                UUID id;
                try { id = UUID.fromString(e.getKey()); } catch (IllegalArgumentException bad) { continue; }
                JsonObject p = e.getValue().getAsJsonObject();
                Track t = out.computeIfAbsent(id, k -> new Track());
                t.uuid = id;
                if (p.has("name")) t.name = p.get("name").getAsString();
                if (p.has("maxReach")) t.maxReach = p.get("maxReach").getAsDouble();
                if (p.has("lastSeen")) t.lastSeenWall = p.get("lastSeen").getAsLong();
                JsonObject cheats = p.getAsJsonObject("cheats");
                if (cheats == null) continue;
                for (Cheat c : Cheat.values()) {
                    if (!cheats.has(c.name())) continue;
                    JsonObject o = cheats.getAsJsonObject(c.name());
                    int i = c.ordinal();
                    t.confirmed[i] = true;
                    t.vl[i] = o.has("vl") ? o.get("vl").getAsDouble() : 3.0;
                    t.vlStamp[i] = now;
                    t.count[i] = o.has("count") ? o.get("count").getAsInt() : 1;
                    t.detail[i] = o.has("detail") ? o.get("detail").getAsString() : null;
                    t.firstWall[i] = o.has("first") ? o.get("first").getAsLong() : 0L;
                    t.lastWall[i] = o.has("last") ? o.get("last").getAsLong() : 0L;
                }
            }
        } catch (Exception ignored) {
            // corrupt file -> start fresh
        }
    }
}
