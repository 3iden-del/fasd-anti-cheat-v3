package com.example.cheatwatch;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The menu. Tabs: Flagged (confirmed cheaters, kept until you clear them), Watching (minor / pending flags),
 * Reach (every hit measured), All players (anyone tracked, flagged or not) and Settings.
 */
public class FlagScreen extends Screen {
    private static final String[] TABS = {"Flagged", "Watching", "Reach", "All players", "Settings"};
    private static final String[] ALL_SORT = {"Suspicion", "Name", "Distance"};
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, DARK = 0xFF777777, RED = 0xFFFF5555,
            YELLOW = 0xFFFFFF55, GREEN = 0xFF55FF55, GOLD = 0xFFFFD700, ORANGE = 0xFFFFAA00;

    // remembered between openings
    private static int lastTab = 0, lastAllSort = 0;

    private final CheatWatchClient mod;
    private List<Track> rows = new ArrayList<>();
    private int tab = lastTab;
    private int allSort = lastAllSort;
    private int scroll = 0;
    private int perPage = 8;
    private UUID selected;
    private int refresh = 0;
    private boolean confirmClear = false;

    public FlagScreen(CheatWatchClient mod) {
        super(Component.literal("fasd's Anti Cheat"));
        this.mod = mod;
    }

    @Override
    protected void init() {
        rows = mod.snapshot(Math.min(tab, 3), allSort);

        // ---- tab buttons ----
        int tw = 78, gap = 3;
        int tx = this.width / 2 - (TABS.length * (tw + gap) - gap) / 2;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            String label = TABS[i];
            if (i == 0) label += " (" + mod.flaggedCount() + ")";
            Component text = i == tab
                    ? Component.literal(label).withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW)
                    : Component.literal(label);
            Button b = Button.builder(text, x -> {
                tab = idx;
                lastTab = idx;
                scroll = 0;
                confirmClear = false;
                rebuildWidgets();
            }).bounds(tx + i * (tw + gap), 8, tw, 20).build();
            addRenderableWidget(b);
        }

        // ---- bottom buttons ----
        int by = this.height - 26;
        int bx = this.width / 2 - 155;
        addRenderableWidget(Button.builder(Component.literal("Print to chat"), b -> mod.report(Minecraft.getInstance()))
                .bounds(bx, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Settings"), b -> {
            tab = 4;
            lastTab = 4;
            rebuildWidgets();
        }).bounds(bx + 105, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(bx + 210, by, 100, 20).build());

        if (tab == 4) {
            initSettings();
        } else {
            initList();
        }
    }

    private void initSettings() {
        int x = 20, y = 44, w = 200;
        addRenderableWidget(Button.builder(Component.literal("Detection: " + (mod.cfg.enabled ? "ON" : "OFF")), b -> {
            mod.setEnabled(Minecraft.getInstance(), !mod.cfg.enabled);
            rebuildWidgets();
        }).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Tab list colours: " + (mod.cfg.tabColors ? "ON" : "OFF")), b -> {
            mod.setTabColors(Minecraft.getInstance(), !mod.cfg.tabColors);
            rebuildWidgets();
        }).bounds(x, y + 26, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Sensitivity: " + mod.cfg.sensName()), b -> {
            mod.setSensitivity(mod.cfg.sensitivity + 1);
            rebuildWidgets();
        }).bounds(x, y + 52, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Chat alerts: " + (mod.cfg.chatAlerts ? "ON" : "OFF")), b -> {
            mod.setChatAlerts(!mod.cfg.chatAlerts);
            rebuildWidgets();
        }).bounds(x, y + 78, w, 20).build());
        addRenderableWidget(Button.builder(
                confirmClear ? Component.literal("Click again to delete ALL flags").withStyle(ChatFormatting.RED)
                        : Component.literal("Clear all flags"), b -> {
            if (confirmClear) {
                mod.clearAll();
                selected = null;
                confirmClear = false;
            } else {
                confirmClear = true;
            }
            rebuildWidgets();
        }).bounds(x, y + 112, w, 20).build());
    }

    private void initList() {
        perPage = Math.max(3, Math.min(12, (this.height - 36 - 90) / 22));
        int maxScroll = Math.max(0, rows.size() - perPage);
        scroll = Mth.clamp(scroll, 0, maxScroll);

        int left = 20, top = 44;
        double rvl = mod.cfg.reportVl();
        int now = mod.now();
        for (int i = 0; i < perPage; i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) break;
            final Track t = rows.get(idx);
            int st = t.status(rvl, now);
            ChatFormatting col = st >= 3 ? ChatFormatting.RED : st == 2 ? ChatFormatting.YELLOW
                    : st == 1 ? ChatFormatting.WHITE : ChatFormatting.GRAY;
            String info;
            if (tab == 2) info = String.format("%.2f b max", t.maxReach);
            else if (tab == 3 && allSort == 2) info = mod.inRange(t) ? String.format("%.0f b", t.dist) : "away";
            else info = t.isFlagged() ? t.confirmedKinds() + " cheat" + (t.confirmedKinds() == 1 ? "" : "s")
                    : String.format("%.1f", t.score(now));
            String prefix = mod.inRange(t) ? "" : "(away) ";
            Component label = Component.literal(prefix + t.name + "  " + info).withStyle(col);
            addRenderableWidget(Button.builder(label, b -> selected = t.uuid).bounds(left, top + i * 22, 200, 20).build());
        }

        int pageY = top + perPage * 22 + 4;
        addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> {
            scroll = Math.max(0, scroll - perPage);
            rebuildWidgets();
        }).bounds(left, pageY, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> {
            scroll += perPage;
            rebuildWidgets();
        }).bounds(left + 160, pageY, 40, 20).build());
        if (tab == 3) {
            addRenderableWidget(Button.builder(Component.literal("Sort: " + ALL_SORT[allSort]), b -> {
                allSort = (allSort + 1) % ALL_SORT.length;
                lastAllSort = allSort;
                scroll = 0;
                rebuildWidgets();
            }).bounds(left + 45, pageY, 110, 20).build());
        }

        if (selected != null) {
            addRenderableWidget(Button.builder(Component.literal("Forget this player"), b -> {
                mod.forget(selected);
                selected = null;
                rebuildWidgets();
            }).bounds(235, this.height - 52, 130, 20).build());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab != 4 && mouseX < 230) {
            int maxScroll = Math.max(0, rows.size() - perPage);
            int next = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll);
            if (next != scroll) {
                scroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
        if (++refresh >= 20) {
            refresh = 0;
            rebuildWidgets();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =====================================================================
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);

        g.drawString(this.font, "fasd's Anti Cheat", 20, 14, GOLD);
        String state = (mod.cfg.enabled ? "detection ON" : "detection OFF") + " | " + mod.trackedCount() + " players tracked";
        g.drawString(this.font, state, 20, this.height - 37, mod.cfg.enabled ? GRAY : ORANGE);

        if (tab == 4) {
            renderSettingsInfo(g);
            return;
        }

        int x = 235, y = 44;
        g.fill(x - 6, y - 6, this.width - 14, this.height - 58, 0x55000000);

        if (rows.isEmpty()) {
            String[] msg = switch (tab) {
                case 0 -> new String[]{"Nobody is flagged yet.", "Flags stay here until you clear them."};
                case 1 -> new String[]{"Nobody has minor flags right now."};
                case 2 -> new String[]{"No hits measured yet."};
                default -> new String[]{"Nobody tracked yet."};
            };
            for (int i = 0; i < msg.length; i++) g.drawString(this.font, msg[i], 20, 48 + i * 12, GRAY);
        }

        Track sel = null;
        for (Track t : rows) if (t.uuid != null && t.uuid.equals(selected)) sel = t;
        if (sel == null) {
            g.drawString(this.font, "Click a player on the left to see their flags.", x, y, GRAY);
            g.drawString(this.font, "Yellow = suspicious, red = likely cheating.", x, y + 14, DARK);
            g.drawString(this.font, "Scroll the list with the mouse wheel.", x, y + 26, DARK);
            return;
        }
        renderDetail(g, sel, x, y);
    }

    private void renderDetail(GuiGraphics g, Track sel, int x, int y) {
        double rvl = mod.cfg.reportVl();
        int now = mod.now();
        int st = sel.status(rvl, now);
        int nameCol = st >= 3 ? RED : st == 2 ? YELLOW : WHITE;
        g.drawString(this.font, sel.name, x, y, nameCol);
        y += 12;
        String where = mod.inRange(sel) ? String.format("%.1f blocks away", sel.dist) : "not in range (last seen " + ago(sel.lastSeenWall) + ")";
        g.drawString(this.font, where + " | ping " + sel.ping + " ms", x, y, GRAY);
        y += 12;
        if (sel.isFlagged()) {
            g.drawString(this.font, "Verdict: " + sel.verdict(rvl, now), x, y, st >= 3 ? RED : ORANGE);
        } else {
            g.drawString(this.font, "Not flagged (minor evidence only)", x, y, GREEN);
        }
        y += 16;

        int limit = this.height - 64;
        for (Cheat c : Cheat.values()) {
            int i = c.ordinal();
            double v = sel.vl(i, now);
            if (!sel.confirmed[i] && v < 0.5) continue;
            if (y > limit - 24) {
                g.drawString(this.font, "...", x, y, GRAY);
                break;
            }
            boolean over = sel.confirmed[i];
            String head = String.format("%s  (vl %.1f%s)", c.label, v, over ? ", flagged " + sel.count[i] + "x" : "");
            g.drawString(this.font, head, x, y, over ? RED : GRAY);
            y += 11;
            String d = sel.detail[i] == null ? c.desc : sel.detail[i];
            g.drawString(this.font, "   " + this.font.plainSubstrByWidth(d, this.width - x - 40), x, y, GRAY);
            y += 11;
            if (over && sel.lastWall[i] > 0) {
                g.drawString(this.font, "   last " + ago(sel.lastWall[i]) + (sel.firstWall[i] > 0 ? ", first " + ago(sel.firstWall[i]) : ""), x, y, DARK);
                y += 11;
            }
            y += 3;
        }
        if (sel.reachHits > 0 && y < limit) {
            y += 2;
            g.drawString(this.font, String.format("Reach: max %.2f, avg %.2f over %d hits", sel.maxReach, sel.avgReach(), sel.reachHits), x, y, WHITE);
        }
    }

    private void renderSettingsInfo(GuiGraphics g) {
        int x = 240, y = 48;
        g.drawString(this.font, "Tab list colours", x, y, GOLD);
        g.drawString(this.font, "Yellow name = suspicious (one confirmed cheat)", x, y + 12, YELLOW);
        g.drawString(this.font, "Red name = likely cheating (two or more)", x, y + 24, RED);
        g.drawString(this.font, "Turning colours off puts the original names back.", x, y + 36, GRAY);
        y += 62;
        g.drawString(this.font, "Sensitivity", x, y, GOLD);
        g.drawString(this.font, "Low: most forgiving, needs more proof.", x, y + 12, GRAY);
        g.drawString(this.font, "High: flags sooner, more risk of false flags.", x, y + 24, GRAY);
        y += 50;
        g.drawString(this.font, "Flags are saved per server and survive relogging.", x, y, GRAY);
        g.drawString(this.font, "Detection OFF stops new flags; saved flags stay.", x, y + 12, GRAY);
    }

    private static String ago(long wall) {
        if (wall <= 0) return "never";
        long s = Math.max(0, (System.currentTimeMillis() - wall) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }
}
