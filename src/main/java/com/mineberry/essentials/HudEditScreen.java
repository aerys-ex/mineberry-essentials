package com.mineberry.essentials;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * "Edit HUD Layout": every enabled box is shown where it really is. Drag a box anywhere (it snaps to the screen edges, the
 * centre and the other boxes, with guide lines), right-click a box to put it back in the automatic stack. A box stays
 * attached to the corner nearest to where you drop it, so the layout survives window and GUI scale changes. Boxes that overlap
 * get a red outline. Scroll the mouse wheel over a box to resize it (50-250%); right-click puts position and size back.
 * The vanilla scoreboard has no box here (the game draws it, you can see it behind the dim layer): its switch and size are the
 * buttons at the bottom.
 *
 * Mouse buttons are polled from GLFW once per frame (like the radial menu does) instead of overriding the Screen click methods.
 */
public class HudEditScreen extends Screen {

    private static final int EDGE = 6, GAP = 3, H = 20, SNAP = 5, MIN_MOVE = 3, SCALE_STEP = 10;
    private static final int RED = 0xFFFF4D4D;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private List<ClientHud.Placed> placed = List.of();

    private HudMod grabbed;                 // the box being dragged
    private int grabW, grabH, grabDx, grabDy, startX, startY, dragX, dragY;
    private boolean moved;
    private boolean prevLeft, prevRight;
    private final List<Integer> guideX = new ArrayList<>(), guideY = new ArrayList<>();
    private Button sbToggle, sbSmaller, sbSize, sbBigger;   // the scoreboard row

    public HudEditScreen(Screen parent) {
        super(Component.literal("Edit HUD Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        this.addRenderableOnly((g, mx, my, d) -> Ui.ghostAll(this));   // first thing drawn: hides the vanilla buttons before THEY are drawn (no flash on open)
        widgets.clear();
        grabbed = null;
        prevLeft = mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);   // the click that opened this screen must not start a drag
        prevRight = mouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        this.addRenderableOnly((g, mx, my, d) -> {
            Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
            String hint = "Drag a box to move it   |   Scroll over it to resize   |   Right-click to reset";
            Ui.txt(g, this.font, hint, (this.width - this.font.width(hint)) / 2, this.height / 2 - 4, Ui.TEXT, true);

            placed = ClientHud.layout(this.minecraft, this.font, this.width, this.height, true, grabbed);
            handleMouse(mx, my);
            if (grabbed != null) {   // show the dragged box at the cursor
                for (int i = 0; i < placed.size(); i++) {
                    ClientHud.Placed p = placed.get(i);
                    if (p.mod() == grabbed) placed.set(i, new ClientHud.Placed(p.mod(), dragX, dragY, p.w(), p.h(), p.text(), p.size(), true, p.scale()));
                }
            }
            drawBoxes(g, mx, my);
        });

        Button reset = Button.builder(Component.literal("Reset all"), btn -> {
            for (HudMod mod : ClientHud.MODS) {
                Config.clearHudPos(mod.id());
                Config.setHudScalePct(mod.id(), 100);
            }
            ClientHud.sync();
            refreshScoreboardRow();
        }).bounds(this.width / 2 - 84, this.height / 2 + 12, 80, H).build();
        Button done = Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 + 4, this.height / 2 + 12, 80, H).build();
        widgets.add(reset);
        widgets.add(done);
        this.addRenderableWidget(reset);
        this.addRenderableWidget(done);

        // the vanilla scoreboard: hide / show, smaller, size (click = 100%), bigger
        int rowY = this.height / 2 + 12 + H + 6, rowX = this.width / 2 - 105;
        sbToggle = Button.builder(Component.literal(""), btn -> {
            ClientHud.SCOREBOARD.setEnabled(!ClientHud.SCOREBOARD.enabled());
            ClientHud.sync();
            refreshScoreboardRow();
        }).bounds(rowX, rowY, 110, H).build();
        sbSmaller = Button.builder(Component.literal("-"), btn -> changeScoreboardSize(-SCALE_STEP)).bounds(rowX + 114, rowY, 20, H).build();
        sbSize = Button.builder(Component.literal(""), btn -> changeScoreboardSize(100 - ClientHud.SCOREBOARD.scalePct()))
                .bounds(rowX + 136, rowY, 48, H).build();
        sbBigger = Button.builder(Component.literal("+"), btn -> changeScoreboardSize(SCALE_STEP)).bounds(rowX + 186, rowY, 24, H).build();
        for (Button b : List.of(sbToggle, sbSmaller, sbSize, sbBigger)) {
            widgets.add(b);
            this.addRenderableWidget(b);
        }
        refreshScoreboardRow();
        this.addRenderableOnly((g, mx, my, d) -> Ui.buttons(g, this.font, widgets, new HashMap<>(), mx, my));
    }

    // ---------------------------------------------------------------- scoreboard row

    private void changeScoreboardSize(int deltaPct) {
        Config.setHudScalePct(ClientHud.SCOREBOARD.id(), ClientHud.SCOREBOARD.scalePct() + deltaPct);
        ClientHud.sync();
        refreshScoreboardRow();
    }

    private void refreshScoreboardRow() {
        boolean on = ClientHud.SCOREBOARD.enabled();
        sbToggle.setMessage(Component.literal(on ? "Scoreboard: Shown" : "Scoreboard: Hidden"));
        sbSize.setMessage(Component.literal(ClientHud.SCOREBOARD.scalePct() + "%"));
        sbSmaller.active = on;
        sbSize.active = on;
        sbBigger.active = on;
    }

    // ---------------------------------------------------------------- mouse

    /** Mouse wheel over a box: 10% bigger / smaller. */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || grabbed != null) return false;
        ClientHud.Placed hit = boxAt((int) mouseX, (int) mouseY);
        if (hit == null) return false;
        Config.setHudScalePct(hit.mod().id(), hit.mod().scalePct() + (scrollY > 0 ? SCALE_STEP : -SCALE_STEP));
        ClientHud.sync();
        return true;
    }

    /** Asks GLFW whether a mouse button is physically down (Minecraft does not track held buttons while a screen is open). */
    private static boolean mouseDown(int button) {
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
    }

    private void handleMouse(int mx, int my) {
        boolean left = mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean right = mouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean overButton = false;
        for (AbstractWidget b : widgets) if (b.isMouseOver(mx, my)) overButton = true;

        if (!overButton && left && !prevLeft) {
            ClientHud.Placed hit = boxAt(mx, my);
            if (hit != null) {
                grabbed = hit.mod();
                grabW = hit.w();
                grabH = hit.h();
                grabDx = mx - hit.x();
                grabDy = my - hit.y();
                startX = dragX = hit.x();
                startY = dragY = hit.y();
                moved = false;
            }
        }
        if (!overButton && right && !prevRight && grabbed == null) {
            ClientHud.Placed hit = boxAt(mx, my);
            if (hit != null) {
                Config.clearHudPos(hit.mod().id());
                Config.setHudScalePct(hit.mod().id(), 100);
                ClientHud.sync();
            }
        }

        guideX.clear();
        guideY.clear();
        if (grabbed != null) {
            if (left) {
                int nx = mx - grabDx, ny = my - grabDy;
                if (!moved && Math.abs(nx - startX) < MIN_MOVE && Math.abs(ny - startY) < MIN_MOVE) {
                    nx = startX;   // a plain click must not move or freeze the box
                    ny = startY;
                } else {
                    moved = true;
                    nx = snapAxis(nx, grabW, this.width, true, guideX);
                    ny = snapAxis(ny, grabH, this.height, false, guideY);
                }
                dragX = Math.max(0, Math.min(nx, this.width - grabW));
                dragY = Math.max(0, Math.min(ny, this.height - grabH));
            } else {
                if (moved) drop();
                grabbed = null;
            }
        }
        prevLeft = left;
        prevRight = right;
    }

    /** The topmost box under the cursor (the last one drawn). */
    private ClientHud.Placed boxAt(int mx, int my) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            ClientHud.Placed p = placed.get(i);
            if (mx >= p.x() && mx < p.x() + p.w() && my >= p.y() && my < p.y() + p.h()) return p;
        }
        return null;
    }

    /** Saves the dropped box relative to the corner nearest to its centre. */
    private void drop() {
        boolean right = dragX + grabW / 2 >= this.width / 2, bottom = dragY + grabH / 2 >= this.height / 2;
        int corner = (right ? 1 : 0) + (bottom ? 2 : 0);
        int dx = right ? this.width - grabW - dragX : dragX;
        int dy = bottom ? this.height - grabH - dragY : dragY;
        Config.setHudPos(grabbed.id(), corner, Math.max(0, dx), Math.max(0, dy));
        ClientHud.sync();
    }

    /** Pulls pos to a nearby alignment (screen edges, centre, other boxes) and records the guide line it snapped to. */
    private int snapAxis(int pos, int size, int screen, boolean horizontal, List<Integer> lines) {
        List<int[]> cands = new ArrayList<>();   // {box position, guide line}
        cands.add(new int[]{EDGE, EDGE});
        cands.add(new int[]{screen - EDGE - size, screen - EDGE});
        cands.add(new int[]{(screen - size) / 2, screen / 2});
        for (ClientHud.Placed o : placed) {
            if (o.mod() == grabbed) continue;
            int op = horizontal ? o.x() : o.y(), os = horizontal ? o.w() : o.h();
            cands.add(new int[]{op, op});                                  // same start
            cands.add(new int[]{op + os - size, op + os});                 // same end
            cands.add(new int[]{op + os + GAP, op + os + GAP});            // right after it
            cands.add(new int[]{op - GAP - size, op - GAP});               // right before it
            cands.add(new int[]{op + (os - size) / 2, op + os / 2});       // same centre
        }
        int best = pos, bestDist = SNAP + 1, line = -1;
        for (int[] c : cands) {
            int dist = Math.abs(c[0] - pos);
            if (dist < bestDist) { bestDist = dist; best = c[0]; line = c[1]; }
        }
        if (line >= 0) lines.add(line);
        return best;
    }

    // ---------------------------------------------------------------- drawing

    private void drawBoxes(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my) {
        int guide = (0x90 << 24) | (Ui.ACCENT & 0xFFFFFF);
        for (int gx : guideX) Ui.fill(g, gx, 0, gx + 1, this.height, guide);
        for (int gy : guideY) Ui.fill(g, 0, gy, this.width, gy + 1, guide);

        for (ClientHud.Placed p : placed) {
            ClientHud.drawBox(g, this.minecraft, this.font, p);
            boolean overlap = false;
            for (ClientHud.Placed o : placed) {
                if (o != p && p.x() < o.x() + o.w() && o.x() < p.x() + p.w() && p.y() < o.y() + o.h() && o.y() < p.y() + p.h()) overlap = true;
            }
            boolean hover = grabbed == null && mx >= p.x() && mx < p.x() + p.w() && my >= p.y() && my < p.y() + p.h();
            int color = overlap ? RED : p.mod() == grabbed ? Ui.ACCENT : hover ? 0xFFFFFFFF : 0x55FFFFFF;
            outline(g, p.x() - 1, p.y() - 1, p.x() + p.w() + 1, p.y() + p.h() + 1, color);
            if (hover || p.mod() == grabbed) {
                String label = p.mod().scalePct() == 100 ? p.mod().name() : p.mod().name() + "  " + p.mod().scalePct() + "%";
                Ui.txt(g, this.font, label, p.x(), Math.max(0, p.y() - 11), overlap ? RED : Ui.TEXT, true);
            }
        }
    }

    private static void outline(net.minecraft.client.gui.GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        Ui.fill(g, x1, y1, x2, y1 + 1, color);
        Ui.fill(g, x1, y2 - 1, x2, y2, color);
        Ui.fill(g, x1, y1, x1 + 1, y2, color);
        Ui.fill(g, x2 - 1, y1, x2, y2, color);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
