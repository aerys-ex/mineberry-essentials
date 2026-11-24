package com.mineberry.essentials;

import net.minecraft.client.KeyMapping;
import com.mineberry.essentials.modules.TournamentHudModule;
import com.mineberry.essentials.modules.WaypointsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** The multi-line HUD mods: Keystrokes/CPS, Armor Status, Potion Effects, Compass, Biome/Time and Tournament Players. */
final class HudWidgets {

    private static final int WHITE = 0xFFFFFFFF, SOFT = 0xFFC8C8C8;

    private HudWidgets() {}

    // ---------------------------------------------------------------- watermark

    /** "MineBerry Essentials" in a corner, drawn without a box like the watermarks of other clients. */
    static final class Watermark implements HudWidget {

        private static final String MINE = "Mine", BERRY = "Berry", SUB = " Essentials";

        @Override
        public int[] size(Minecraft mc, Font font) {
            return new int[]{font.width(MINE + BERRY + SUB), 9};
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {
            Ui.txt(g, font, MINE, x, y + 1, WHITE, true);
            x += font.width(MINE);
            Ui.txt(g, font, BERRY, x, y + 1, 0xFF000000 | Ui.ACCENT, true);
            x += font.width(BERRY);
            Ui.txt(g, font, SUB, x, y + 1, 0xFF000000 | Ui.MUTED, true);
        }

        @Override
        public boolean plain() {
            return true;
        }
    }

    // ---------------------------------------------------------------- vanilla scoreboard

    /**
     * The vanilla sidebar scoreboard. ClientHud wraps the vanilla element and reads this mod's on/off switch and size, so this widget
     * only gives it a card in the HUD menu. The layout never places or draws it.
     */
    static final class Scoreboard implements HudWidget {

        @Override
        public int[] size(Minecraft mc, Font font) {
            return null;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {}

        @Override
        public boolean virtual() {
            return true;
        }
    }

    // ---------------------------------------------------------------- keystrokes + CPS

    /** W A S D, both mouse buttons and the space bar, lit while held, with the clicks per second of each mouse button. */
    static final class Keystrokes implements HudWidget {

        private static final int K = 18, GAP = 2, SPACE_H = 8, KEYS_W = K * 3 + GAP * 2;
        private static final int HEIGHT = K * 3 + GAP * 3 + SPACE_H + 3 + 9;

        private final ClickCounter left = new ClickCounter(), right = new ClickCounter();
        private String cps = "";
        private int width;

        @Override
        public int[] size(Minecraft mc, Font font) {
            long now = System.nanoTime() / 1_000_000L;
            left.sample(mc.options.keyAttack.isDown(), now);
            right.sample(mc.options.keyUse.isDown(), now);
            cps = left.cps(now) + " | " + right.cps(now) + " CPS";
            width = Math.max(KEYS_W, font.width(cps));
            return new int[]{width, HEIGHT};
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {
            int kx = x + (width - KEYS_W) / 2, step = K + GAP;
            key(g, font, mc.options.keyUp, kx + step, y);
            key(g, font, mc.options.keyLeft, kx, y + step);
            key(g, font, mc.options.keyDown, kx + step, y + step);
            key(g, font, mc.options.keyRight, kx + step * 2, y + step);

            int my = y + step * 2, half = (KEYS_W - GAP) / 2;
            mouse(g, font, "LMB", mc.options.keyAttack.isDown(), kx, my, half);
            mouse(g, font, "RMB", mc.options.keyUse.isDown(), kx + half + GAP, my, KEYS_W - half - GAP);

            int sy = my + step;
            boolean jump = mc.options.keyJump.isDown();
            box(g, kx, sy, KEYS_W, SPACE_H, jump);
            Ui.fill(g, kx + 14, sy + 3, kx + KEYS_W - 14, sy + 5, jump ? WHITE : SOFT);

            Ui.txt(g, font, cps, x + (width - font.width(cps)) / 2, sy + SPACE_H + 3 + 1, SOFT, true);
        }

        private static void key(GuiGraphicsExtractor g, Font font, KeyMapping mapping, int x, int y) {
            boolean down = mapping.isDown();
            box(g, x, y, K, K, down);
            label(g, font, mapping.getTranslatedKeyMessage().getString(), x, y, K, K, down);
        }

        private static void mouse(GuiGraphicsExtractor g, Font font, String name, boolean down, int x, int y, int w) {
            box(g, x, y, w, K, down);
            label(g, font, name, x, y, w, K, down);
        }

        private static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean down) {
            Ui.keyBox(g, x, y, w, h, down);
        }

        private static void label(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int h, boolean down) {
            String shown = font.plainSubstrByWidth(text, w - 2);
            Ui.txt(g, font, shown, x + (w - font.width(shown)) / 2, y + (h - 8) / 2 + 1, down ? WHITE : SOFT, false);
        }
    }

    // ---------------------------------------------------------------- armor + durability

    /** Helmet, chestplate, leggings and boots with the durability left, plus the held item when it wears out (tools, bows...). */
    static final class Armor implements HudWidget {

        private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        private static final int ROW = 18;

        private final ItemStack[] stacks = new ItemStack[SLOTS.length + 1];
        private final String[] labels = new String[stacks.length];
        private final int[] colors = new int[stacks.length];
        private int count;

        @Override
        public int[] size(Minecraft mc, Font font) {
            Arrays.fill(stacks, null);
            count = 0;
            if (mc.player == null) return null;
            for (EquipmentSlot slot : SLOTS) add(mc.player.getItemBySlot(slot), false);
            add(mc.player.getMainHandItem(), true);
            if (count == 0) return null;
            int textW = 0;
            for (int i = 0; i < count; i++) textW = Math.max(textW, font.width(labels[i]));
            return new int[]{16 + (textW > 0 ? 4 + textW : 0), count * ROW - 2};
        }

        private void add(ItemStack stack, boolean onlyIfWears) {
            if (stack.isEmpty()) return;
            boolean wears = stack.isDamageableItem();
            if (onlyIfWears && !wears) return;
            stacks[count] = stack;
            if (wears) {
                int max = stack.getMaxDamage(), left = Math.max(0, max - stack.getDamageValue());
                labels[count] = String.valueOf(left);
                colors[count] = HudFormat.durabilityColor(left, max);
            } else {
                labels[count] = "";
                colors[count] = WHITE;
            }
            count++;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {
            for (int i = 0; i < count; i++) {
                int ry = y + i * ROW;
                g.item(stacks[i], x, ry);
                if (!labels[i].isEmpty()) Ui.txt(g, font, labels[i], x + 20, ry + 5, colors[i], true);
            }
        }
    }

    // ---------------------------------------------------------------- potion effects

    /** Every visible effect on you: a swatch in the effect's colour, its name and level, and the time left. */
    static final class Potions implements HudWidget {

        private record Line(String name, String time, int color, int timeColor) {}

        private static final int MAX = 8, ROW = 10, SWATCH = 4, NAME_GAP = 7, TIME_GAP = 8;

        private final List<Line> lines = new ArrayList<>();
        private String more = "";
        private int width;

        @Override
        public int[] size(Minecraft mc, Font font) {
            lines.clear();
            more = "";
            if (mc.player == null) return null;
            for (MobEffectInstance instance : mc.player.getActiveEffects()) {
                if (!instance.isVisible()) continue;
                var effect = instance.getEffect().value();
                String level = HudFormat.roman(instance.getAmplifier());
                String name = effect.getDisplayName().getString() + (level.isEmpty() ? "" : " " + level);
                boolean forever = instance.isInfiniteDuration();
                int ticks = instance.getDuration();
                lines.add(new Line(name, forever ? "Inf" : HudFormat.duration(ticks), 0xFF000000 | effect.getColor(),
                        !forever && ticks < 200 ? 0xFFFF5555 : SOFT));
            }
            if (lines.isEmpty()) return null;
            lines.sort(Comparator.comparing(Line::name));
            if (lines.size() > MAX) {
                more = "+" + (lines.size() - MAX) + " more";
                lines.subList(MAX, lines.size()).clear();
            }
            int nameW = 0, timeW = 0;
            for (Line line : lines) {
                nameW = Math.max(nameW, font.width(line.name()));
                timeW = Math.max(timeW, font.width(line.time()));
            }
            width = Math.max(NAME_GAP + nameW + TIME_GAP + timeW, font.width(more));
            return new int[]{width, lines.size() * ROW + (more.isEmpty() ? 0 : ROW) - 1};
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {
            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                int ry = y + i * ROW;
                Ui.fill(g, x, ry + 3, x + SWATCH, ry + 3 + SWATCH, line.color());
                Ui.txt(g, font, line.name(), x + NAME_GAP, ry + 1, WHITE, true);
                Ui.txt(g, font, line.time(), x + width - font.width(line.time()), ry + 1, line.timeColor(), true);
            }
            if (!more.isEmpty()) Ui.txt(g, font, more, x, y + lines.size() * ROW + 1, Ui.MUTED, true);
        }
    }

    // ---------------------------------------------------------------- compass

    /**
     * A scrolling strip of N / NE / E... that follows where you look, with the exact heading and world axis below it.
     * When the Waypoints module is on, your visible waypoints of this dimension show as coloured markers under the strip
     * (dimmed at the edge when they are outside the view), and the heading line shows the waypoint you are looking at.
     */
    static final class Compass implements HudWidget {

        private static final int W = 110, STRIP_H = 9, HEIGHT = STRIP_H + 1 + 4 + 2 + 9;
        private static final float SPAN = 100f;   // degrees visible across the strip

        private static final int MARK_H = 3;   // extra rows for the waypoint markers, only when there are some

        private record Mark(float off, int color, double dist, String name) {}

        private final List<Mark> marks = new ArrayList<>();
        private float bearing;
        private int degrees, markH;

        @Override
        public int[] size(Minecraft mc, Font font) {
            if (mc.player == null) return null;
            degrees = HudFormat.bearing(mc.player.getYRot());
            bearing = ((mc.player.getYRot() + 180f) % 360f + 360f) % 360f;
            marks.clear();
            if (mc.level != null && Config.isEnabled("waypoints")) {
                String dim = WaypointsModule.dimensionId(mc);
                for (Config.Waypoint wp : Config.waypoints()) {
                    if (!wp.visible || !dim.equals(wp.dim)) continue;
                    double dx = wp.x + 0.5 - mc.player.getX(), dz = wp.z + 0.5 - mc.player.getZ();
                    if (dx * dx + dz * dz < 0.25) continue;   // standing on it: it has no direction
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));   // Minecraft yaw towards the waypoint
                    float heading = ((yaw + 180f) % 360f + 360f) % 360f;       // same 0 = north scale as the strip
                    marks.add(new Mark(Mth.wrapDegrees(heading - bearing), wp.color, WaypointsModule.distanceTo(mc, wp), wp.name));
                }
                marks.sort(Comparator.comparingDouble(Mark::dist).reversed());   // far ones first, so near ones end up on top
            }
            markH = marks.isEmpty() ? 0 : MARK_H;
            return new int[]{W, HEIGHT + markH};
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {
            float half = SPAN / 2f, perDegree = W / SPAN;
            int lineY = y + STRIP_H + 1;
            Ui.fill(g, x, lineY, x + W, lineY + 1, 0x55FFFFFF);
            for (int d = 0; d < 360; d += 15) {
                float off = Mth.wrapDegrees(d - bearing);
                if (Math.abs(off) > half) continue;
                int px = Math.min(x + W - 1, x + Math.round(W / 2f + off * perDegree));
                boolean main = d % 45 == 0;
                float edge = Math.abs(off) / half;
                int alpha = Math.round(255f * (1f - 0.75f * edge * edge));
                Ui.fill(g, px, lineY + 1, px + 1, lineY + (main ? 4 : 3), (alpha << 24) | 0xFFFFFF);
                if (!main) continue;
                String name = HudFormat.pointName(d / 45);
                int w = font.width(name), lx = px - w / 2;
                if (lx < x || lx + w > x + W) continue;   // never draw outside the box
                int rgb = d == 0 ? (Ui.ACCENT & 0xFFFFFF) : 0xFFFFFF;
                Ui.txt(g, font, name, lx, y + 1, (alpha << 24) | rgb, true);
            }
            Ui.fill(g, x + W / 2, lineY - 1, x + W / 2 + 1, lineY + 5, Ui.ACCENT);   // where you are looking

            Mark ahead = null;   // the nearest waypoint you are looking at
            for (Mark m : marks) {
                float off = m.off();
                boolean inView = Math.abs(off) <= half;
                int px = Math.max(x + 2, Math.min(x + W - 3, x + Math.round(W / 2f + off * perDegree)));
                int argb = ((inView ? 0xFF : 0x80) << 24) | (m.color() & 0xFFFFFF);
                for (int r = 0; r < 3; r++) Ui.fill(g, px - r, lineY + 4 + r, px + r + 1, lineY + 5 + r, argb);   // little triangle under the strip
                if (Math.abs(off) <= 6f && (ahead == null || m.dist() < ahead.dist())) ahead = m;
            }

            int point = HudFormat.point(degrees);
            String axis = HudFormat.axis(point);
            String text = HudFormat.pointName(point) + (axis.isEmpty() ? "" : " (" + axis + ")") + " " + degrees + "\u00B0";
            int textColor = WHITE;
            if (ahead != null) {   // looking at a waypoint: show its name and distance instead of the heading
                String name = ahead.name(), dist = " " + WaypointsModule.formatDistance(ahead.dist());
                while (name.length() > 1 && font.width(name + dist) > W) name = name.substring(0, name.length() - 1);
                text = name + dist;
                textColor = 0xFF000000 | (ahead.color() & 0xFFFFFF);
            }
            Ui.txt(g, font, text, x + (W - font.width(text)) / 2, y + HEIGHT + markH - 8, textColor, true);
        }
    }

    // ---------------------------------------------------------------- biome + time

    /** The biome you stand in and, in the overworld, the in-game time of day. */
    static final class BiomeTime implements HudWidget {

        private String biome = "", time = "";

        @Override
        public int[] size(Minecraft mc, Font font) {
            if (mc.player == null || mc.level == null) return null;
            String id = mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
                    .map(Object::toString).map(HudFormat::keyId).orElse("");
            biome = id.isEmpty() ? "Unknown biome" : name(id);
            time = "";
            if (mc.level.dimension().equals(Level.OVERWORLD)) {   // other dimensions have no day and night to show
                long ticks = mc.level.getDefaultClockTime();
                time = HudFormat.clock(ticks) + " " + HudFormat.phase(ticks);
            }
            return new int[]{Math.max(font.width(biome), font.width(time)), time.isEmpty() ? 9 : 19};
        }

        private static String name(String id) {
            String key = HudFormat.biomeLangKey(id);
            String translated = Component.translatable(key).getString();
            return translated.equals(key) ? HudFormat.pretty(id) : translated;   // modded biomes often have no translation
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {
            Ui.txt(g, font, biome, x, y + 1, WHITE, true);
            if (!time.isEmpty()) Ui.txt(g, font, time, x, y + 11, SOFT, true);
        }
    }

    // ---------------------------------------------------------------- tournament players

    /**
     * Your friends in a tournament: a dot, the name and the status (alive, dead (in-game), dead (left), left), and under
     * each name the last few things that friend said, in full (long lines wrap). Newer messages are brighter, older
     * ones dimmer, and every message fades out on its own before it disappears. The data comes from the
     * Tournament HUD module; this only draws it.
     */
    static final class TournamentPlayers implements HudWidget {

        private static final int MAX = 8, ROW = 10, LINE = 9, DOT = 4, NAME_GAP = 7, COL_GAP = 8;
        private static final int WRAP_W = 190, MAX_WRAP_LINES = 4, LINE_BUDGET = 14, FADE_MS = 3000;
        private static final int GREEN = 0xFF55FF55, RED = 0xFFFF5555, GREY = 0xFF8E8E8E, DIM_RED = 0xFFCC6666;
        private static final float[] DIM = {1f, 0.7f, 0.5f};   // newest, one before, two before

        /** One message as drawn: its wrapped lines, its brightness and when it arrived (to drop the oldest first). */
        private static final class Block {
            final List<String> lines;
            int alpha;
            final long at;
            Block(List<String> lines, int alpha, long at) { this.lines = lines; this.alpha = alpha; this.at = at; }
        }

        private final List<TournamentHudModule.Row> rows = new ArrayList<>();
        private final List<List<Block>> blocks = new ArrayList<>();
        private String header = "", more = "";
        private int nameW, statusW, width;

        @Override
        public int[] size(Minecraft mc, Font font) {
            rows.clear();
            blocks.clear();
            more = "";
            if (mc.player == null || !TournamentHudModule.active()) return null;
            List<TournamentHudModule.Row> all = TournamentHudModule.rows();
            if (all.isEmpty()) return null;

            int alive = 0;
            for (TournamentHudModule.Row row : all) if (row.status() == TournamentHudModule.Status.ALIVE) alive++;
            header = "Alive " + alive + "/" + all.size();

            long now = Clock.ms(), window = Config.tournamentMessageSeconds() * 1000L;
            int shown = Math.min(all.size(), MAX), totalLines = 0;
            nameW = 0;
            statusW = 0;
            for (int i = 0; i < shown; i++) {
                TournamentHudModule.Row row = all.get(i);
                rows.add(row);
                nameW = Math.max(nameW, font.width(row.name()));
                statusW = Math.max(statusW, font.width(row.label()));

                List<TournamentHudModule.Msg> live = new ArrayList<>();
                for (TournamentHudModule.Msg msg : row.messages()) {
                    long age = now - msg.at();
                    if (age >= 0 && age < window) live.add(msg);
                }
                List<Block> mine = new ArrayList<>();
                for (int k = 0; k < live.size(); k++) {
                    TournamentHudModule.Msg msg = live.get(k);
                    int fromNewest = live.size() - 1 - k;
                    float dim = DIM[Math.min(fromNewest, DIM.length - 1)];
                    float fade = Math.min(1f, (window - (now - msg.at())) / (float) FADE_MS);   // fades out over its last 3 seconds
                    int alpha = Math.round(255f * dim * Math.max(0f, fade));
                    if (alpha < 12) continue;
                    List<String> wrapped = wrap(font, msg.text());
                    mine.add(new Block(wrapped, alpha, msg.at()));
                    totalLines += wrapped.size();
                }
                blocks.add(mine);
            }
            // too much on screen: the oldest messages go first
            while (totalLines > LINE_BUDGET) {
                Block oldest = null;
                List<Block> owner = null;
                for (List<Block> list : blocks) {
                    for (Block b : list) {
                        if (oldest == null || b.at < oldest.at) { oldest = b; owner = list; }
                    }
                }
                if (oldest == null) break;
                owner.remove(oldest);
                totalLines -= oldest.lines.size();
            }
            if (all.size() > MAX) more = "+" + (all.size() - MAX) + " more";

            width = DOT + 3 + nameW + COL_GAP + statusW;
            int height = ROW;                                       // the header
            for (int i = 0; i < rows.size(); i++) {
                height += ROW;
                for (Block b : blocks.get(i)) {
                    for (String line : b.lines) width = Math.max(width, NAME_GAP + font.width(line));
                    height += b.lines.size() * LINE;
                }
            }
            width = Math.max(width, Math.max(font.width(header), font.width(more)));
            if (!more.isEmpty()) height += ROW;
            return new int[]{width, height - 1};
        }

        /** Splits a message into lines that fit WRAP_W: at spaces, and inside a word only if the word alone is too wide. */
        private static List<String> wrap(Font font, String text) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ")) {
                if (word.isEmpty()) continue;
                while (font.width(word) > WRAP_W) {
                    String part = font.plainSubstrByWidth(word, WRAP_W);
                    if (part.isEmpty()) break;
                    if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
                    out.add(part);
                    word = word.substring(part.length());
                }
                if (word.isEmpty()) continue;
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (font.width(candidate) <= WRAP_W || current.length() == 0) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    out.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                }
            }
            if (current.length() > 0) out.add(current.toString());
            if (out.size() > MAX_WRAP_LINES) {                       // a very long message: cut it, with dots
                String last = out.get(MAX_WRAP_LINES - 1);
                out = new ArrayList<>(out.subList(0, MAX_WRAP_LINES));
                out.set(MAX_WRAP_LINES - 1, font.plainSubstrByWidth(last, WRAP_W - font.width("...")) + "...");
            }
            return out;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y) {
            Ui.txt(g, font, header, x, y + 1, Ui.MUTED, true);
            int ry = y + ROW;
            for (int i = 0; i < rows.size(); i++) {
                TournamentHudModule.Row row = rows.get(i);
                boolean left = row.status() == TournamentHudModule.Status.LEFT;
                int color = switch (row.status()) {
                    case ALIVE -> GREEN;
                    case DEAD -> RED;
                    case LEFT -> row.died() ? DIM_RED : GREY;
                };
                Ui.fill(g, x, ry + 3, x + DOT, ry + 3 + DOT, color);
                Ui.txt(g, font, row.name(), x + NAME_GAP, ry + 1, left ? GREY : WHITE, true);
                Ui.txt(g, font, row.label(), x + width - font.width(row.label()), ry + 1, color, true);
                ry += ROW;
                for (Block b : blocks.get(i)) {
                    for (String line : b.lines) {
                        Ui.txt(g, font, line, x + NAME_GAP, ry, (b.alpha << 24) | 0xE0E0E0, true);
                        ry += LINE;
                    }
                }
            }
            if (!more.isEmpty()) Ui.txt(g, font, more, x, ry + 1, Ui.MUTED, true);
        }
    }
}
