package com.mineberry.essentials;

import com.mineberry.essentials.modules.AerionMiniChatModule;
import com.mineberry.essentials.modules.AirdropParser;
import com.mineberry.essentials.modules.ServerStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A small chat with Aerion on the left side of the HUD. Opened with ` (rebindable) or from the radial menu; ` or Esc
 * closes it, Enter sends. It is the same conversation as the Aerion tab and {@code !aerion} (see AerionTalk).
 *
 * Like the other screens here it reads Enter (and the open key) from GLFW once per frame, so no Screen key method is
 * overridden. It never pauses a singleplayer game.
 */
public class AerionMiniScreen extends Screen {

    private static final int W = 260, H = 170, HEADER_H = 16, LINE_H = 11, PAD_X = 6, MSG_GAP = 4, INPUT_H = 16;

    private record VLine(String text, boolean user, int x, int y, int w, AerionTalk.Msg msg, int start) {}

    private final String serverId = AerionTalk.currentServerId();
    private final AerionTalk.Session session = AerionTalk.session(serverId);
    private final List<VLine> lines = new ArrayList<>();
    private EditBox input;
    private String draft = "";
    private boolean prevEnter, prevClose, waiting, prevMouse;
    private int scrollUp;               // how far the view is scrolled up from the newest message, in pixels
    private long copiedUntil;           // "Copied!" shows in the header until then
    private int builtFor = -1, contentH, chatH;
    private AerionTalk.Msg builtLast;   // the newest message at the last layout: the list stops growing once it is capped
    private boolean builtWaiting;
    private int px, py, pw, ph, chatY, inputY;

    public AerionMiniScreen() {
        super(Component.literal("Aerion"));
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        if (input != null) draft = input.getValue();
        pw = Math.min(W, this.width - 12);
        ph = Math.min(H, this.height - 20);
        px = 6;
        py = (this.height - ph) / 2;
        inputY = ph - INPUT_H - 6;
        chatY = HEADER_H + 5;
        chatH = inputY - 4 - chatY;

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, mouseX, mouseY));
        input = new EditBox(this.font, px + 10, py + inputY + 4, pw - 20, 12, Component.literal("Ask Aerion"));
        input.setBordered(false);
        input.setMaxLength(120);
        input.setValue(draft);
        this.addRenderableWidget(input);
        this.setInitialFocus(input);

        prevEnter = enterDown();        // keys that are already held must not send or close anything
        prevClose = closeDown();
        prevMouse = mouseDown();        // same for a click that opened this
        builtFor = -1;
    }

    /** Never pauses a singleplayer game. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- keys

    private static boolean down(int key) {
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    private static boolean enterDown() {
        return down(GLFW.GLFW_KEY_ENTER) || down(GLFW.GLFW_KEY_KP_ENTER);
    }

    private static boolean mouseDown() {
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private static boolean closeDown() {
        return down(AerionMiniChatModule.closeKeyCode());
    }

    private void pollKeys() {
        boolean enter = enterDown();
        if (enter && !prevEnter && input != null && input.isFocused()) submit(input.getValue());
        prevEnter = enter;
        boolean close = AerionMiniChatModule.closesWithKey() && closeDown();
        if (close && !prevClose) { onClose(); return; }
        prevClose = close;
        if (input != null && input.getValue().indexOf('`') >= 0) input.setValue(input.getValue().replace("`", ""));
    }

    // ---------------------------------------------------------------- conversation

    private void submit(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        input.setValue("");
        draft = "";
        waiting = AirdropParser.asksAboutAirdrop(text) || ServerStats.asks(text);   // /airdrop and /gc take a moment, the rest answers at once
        scrollUp = 0;
        AerionTalk.ask(serverId, text, answer -> {
            waiting = false;
            AerionMiniChatModule.toast(answer);   // read it on the HUD even if you already closed this
        });
        builtFor = -1;
    }

    private void rebuildLines() {
        lines.clear();
        int boxW = pw - 12;
        int maxTextW = Math.max(60, (int) ((boxW - 8) * 0.85) - 2 * PAD_X);
        List<AerionTalk.Msg> shown = new ArrayList<>(session.messages());
        if (waiting) shown.add(new AerionTalk.Msg(false, "..."));
        int oldH = contentH;
        int y = 4;
        for (AerionTalk.Msg m : shown) {
            List<String> wrapped = AerionTalk.wrap(this.font, m.text(), maxTextW);
            int textW = 0;
            for (String s : wrapped) textW = Math.max(textW, this.font.width(s));
            int bubbleW = textW + 2 * PAD_X + (m.user() ? 0 : 2);
            int x = m.user() ? boxW - 4 - bubbleW : 4;
            int start = 0;
            for (String s : wrapped) {
                lines.add(new VLine(s, m.user(), x, y, bubbleW, m, start));
                start += s.length() + 1;
                y += LINE_H;
            }
            y += MSG_GAP;
        }
        contentH = y;
        if (scrollUp > 0 && oldH > 0) scrollUp += contentH - oldH;   // keep what you are reading in place when something new arrives
        scrollUp = Math.max(0, Math.min(scrollUp, maxScroll()));
        builtFor = session.messages().size();
        builtLast = session.messages().isEmpty() ? null : session.messages().get(session.messages().size() - 1);
        builtWaiting = waiting;
    }

    private int maxScroll() {
        return Math.max(0, contentH - chatH);
    }

    /** Mouse wheel scrolls the conversation (wheel up = older messages). */
    private void scrollBy(double wheel) {
        if (wheel == 0 || maxScroll() == 0) return;
        scrollUp = Math.max(0, Math.min(maxScroll(), scrollUp + (int) Math.signum(wheel) * LINE_H * 3));
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        pollKeys();
        List<AerionTalk.Msg> all = session.messages();
        if (builtFor != all.size() || builtWaiting != waiting || (all.isEmpty() ? null : all.get(all.size() - 1)) != builtLast) rebuildLines();
        int left = px + 6, boxW = pw - 12;

        Ui.frame(g, px, py, pw, ph, HEADER_H);
        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "Aerion", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, AerionBrain.forServer(serverId).serverName(), left + this.font.width("Aerion") + 6, headerTextY, Ui.MUTED, false);

        Ui.field(g, left, py + chatY, boxW, chatH, false);
        scrollUp = Math.max(0, Math.min(scrollUp, maxScroll()));
        int offset = maxScroll() - scrollUp;   // scrollUp 0 shows the newest messages at the bottom

        // a command (a line that starts with "/") is copied with a click
        VLine hover = null;
        String hoverCmd = null;
        if (mouseX >= left && mouseX < left + boxW && mouseY >= py + chatY && mouseY < py + chatY + chatH) {
            for (VLine line : lines) {
                if (line.user()) continue;
                int y = line.y() - offset;
                if (y < 0 || y + LINE_H > chatH) continue;
                int x = left + line.x(), top = py + chatY + y;
                if (mouseX >= x && mouseX < x + line.w() && mouseY >= top && mouseY < top + LINE_H) { hover = line; break; }
            }
            if (hover != null) hoverCmd = AerionTalk.commandOf(hover.msg(), hover.start());
        }
        boolean mouse = mouseDown();
        if (mouse && !prevMouse && hoverCmd != null) {
            Minecraft.getInstance().keyboardHandler.setClipboard(hoverCmd);
            copiedUntil = System.currentTimeMillis() + 1500;
            if (input != null) this.setFocused(input);   // the click must not take the typing focus away
        }
        prevMouse = mouse;
        boolean copied = System.currentTimeMillis() < copiedUntil;
        if (copied) {
            String done = "Copied!";
            Ui.txt(g, this.font, done, px + pw - 6 - this.font.width(done), headerTextY, 0xFF6EE08C, false);
        } else {
            Ui.headerRight(g, this.font, px + pw - 6, headerTextY, hoverCmd != null ? "Click to copy"
                    : AerionMiniChatModule.closesWithKey() ? AerionMiniChatModule.keyName() + " to close" : "Esc to close");
        }

        for (VLine line : lines) {
            int y = line.y() - offset;
            if (y < 0 || y + LINE_H > chatH) continue;
            int x = left + line.x(), top = py + chatY + y;
            if (line.user()) {
                Ui.fill(g, x, top, x + line.w(), top + LINE_H, Ui.TRACK_ON);
                Ui.txt(g, this.font, line.text(), x + PAD_X, top + 2, Ui.TEXT, false);
            } else {
                String typed = AerionTalk.typed(line.msg(), line.text(), line.start());
                if (typed == null) continue;   // the typewriter has not reached this line yet
                String cmd = AerionTalk.commandOf(line.msg(), line.start());
                boolean hot = cmd != null && hover != null && line.msg() == hover.msg() && cmd.equals(hoverCmd);
                Ui.fill(g, x, top, x + line.w(), top + LINE_H, hot ? Ui.TRACK_ON : Ui.BTN);
                Ui.fill(g, x, top, x + 2, top + LINE_H, Ui.ACCENT);
                Ui.txt(g, this.font, typed, x + 2 + PAD_X, top + 2, cmd != null ? Ui.ACCENT : Ui.TEXT, false);
                if (AerionTalk.caret(line.msg(), line.text(), line.start())) {
                    Ui.txt(g, this.font, "_", x + 2 + PAD_X + this.font.width(typed), top + 2, Ui.ACCENT, false);
                }
            }
        }
        if (maxScroll() > 0) {   // scrollbar
            int trackX = left + boxW - 3, thumbH = Math.max(12, chatH * chatH / contentH);
            int thumbY = py + chatY + (chatH - thumbH) * (maxScroll() - scrollUp) / maxScroll();
            Ui.fill(g, trackX, py + chatY + 1, trackX + 2, py + chatY + chatH - 1, Ui.DIVIDER);
            Ui.fill(g, trackX, thumbY, trackX + 2, thumbY + thumbH, Ui.STRONG);
        }
        Ui.inputBox(g, input);
        if (input.getValue().isEmpty() && !input.isFocused()) {
            Ui.txt(g, this.font, "Ask Aerion", px + 10, py + inputY + 6, Ui.MUTED, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollBy(scrollY);
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}
