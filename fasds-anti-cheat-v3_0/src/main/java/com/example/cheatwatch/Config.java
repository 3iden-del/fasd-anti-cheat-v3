package com.example.cheatwatch;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Your settings. Saved to config/fasds_anti_cheat.json. */
final class Config {
    static final String[] SENS_NAMES = {"Low (fewest false flags)", "Normal", "High (catches more)"};

    boolean enabled = true;       // run the detection at all
    boolean tabColors = true;     // yellow / red names in the tab list
    boolean chatAlerts = false;   // chat line the moment someone gets confirmed
    int sensitivity = 1;          // 0 low, 1 normal, 2 high

    /** Multiplies every speed / reach / jump limit. Bigger = more forgiving. */
    double leniency() {
        return sensitivity == 0 ? 1.12 : sensitivity == 2 ? 0.93 : 1.0;
    }

    /** VL a single cheat needs before the player counts as flagged. */
    double reportVl() {
        return sensitivity == 0 ? 4.0 : sensitivity == 2 ? 2.0 : 3.0;
    }

    String sensName() { return SENS_NAMES[Math.max(0, Math.min(2, sensitivity))]; }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("fasds_anti_cheat.json");
    }

    static Config load() {
        Config c = new Config();
        try {
            Path f = file();
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                if (o.has("enabled")) c.enabled = o.get("enabled").getAsBoolean();
                if (o.has("tabColors")) c.tabColors = o.get("tabColors").getAsBoolean();
                if (o.has("chatAlerts")) c.chatAlerts = o.get("chatAlerts").getAsBoolean();
                if (o.has("sensitivity")) c.sensitivity = Math.max(0, Math.min(2, o.get("sensitivity").getAsInt()));
            }
        } catch (Exception ignored) {
            // bad or missing file -> defaults
        }
        return c;
    }

    void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("tabColors", tabColors);
            o.addProperty("chatAlerts", chatAlerts);
            o.addProperty("sensitivity", sensitivity);
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception ignored) {
            // not critical
        }
    }
}
