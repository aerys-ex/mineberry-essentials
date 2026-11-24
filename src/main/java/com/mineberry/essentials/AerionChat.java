package com.mineberry.essentials;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * How Aerion looks in the normal chat: a pink-to-violet name with a little star, a soft arrow, then the message.
 * Every place that makes Aerion talk in chat goes through here, so he always looks the same.
 *
 * <pre>
 *   you » airdrop info
 *   ✦ Aerion » Airdrop (Easy) is out now, the boss fight is going.
 *     Aerion ┃ Location: X: -1579, Y: 63, Z: 2782
 * </pre>
 */
public final class AerionChat {

    private static final int PINK = 0xFF8FBF;
    private static final int VIOLET = 0xB784FF;
    private static final int SOFT = 0x7A6A9A;
    private static final String NAME = "Aerion";

    private AerionChat() { }

    /** One Aerion line in chat. */
    public static void say(Minecraft mc, String text, ChatFormatting color) {
        if (mc.player == null) return;
        mc.player.sendSystemMessage(prefix().append(body(text, color)));
    }

    /** An answer that may have several lines: the first has the full name, the rest line up under it with a thin bar. */
    public static void sayLines(Minecraft mc, String answer, ChatFormatting color) {
        if (mc.player == null) return;
        boolean first = true;
        for (String line : answer.split("\n")) {
            if (line.isBlank()) continue;
            MutableComponent head = first ? prefix() : continuation();
            mc.player.sendSystemMessage(head.append(body(line, color)));
            first = false;
        }
    }

    /** What the player asked, shown quietly before the answer. */
    public static void echo(Minecraft mc, String question) {
        if (mc.player == null) return;
        MutableComponent out = Component.literal("you ").withStyle(Style.EMPTY.withColor(SOFT).withItalic(Boolean.TRUE));
        out.append(Component.literal("» ").withStyle(Style.EMPTY.withColor(SOFT)));
        out.append(Component.literal(question).withStyle(Style.EMPTY.withColor(0xB8B8C8).withItalic(Boolean.TRUE)));
        mc.player.sendSystemMessage(out);
    }

    private static MutableComponent body(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color == null ? ChatFormatting.WHITE : color);
    }

    private static MutableComponent prefix() {
        MutableComponent out = Component.literal("\u2726 ").withStyle(Style.EMPTY.withColor(PINK));
        int n = NAME.length();
        for (int i = 0; i < n; i++) {
            int rgb = mix(PINK, VIOLET, n == 1 ? 0f : i / (float) (n - 1));
            out.append(Component.literal(String.valueOf(NAME.charAt(i)))
                    .withStyle(Style.EMPTY.withColor(rgb).withBold(Boolean.TRUE)));
        }
        out.append(Component.literal(" \u00bb ").withStyle(Style.EMPTY.withColor(SOFT)));
        return out;
    }

    private static MutableComponent continuation() {
        // The dim name is on purpose: the airdrop reader skips lines that start with letters it does not know,
        // so Aerion's own "Location: ..." line is never mistaken for one from the server.
        return Component.literal("  " + NAME + " \u2503 ").withStyle(Style.EMPTY.withColor(SOFT));
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
