package com.example.cheatwatch;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.PlayerTeam;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * fasd's Anti Cheat 3.0: a CLIENT-SIDE heuristic cheat spotter.
 * It only uses what the server already tells your client about other players
 * (position, swing animation, hurt animation, held item, effects, ping).
 * It cannot see other players' packets or their client brand.
 *
 * Every player the server sends to your client is watched, however far away they are. The server decides
 * how far that is (its entity tracking range), so on a server with a small range you only see the players
 * it sends you.
 */
public class CheatWatchClient implements ClientModInitializer {

    public static CheatWatchClient INSTANCE;

    private final Map<UUID, Track> tracks = new HashMap<>();
    private final List<AbstractClientPlayer> cur = new ArrayList<>();
    private final List<Track> curTracks = new ArrayList<>();
    private final Checks checks = new Checks(this);
    final Config cfg = Config.load();

    private int now = 0;
    private boolean dirty;
    private String sessionKey;       // which server the flags belong to (null = singleplayer, nothing saved)
    private boolean sessionLoaded;
    private ClientLevel lastLevel;
    private KeyMapping menuKey, reportKey, clearKey, toggleKey, tabKey;

    // tab-list colouring: what we changed, so we can put the original name back
    private static final class TabState {
        Component orig, ours;
        ChatFormatting col;
    }
    private final Map<UUID, TabState> tabApplied = new HashMap<>();

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fasds_anti_cheat", "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        reportKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.report", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        tabKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.tabcolors", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        clearKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fasds_anti_cheat.clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> saveNow());
    }

    public int now() { return now; }

    void markDirty() { dirty = true; }

    // =====================================================================
    private void onTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            leaveServer();
            return;
        }
        ClientLevel level = mc.level;
        if (!sessionLoaded) enterServer(mc);
        now++;

        while (menuKey.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new FlagScreen(this));
        }
        while (reportKey.consumeClick()) report(mc);
        while (toggleKey.consumeClick()) setEnabled(mc, !cfg.enabled);
        while (tabKey.consumeClick()) setTabColors(mc, !cfg.tabColors);
        while (clearKey.consumeClick()) {
            clearAll();
            say(mc, "Cleared all flags.", ChatFormatting.GRAY);
        }

        if (cfg.enabled) {
            checks.now = now;
            checks.len = cfg.leniency();
            checks.reportVl = cfg.reportVl();

            // a new world / dimension: everyone's coordinates jump, so restart their movement tracking
            if (level != lastLevel) {
                lastLevel = level;
                for (Track t : tracks.values()) {
                    t.resetMovement();
                    t.has = false;
                    t.lastTeleport = now;
                    t.airborne = false;
                    t.nfDue = 0;
                    t.kbDue = 0;
                }
            }

            runChecks(mc, level);
        }

        // every second: tab list colours; every minute: autosave; every 10 seconds: forget unflagged strangers
        if (now % 20 == 0) updateTabList(mc);
        if (now % 1200 == 0 && dirty) saveNow();
        if (now % 200 == 0) cleanup();
    }

    private void runChecks(Minecraft mc, ClientLevel level) {
        // Every player the server has sent us, at any distance.
        cur.clear();
        curTracks.clear();
        checks.swingP.clear();
        checks.swingT.clear();
        for (AbstractClientPlayer p : level.players()) {
            if (p == mc.player || p.isRemoved()) continue;
            cur.add(p);
        }
        checks.setPlayers(cur);

        // pass 1: movement + swing bookkeeping (needs nothing from other players)
        for (int i = 0; i < cur.size(); i++) {
            AbstractClientPlayer p = cur.get(i);
            Track t = tracks.get(p.getUUID());
            if (t == null) {
                t = new Track();
                t.uuid = p.getUUID();
                t.slot = Math.floorMod(p.getUUID().hashCode(), 3);
                tracks.put(t.uuid, t);
            }
            curTracks.add(t);
            t.name = p.getName().getString();
            t.lastSeen = now;
            t.lastSeenWall = System.currentTimeMillis();
            t.dist = p.distanceTo(mc.player);
            t.ping = pingOf(mc, p);
            checks.movement(level, p, t);
            checks.swing(p, t);
        }
        // pass 2: combat (needs every player's state for this tick to be up to date)
        for (int i = 0; i < cur.size(); i++) {
            checks.combat(level, cur.get(i), curTracks.get(i));
        }
        checks.finishCombat();
    }

    private int pingOf(Minecraft mc, AbstractClientPlayer p) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return 0;
        PlayerInfo info = conn.getPlayerInfo(p.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    // =====================================================================
    // flags, saving, sessions
    // =====================================================================

    void onConfirmed(Track t, Cheat c) {
        Minecraft mc = Minecraft.getInstance();
        if (cfg.chatAlerts && mc.player != null) {
            say(mc, t.name + " may be using " + c.label, ChatFormatting.RED);
        }
        // make the tab list react straight away instead of waiting up to a second
        updateTabList(mc);
    }

    private void enterServer(Minecraft mc) {
        sessionLoaded = true;
        ServerData sd = mc.getCurrentServer();
        sessionKey = sd == null ? null : sd.ip;
        tracks.clear();
        tabApplied.clear();
        dirty = false;
        lastLevel = null;
        FlagStore.load(sessionKey, tracks, now);
    }

    private void leaveServer() {
        if (!sessionLoaded) return;
        saveNow();
        tracks.clear();
        tabApplied.clear();
        cur.clear();
        curTracks.clear();
        sessionLoaded = false;
        sessionKey = null;
        lastLevel = null;
    }

    void saveNow() {
        if (sessionKey != null && sessionLoaded) FlagStore.save(sessionKey, tracks.values());
        dirty = false;
    }

    /** Unflagged players we have not seen for 5 minutes are forgotten. Flagged players are kept. */
    private void cleanup() {
        for (Iterator<Track> it = tracks.values().iterator(); it.hasNext(); ) {
            Track t = it.next();
            if (t.isFlagged()) continue;
            if (now - t.lastSeen > 6000 && t.score(now) < 1.0) it.remove();
        }
    }

    // =====================================================================
    // tab list: yellow = suspicious, red = likely cheating
    // =====================================================================

    private void updateTabList(Minecraft mc) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        double rvl = cfg.reportVl();
        boolean want = cfg.tabColors;

        for (Track t : tracks.values()) {
            if (t.uuid == null) continue;
            int st = want ? t.status(rvl, now) : 0;
            ChatFormatting col = st >= 3 ? ChatFormatting.RED : st == 2 ? ChatFormatting.YELLOW : null;
            PlayerInfo info = conn.getPlayerInfo(t.uuid);
            TabState ts = tabApplied.get(t.uuid);
            if (info == null) {                      // left the server
                if (ts != null) tabApplied.remove(t.uuid);
                continue;
            }
            if (col == null) {
                if (ts != null) {                    // no longer flagged (or colours turned off): put the old name back
                    if (info.getTabListDisplayName() == ts.ours) info.setTabListDisplayName(ts.orig);
                    tabApplied.remove(t.uuid);
                }
                continue;
            }
            Component shown = info.getTabListDisplayName();
            if (ts != null && shown == ts.ours && ts.col == col) continue;   // already up to date
            if (ts == null) {
                ts = new TabState();
                tabApplied.put(t.uuid, ts);
                ts.orig = shown;
            } else if (shown != ts.ours) {
                ts.orig = shown;                     // the server changed the name since: remember the new one
            }
            ts.col = col;
            ts.ours = coloured(info, t, ts.orig, col);
            info.setTabListDisplayName(ts.ours);
        }
        // tracks that were forgotten while coloured
        if (!tabApplied.isEmpty()) {
            for (Iterator<Map.Entry<UUID, TabState>> it = tabApplied.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, TabState> e = it.next();
                if (tracks.containsKey(e.getKey())) continue;
                PlayerInfo info = conn.getPlayerInfo(e.getKey());
                if (info != null && info.getTabListDisplayName() == e.getValue().ours) {
                    info.setTabListDisplayName(e.getValue().orig);
                }
                it.remove();
            }
        }
    }

    /** Keeps team prefixes / suffixes (ranks) but forces the name itself to the warning colour. */
    private static Component coloured(PlayerInfo info, Track t, Component orig, ChatFormatting col) {
        if (orig != null) return Component.literal(orig.getString()).withStyle(col);
        MutableComponent out = Component.empty();
        PlayerTeam team = info.getTeam();
        if (team != null) out.append(team.getPlayerPrefix());
        out.append(Component.literal(t.name).withStyle(col));
        if (team != null) out.append(team.getPlayerSuffix());
        return out;
    }

    /** Puts every name back (used when colours are switched off). */
    private void restoreTabList(Minecraft mc) {
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            for (Map.Entry<UUID, TabState> e : tabApplied.entrySet()) {
                PlayerInfo info = conn.getPlayerInfo(e.getKey());
                if (info != null && info.getTabListDisplayName() == e.getValue().ours) {
                    info.setTabListDisplayName(e.getValue().orig);
                }
            }
        }
        tabApplied.clear();
    }

    // =====================================================================
    // settings (used by the keys and the menu)
    // =====================================================================

    void setEnabled(Minecraft mc, boolean on) {
        cfg.enabled = on;
        cfg.save();
        say(mc, "Detection " + (on ? "ON" : "OFF") + (on ? "" : " (saved flags stay, nobody new gets flagged)"),
                on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    void setTabColors(Minecraft mc, boolean on) {
        cfg.tabColors = on;
        cfg.save();
        if (on) updateTabList(mc); else restoreTabList(mc);
        say(mc, "Tab list colours " + (on ? "ON (yellow = suspicious, red = likely cheating)" : "OFF"),
                on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    void setSensitivity(int s) {
        cfg.sensitivity = Math.floorMod(s, 3);
        cfg.save();
    }

    void setChatAlerts(boolean on) {
        cfg.chatAlerts = on;
        cfg.save();
    }

    private void say(Minecraft mc, String msg, ChatFormatting col) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[fasd's Anti Cheat] " + msg).withStyle(col), false);
        }
    }

    // =====================================================================
    // used by the menu
    // =====================================================================

    public void clearAll() {
        Minecraft mc = Minecraft.getInstance();
        restoreTabList(mc);
        tracks.clear();
        dirty = true;
        saveNow();
    }

    public void forget(UUID id) {
        Track t = tracks.remove(id);
        if (t != null) {
            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener conn = mc.getConnection();
            TabState ts = tabApplied.remove(id);
            if (ts != null && conn != null) {
                PlayerInfo info = conn.getPlayerInfo(id);
                if (info != null && info.getTabListDisplayName() == ts.ours) info.setTabListDisplayName(ts.orig);
            }
            dirty = true;
            saveNow();
        }
    }

    /** tab: 0 = flagged, 1 = watching, 2 = reach, 3 = all players (sorted by sort: 0 suspicion, 1 name, 2 distance). */
    public List<Track> snapshot(int tab, int sort) {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks.values()) {
            switch (tab) {
                case 0 -> { if (t.isFlagged()) out.add(t); }
                case 1 -> { if (!t.isFlagged() && t.score(now) >= 0.5) out.add(t); }
                case 2 -> { if (t.reachHits > 0) out.add(t); }
                default -> out.add(t);
            }
        }
        if (tab == 2) {
            out.sort((a, b) -> Double.compare(b.maxReach, a.maxReach));
        } else if (tab == 3 && sort == 1) {
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        } else if (tab == 3 && sort == 2) {
            out.sort((a, b) -> Double.compare(a.dist + (inRange(a) ? 0 : 1e6), b.dist + (inRange(b) ? 0 : 1e6)));
        } else {
            double rvl = cfg.reportVl();
            out.sort((a, b) -> {
                int c = Integer.compare(b.status(rvl, now), a.status(rvl, now));
                return c != 0 ? c : Double.compare(b.score(now), a.score(now));
            });
        }
        return out;
    }

    public boolean inRange(Track t) { return now - t.lastSeen < 40; }

    public int flaggedCount() {
        int n = 0;
        for (Track t : tracks.values()) if (t.isFlagged()) n++;
        return n;
    }

    public int trackedCount() { return tracks.size(); }

    public void report(Minecraft mc) {
        if (mc.player == null) return;
        double rvl = cfg.reportVl();
        List<Track> flagged = snapshot(0, 0);
        mc.player.displayClientMessage(Component.literal("===== fasd's Anti Cheat report =====").withStyle(ChatFormatting.GOLD), false);
        if (flagged.isEmpty()) {
            mc.player.displayClientMessage(Component.literal("No one is flagged right now.").withStyle(ChatFormatting.GREEN), false);
            return;
        }
        for (Track t : flagged) {
            int st = t.status(rvl, now);
            ChatFormatting col = st >= 3 ? ChatFormatting.RED : ChatFormatting.YELLOW;
            String where = inRange(t) ? String.format("%.0f blocks", t.dist) : "not in range";
            mc.player.displayClientMessage(Component.literal(String.format("%s - %s (%s)", t.name, t.verdict(rvl, now), where))
                    .withStyle(col, ChatFormatting.BOLD), false);
            for (Cheat c : Cheat.values()) {
                int i = c.ordinal();
                if (!t.confirmed[i]) continue;
                mc.player.displayClientMessage(Component.literal(String.format("  %s (vl %.1f, %dx) - %s", c.label, t.vl[i], t.count[i],
                        t.detail[i] == null ? c.desc : t.detail[i])).withStyle(ChatFormatting.GRAY), false);
            }
        }
        mc.player.displayClientMessage(Component.literal("Heuristic only - lag and odd mechanics can still cause false flags.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), false);
    }
}
