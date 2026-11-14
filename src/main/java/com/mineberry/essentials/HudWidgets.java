package com.mineberry.essentials;

import net.minecraft.client.KeyMapping;
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

/** The multi-line HUD mods: Keystrokes/CPS, Armor Status, Potion Effects, Compass and Biome/Time. */
final class HudWidgets {

    private static final int WHITE = 0xFFFFFFFF, SOFT = 0xFFC8C8C8;

    private HudWidgets() {}

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
            Ui.rect(g, x, y, w, h, down ? Ui.ACCENT : 0x66FFFFFF, down ? ((Ui.ACCENT & 0xFFFFFF) | 0x99000000) : 0x40000000);
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

    /** A scrolling strip of N / NE / E... that follows where you look, with the exact heading and world axis below it. */
    static final class Compass implements HudWidget {

        private static final int W = 110, STRIP_H = 9, HEIGHT = STRIP_H + 1 + 4 + 2 + 9;
        private static final float SPAN = 100f;   // degrees visible across the strip

        private float bearing;
        private int degrees;

        @Override
        public int[] size(Minecraft mc, Font font) {
            if (mc.player == null) return null;
            degrees = HudFormat.bearing(mc.player.getYRot());
            bearing = ((mc.player.getYRot() + 180f) % 360f + 360f) % 360f;
            return new int[]{W, HEIGHT};
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

            int point = HudFormat.point(degrees);
            String axis = HudFormat.axis(point);
            String text = HudFormat.pointName(point) + (axis.isEmpty() ? "" : " (" + axis + ")") + " " + degrees + "\u00B0";
            Ui.txt(g, font, text, x + (W - font.width(text)) / 2, y + HEIGHT - 8, WHITE, true);
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
}
