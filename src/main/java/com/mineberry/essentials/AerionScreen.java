package com.mineberry.essentials;

import com.mineberry.essentials.AerionTalk.Msg;
import com.mineberry.essentials.AerionTalk.Session;
import com.mineberry.essentials.modules.AirdropModule;
import com.mineberry.essentials.modules.AirdropParser;
import com.mineberry.essentials.modules.ServerStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chat with Aerion, the info guide for one server (MineBerry or MinePeak). Type a question and press Enter, or tap a
 * quick-question button. The answers come from {@link AerionBrain}, which works offline.
 *
 * Aerion shows "..." for a moment before it answers, longer for a longer answer, like a person typing. The answer is
 * stored in the conversation right away, so closing the screen never loses it.
 *
 * Enter is read from GLFW once per frame, like the other screens do with the mouse, so no Screen key method is overridden.
 * The conversation is kept per server until the game closes; "Clear chat" starts over.
 */
public class AerionScreen extends Screen {

    private static final int PANEL_W = 420, HEADER_H = 20, MARGIN = 14, BTN_H = 20, CHIP_H = 18, SEND_W = 54,
            LINE_H = 11, MSG_GAP = 5, PAD_X = 6, MAX_MESSAGES = 80;
    /** "Typing" time before an answer: a base plus a little per letter, never more than the cap. */
    private static final long TYPING_BASE_MS = 300, TYPING_PER_CHAR_MS = 1, TYPING_MAX_MS = 500;

    /** One wrapped line of a message. x and y are relative to the chat area. */
    private record VLine(String text, boolean user, int x, int y, int w, Msg msg, int start) {}

    private final Screen parent;
    private final String serverId;
    private Button defaultButton;
    private final AerionBrain brain;
    private final List<Msg> messages;
    private final AerionBrain.Chat chat;
    private final List<VLine> lines = new ArrayList<>();
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private EditBox input;

    private String draft = "";
    /** An answer that is "being typed": already worked out, shown when pendingAt has passed. */
    private String pendingText;
    private long pendingAt;
    /** True while Aerion waits for the server's answer to /airdrop (shown as "..." like typing). */
    private boolean waiting;
    private int scroll = 0, contentH = 0;
    private boolean stickToBottom = true, prevEnter = false, prevMouse = false;
    private long copiedUntil;   // "Copied!" shows in the header until then

    private int px, py, pw, ph, chatY, chatH, chipsY, inputY, footerY;

    public AerionScreen(Screen parent, String serverId) {
        super(Component.literal("Aerion"));
        this.parent = parent;
        this.serverId = serverId;
        this.brain = AerionBrain.forServer(serverId);
        Session session = AerionTalk.session(serverId);   // shared with the HUD mini chat and !aerion
        this.messages = session.messages();
        this.chat = session.chat();
        if (messages.isEmpty()) messages.add(new Msg(false, brain.greeting()));
    }

    // ---------------------------------------------------------------- setup

    @Override
    protected void init() {
        Ui.applyTheme();
        this.addRenderableOnly((g, mx, my, d) -> Ui.ghostAll(this));   // first thing drawn: hides the vanilla buttons before THEY are drawn (no flash on open)
        if (input != null) draft = input.getValue();
        widgets.clear();

        boolean chips = !brain.suggestions().isEmpty();
        pw = Math.min(PANEL_W, this.width - 20);
        ph = Math.max(210, Math.min(310, this.height - 16));
        footerY = ph - 34;
        inputY = footerY - 6 - BTN_H;
        chipsY = inputY - 4 - CHIP_H;
        chatY = HEADER_H + 8;
        chatH = (chips ? chipsY : inputY) - 6 - chatY;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);
        int left = px + MARGIN, right = px + pw - MARGIN;

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, mouseX, mouseY));

        // quick questions
        if (chips) {
            int x = left;
            for (AerionBrain.Suggestion s : brain.suggestions()) {
                int w = this.font.width(s.label()) + 16;
                if (x + w > right) break;
                add(Button.builder(Component.literal(s.label()), button -> { submit(s.ask()); this.setInitialFocus(input); })
                        .bounds(x, py + chipsY, w, CHIP_H).build());
                x += w + 4;
            }
        }

        // input + send
        input = new EditBox(this.font, left + 4, py + inputY + 6, right - left - SEND_W - 4 - 8, 12, Component.literal("Ask Aerion"));
        input.setBordered(false);
        input.setMaxLength(120);
        input.setValue(draft);
        add(input);
        this.setInitialFocus(input);
        add(Button.builder(Component.literal("Send"), button -> { submit(input.getValue()); this.setInitialFocus(input); })
                .bounds(right - SEND_W, py + inputY, SEND_W, BTN_H).build());

        // footer
        add(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(left, py + footerY + 7, 56, BTN_H).build());
        add(Button.builder(Component.literal("Clear chat"), button -> clearChat())
                .bounds(left + 60, py + footerY + 7, 72, BTN_H).build());
        defaultButton = add(Button.builder(defaultLabel(), button -> {
                    Config.setAerionDefault(serverId);   // !aerion and the mini chat now talk to this Aerion
                    button.setMessage(defaultLabel());
                }).bounds(left + 136, py + footerY + 7, 96, BTN_H).build());

        // painted over the vanilla buttons (added last so it renders on top)
        this.addRenderableOnly((g, mouseX, mouseY, delta) ->
                Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));

        prevEnter = enterDown();   // an Enter that was already held must not send anything
        prevMouse = mouseDown();
        rebuildLines();
        scrollToBottom();
    }

    /** "Make default", or "Default" when this Aerion already answers !aerion and the mini chat. */
    private Component defaultLabel() {
        return Component.literal(Config.aerionDefault().equals(serverId) ? "\u2605 Default" : "Make default");
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    // ---------------------------------------------------------------- conversation

    private void submit(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        commitPending();   // a new message while Aerion is still "typing" shows the old answer first
        AerionTalk.finishTyping();
        messages.add(new Msg(true, text));
        Optional<String> done = AerionActions.tryHandle(text);   // "turn off compass", "convert 800 -1600 to nether", "undo", yes / no
        if (done.isPresent()) {
            pendingText = done.get();
            pendingAt = System.currentTimeMillis() + Math.min(TYPING_MAX_MS, TYPING_BASE_MS + TYPING_PER_CHAR_MS * pendingText.length());
        } else if (AirdropParser.asksAboutAirdrop(text)) {
            askServerAboutAirdrop(AirdropParser.wantsWaypoint(text));   // runs /airdrop in game, the answer comes from the server
        } else if (ServerStats.asks(text)) {
            askServerStats();   // runs /gc in game
        } else {
            pendingText = chat.say(text);
            if (chat.wasUnknown()) AerionLog.unanswered(serverId, text);
            pendingAt = System.currentTimeMillis() + Math.min(TYPING_MAX_MS, TYPING_BASE_MS + TYPING_PER_CHAR_MS * pendingText.length());
        }
        input.setValue("");
        draft = "";
        rebuildLines();
        stickToBottom = true;
        scrollToBottom();
    }

    /** "airdrop info": Aerion runs /airdrop and answers with what the server says. */
    private void askServerAboutAirdrop(boolean addWaypoint) {
        waiting = true;
        AirdropModule.requestInfo(answer -> {
            waiting = false;
            Msg reply = new Msg(false, answer);
            messages.add(reply);
            AerionTalk.startTyping(reply);
            while (messages.size() > MAX_MESSAGES) messages.remove(0);
            if (this.minecraft != null && this.minecraft.gui.screen() == this) {
                rebuildLines();
                if (stickToBottom) scrollToBottom();
            }
        }, addWaypoint);
    }

    /** "server stats": Aerion runs /gc and answers with what the server says (or that a higher rank is needed). */
    private void askServerStats() {
        waiting = true;
        ServerStats.requestStats(answer -> {
            waiting = false;
            Msg reply = new Msg(false, answer);
            messages.add(reply);
            AerionTalk.startTyping(reply);
            while (messages.size() > MAX_MESSAGES) messages.remove(0);
            if (this.minecraft != null && this.minecraft.gui.screen() == this) {
                rebuildLines();
                if (stickToBottom) scrollToBottom();
            }
        });
    }

    /** Puts the answer that was being "typed" into the conversation. */
    private void commitPending() {
        if (pendingText == null) return;
        Msg reply = new Msg(false, pendingText);
        messages.add(reply);
        AerionTalk.startTyping(reply);   // the answer is written out letter by letter
        pendingText = null;
        while (messages.size() > MAX_MESSAGES) messages.remove(0);
        rebuildLines();
        if (stickToBottom) scrollToBottom();
    }

    private void clearChat() {
        AerionTalk.finishTyping();
        pendingText = null;
        waiting = false;
        chat.reset();
        messages.clear();
        messages.add(new Msg(false, brain.greeting()));
        rebuildLines();
        stickToBottom = true;
        scrollToBottom();
    }

    // ---------------------------------------------------------------- layout of the messages

    private void rebuildLines() {
        lines.clear();
        int chatW = pw - 2 * MARGIN;
        int maxTextW = Math.max(60, (int) ((chatW - 8) * 0.8) - 2 * PAD_X);
        int y = 4;
        List<Msg> shown = new ArrayList<>(messages);
        if (pendingText != null || waiting) shown.add(new Msg(false, "..."));   // Aerion is typing
        for (Msg m : shown) {
            List<String> wrapped = wrap(m.text(), maxTextW);
            int textW = 0;
            for (String s : wrapped) textW = Math.max(textW, this.font.width(s));
            int bubbleW = textW + 2 * PAD_X + (m.user() ? 0 : 2);   // the guide's bubbles have a 2px accent bar on the left
            int x = m.user() ? chatW - 4 - bubbleW : 4;
            int start = 0;
            for (String s : wrapped) {
                lines.add(new VLine(s, m.user(), x, y, bubbleW, m, start));
                start += s.length() + 1;
                y += LINE_H;
            }
            y += MSG_GAP;
        }
        contentH = y;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    /** Word wrap by pixel width. Explicit line breaks are kept, a word longer than a line is cut. */
    private List<String> wrap(String text, int maxW) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) { out.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) continue;
                while (this.font.width(word) > maxW) {
                    if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                    String cut = this.font.plainSubstrByWidth(word, maxW);
                    if (cut.isEmpty()) cut = word.substring(0, 1);
                    out.add(cut);
                    word = word.substring(cut.length());
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (this.font.width(candidate) <= maxW) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private int maxScroll() {
        return Math.max(0, contentH - chatH);
    }

    private void scrollToBottom() {
        scroll = maxScroll();
    }

    /** Mouse wheel scrolls the conversation. */
    private void scrollBy(double wheel) {
        if (wheel == 0 || maxScroll() == 0) return;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(wheel) * LINE_H * 3));
        stickToBottom = scroll >= maxScroll();
    }

    // ---------------------------------------------------------------- Enter key

    private static boolean enterDown() {
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS);
    }

    private static boolean mouseDown() {
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private void pollEnter() {
        boolean down = enterDown();
        if (down && !prevEnter && input != null && input.isFocused()) submit(input.getValue());
        prevEnter = down;
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        pollEnter();
        if (pendingText != null && System.currentTimeMillis() >= pendingAt) commitPending();
        int left = px + MARGIN, right = px + pw - MARGIN, chatW = pw - 2 * MARGIN;

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);
        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "Aerion", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, brain.serverName(), left + this.font.width("Aerion") + 6, headerTextY, Ui.MUTED, false);

        // conversation
        Ui.field(g, left, py + chatY, chatW, chatH, false);

        // a command (a line that starts with "/") is copied with a click
        VLine hover = null;
        String hoverCmd = null;
        if (mouseX >= left && mouseX < left + chatW && mouseY >= py + chatY && mouseY < py + chatY + chatH) {
            for (VLine line : lines) {
                if (line.user()) continue;
                int y = line.y() - scroll;
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
        if (System.currentTimeMillis() < copiedUntil) {
            String done = "Copied!";
            Ui.txt(g, this.font, done, right - this.font.width(done), headerTextY, 0xFF6EE08C, false);
        } else {
            Ui.headerRight(g, this.font, right, headerTextY, hoverCmd != null ? "Click to copy" : "built-in guide, works offline");
        }

        for (VLine line : lines) {
            int y = line.y() - scroll;
            if (y < 0 || y + LINE_H > chatH) continue;   // only whole lines are drawn
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
            int trackX = left + chatW - 3, thumbH = Math.max(12, chatH * chatH / contentH);
            int thumbY = py + chatY + (chatH - thumbH) * scroll / maxScroll();
            Ui.fill(g, trackX, py + chatY + 1, trackX + 2, py + chatY + chatH - 1, Ui.DIVIDER);
            Ui.fill(g, trackX, thumbY, trackX + 2, thumbY + thumbH, Ui.STRONG);
        }

        // input
        Ui.inputBox(g, input);
        if (input.getValue().isEmpty() && !input.isFocused()) {
            Ui.txt(g, this.font, "Ask a question, or just say hi", left + 4, py + inputY + 6, Ui.MUTED, false);
        }
        Ui.footerLine(g, left, right, py + footerY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollBy(scrollY);
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        commitPending();
        AerionTalk.finishTyping();
        this.minecraft.gui.setScreen(parent);
    }
}
