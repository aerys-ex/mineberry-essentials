package com.mineberry.essentials;

import com.mineberry.essentials.modules.ScreenshotModule;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screenshot gallery: a grid of thumbnails of your screenshots.
 *
 * - Right-click a thumbnail for a menu: Copy image, Open, Show folder, Delete (asks twice).
 * - Left-click selects. Double-click opens the image in your viewer.
 * - Mouse wheel or the Prev / Next buttons change page.
 * - Esc closes the menu first, then the gallery.
 *
 * Thumbnails are decoded and shrunk on one background thread, then uploaded to the GPU on the render thread, two per
 * frame at most, so opening the gallery never freezes the game. They are released when the screen closes.
 *
 * Mouse buttons are polled from GLFW once per frame (like HudEditScreen and RadialMenuScreen do) instead of
 * overriding the Screen click methods.
 */
public class ScreenshotScreen extends Screen {

    private static final int PANEL_W = 360, HEADER_H = 20, MARGIN = 14, BTN_H = 20;
    private static final int COLS = 3, GAP = 6, CAPTION_H = 12, MAX_ROWS = 3;
    private static final int THUMB_MAX_W = 256, THUMB_MAX_H = 144;      // size of the stored thumbnail texture
    private static final int MENU_W = 104, MENU_ITEM_H = 14, MENU_PAD = 2;
    private static final int RED = 0xFFE05555;
    private static final long DOUBLE_CLICK_MS = 350, CONFIRM_MS = 3000, STATUS_MS = 4000;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    private enum Action {
        COPY("Copy image"), OPEN("Open"), FOLDER("Show folder"), DELETE("Delete");
        final String label;
        Action(String label) { this.label = label; }
    }

    private record Thumb(Identifier id, int w, int h) {}
    private record Loaded(Path file, NativeImage image) {}      // image is null when the file could not be read

    /** One background thread for every gallery: decoding a 4K PNG is heavy, and we never need two at once. */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mineberry-thumbnails");
        t.setDaemon(true);
        return t;
    });
    private static int textureCounter = 0;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final Map<AbstractWidget, Boolean> baseActive = new IdentityHashMap<>();
    private List<Path> files = new ArrayList<>();
    private boolean loaded = false;
    private int page = 0;
    private boolean rebuildPending;            // set while rendering, applied in tick() (see deleteFile)

    // thumbnails
    private final Map<Path, Thumb> thumbs = new HashMap<>();
    private final Set<Path> requested = new HashSet<>();
    private final Set<Path> failed = new HashSet<>();
    private final ConcurrentLinkedQueue<Loaded> ready = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    // status line
    private volatile String status = "";
    private volatile long statusUntil;

    // input state
    private boolean prevLeft, prevRight;
    private int selected = -1;                 // index into files
    private long lastClickAt;
    private int lastClickIndex = -1;
    private int menuIndex = -1;                // index into files, -1 = menu closed
    private int menuX, menuY;
    private Path deleteArmed;
    private long deleteArmedUntil;

    // layout
    private int px, py, pw, ph, topY, gridY, pagerY, footerY;
    private int cellW, thumbH, cellH, rows, perPage;

    public ScreenshotScreen(Screen parent) {
        super(Component.literal("MineBerry Screenshots"));
        this.parent = parent;
    }

    // ---------------------------------------------------------------- layout and widgets

    @Override
    protected void init() {
        Ui.applyTheme();
        this.addRenderableOnly((g, mx, my, d) -> Ui.ghostAll(this));   // first thing drawn: hides the vanilla buttons before THEY are drawn (no flash on open)
        widgets.clear();
        baseActive.clear();
        menuIndex = -1;
        if (!loaded) { files = ScreenshotModule.list(Minecraft.getInstance()); loaded = true; }

        pw = Math.min(PANEL_W, this.width - 20);
        int inner = pw - 2 * MARGIN;
        cellW = (inner - GAP * (COLS - 1)) / COLS;
        thumbH = cellW * 9 / 16;
        cellH = thumbH + CAPTION_H + 2;

        topY = HEADER_H + 10;
        gridY = topY + BTN_H + 10;
        int overhead = gridY + 2 + BTN_H + 10 + 36;                       // everything except the grid rows
        rows = Math.max(1, Math.min(MAX_ROWS, (this.height - 12 - overhead) / (cellH + GAP)));
        perPage = COLS * rows;
        pagerY = gridY + rows * (cellH + GAP) + 2;
        footerY = pagerY + BTN_H + 10;
        ph = footerY + 36;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        prevLeft = mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);                // the click that opened this screen must not select
        prevRight = mouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> {
            uploadReady();
            handleMouse(mouseX, mouseY);
            drawDecor(g, mouseX, mouseY);
        });

        int left = px + MARGIN, right = px + pw - MARGIN;

        add(Button.builder(Component.literal("Open folder"), button -> ScreenshotModule.openFolder(Minecraft.getInstance()))
                .bounds(left, py + topY, 84, BTN_H).build(), true);
        add(Button.builder(Component.literal("Refresh"), button -> refresh())
                .bounds(left + 88, py + topY, 60, BTN_H).build(), true);

        int pages = pageCount();
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        add(Button.builder(Component.literal("< Prev"), button -> { page--; this.rebuildWidgets(); })
                .bounds(left, py + pagerY, 56, BTN_H).build(), page > 0);
        add(Button.builder(Component.literal("Next >"), button -> { page++; this.rebuildWidgets(); })
                .bounds(right - 56, py + pagerY, 56, BTN_H).build(), page < pages - 1);
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 8, 60, BTN_H).build(), true);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));
        // drawn last so the menu sits on top of everything
        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawMenu(g, mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget, boolean active) {
        widget.active = active;
        baseActive.put(widget, active);
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private void refresh() {
        loaded = false;
        releaseThumbs();
        selected = -1;
        setStatus("Refreshed");
        this.rebuildWidgets();
    }

    private int pageCount() { return Math.max(1, (files.size() + perPage - 1) / perPage); }

    private void setStatus(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    // ---------------------------------------------------------------- cells

    private int cellX(int slot) { return px + MARGIN + (slot % COLS) * (cellW + GAP); }

    private int cellY(int slot) { return py + gridY + (slot / COLS) * (cellH + GAP); }

    /** Index into files of the thumbnail under the cursor, or -1. */
    private int fileAt(int mx, int my) {
        for (int slot = 0; slot < perPage; slot++) {
            int index = page * perPage + slot;
            if (index >= files.size()) break;
            int x = cellX(slot), y = cellY(slot);
            if (mx >= x && mx < x + cellW && my >= y && my < y + thumbH + CAPTION_H) return index;
        }
        return -1;
    }

    // ---------------------------------------------------------------- mouse

    /** Asks GLFW whether a mouse button is physically down (Minecraft does not track held buttons while a screen is open). */
    private static boolean mouseDown(int button) {
        long window = GLFW.glfwGetCurrentContext();
        return window != 0L && GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
    }

    private void handleMouse(int mx, int my) {
        boolean left = mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean right = mouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean leftPress = left && !prevLeft, rightPress = right && !prevRight;
        prevLeft = left;
        prevRight = right;

        // buttons underneath the menu must not react while it is open
        boolean locked = menuIndex >= 0;
        for (AbstractWidget w : widgets) w.active = !locked && baseActive.getOrDefault(w, true);

        if (deleteArmed != null && System.currentTimeMillis() > deleteArmedUntil) deleteArmed = null;

        if (menuIndex >= 0) {
            if (leftPress) {
                Action hit = menuItemAt(mx, my);
                if (hit != null) runAction(hit, menuIndex);
                else menuIndex = -1;
            } else if (rightPress) {
                menuIndex = -1;
            }
            return;
        }

        if (rightPress) {
            int hit = fileAt(mx, my);
            if (hit >= 0) {
                selected = hit;
                menuIndex = hit;
                int menuH = Action.values().length * MENU_ITEM_H + 2 * MENU_PAD;
                menuX = Math.max(2, Math.min(mx, this.width - MENU_W - 2));
                menuY = Math.max(2, Math.min(my, this.height - menuH - 2));
            }
        } else if (leftPress) {
            int hit = fileAt(mx, my);
            if (hit >= 0) {
                long now = System.currentTimeMillis();
                if (hit == lastClickIndex && now - lastClickAt <= DOUBLE_CLICK_MS) {
                    ScreenshotModule.open(files.get(hit));
                    lastClickIndex = -1;
                } else {
                    lastClickIndex = hit;
                    lastClickAt = now;
                }
                selected = hit;
            }
        }
    }

    private Action menuItemAt(int mx, int my) {
        int h = Action.values().length * MENU_ITEM_H + 2 * MENU_PAD;
        if (mx < menuX || mx >= menuX + MENU_W || my < menuY + MENU_PAD || my >= menuY + h - MENU_PAD) return null;
        int i = (my - menuY - MENU_PAD) / MENU_ITEM_H;
        Action[] all = Action.values();
        return i >= 0 && i < all.length ? all[i] : null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (menuIndex >= 0) return true;
        int pages = pageCount();
        int next = page + (scrollY < 0 ? 1 : -1);
        if (scrollY != 0 && next >= 0 && next < pages) {
            page = next;
            this.rebuildWidgets();
        }
        return true;
    }

    // ---------------------------------------------------------------- actions

    private void runAction(Action action, int index) {
        if (index < 0 || index >= files.size()) { menuIndex = -1; return; }
        Path file = files.get(index);
        switch (action) {
            case COPY -> {
                setStatus("Copying...");
                ScreenshotModule.copyAsync(file, ok -> setStatus(ok ? "Copied. Press Ctrl+V to paste it"
                        : "Couldn't copy the image: the clipboard is busy, try again"));
                menuIndex = -1;
            }
            case OPEN -> {
                ScreenshotModule.open(file);
                menuIndex = -1;
            }
            case FOLDER -> {
                ScreenshotModule.openFolder(Minecraft.getInstance());
                menuIndex = -1;
            }
            case DELETE -> {
                long now = System.currentTimeMillis();
                if (file.equals(deleteArmed) && now <= deleteArmedUntil) {
                    deleteFile(index);
                    menuIndex = -1;
                    deleteArmed = null;
                } else {
                    deleteArmed = file;                       // first click only arms it, the menu stays open
                    deleteArmedUntil = now + CONFIRM_MS;
                }
            }
        }
    }

    private void deleteFile(int index) {
        Path file = files.get(index);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            setStatus("Couldn't delete the file");
            return;
        }
        files.remove(index);
        releaseThumb(file);
        requested.remove(file);
        failed.remove(file);
        selected = -1;
        setStatus("Deleted");
        if (page >= pageCount()) page = pageCount() - 1;
        // Do NOT call rebuildWidgets() here: this runs from handleMouse(), which is called from inside a renderable, i.e.
        // while Screen.extractRenderState is iterating its renderables list. rebuildWidgets() clears and refills that
        // list, which throws ConcurrentModificationException. tick() applies it between frames instead.
        rebuildPending = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (rebuildPending) {
            rebuildPending = false;
            this.rebuildWidgets();
        }
    }

    // ---------------------------------------------------------------- thumbnails

    private void request(Path file) {
        if (!requested.add(file)) return;
        LOADER.execute(() -> {
            NativeImage small = null;
            if (!closed) {
                try (InputStream in = Files.newInputStream(file); NativeImage full = NativeImage.read(in)) {
                    small = shrink(full);
                } catch (Exception e) {
                    small = null;                              // unreadable file: the cell shows "No preview"
                }
            }
            ready.add(new Loaded(file, small));
            if (closed) drainReady();                          // the screen closed while we worked: free the image
        });
    }

    /** Box-averages the image down to fit THUMB_MAX_W x THUMB_MAX_H, keeping the aspect ratio. Works on every colour byte alike, so the pixel order does not matter. */
    private static NativeImage shrink(NativeImage full) {
        int sw = full.getWidth(), sh = full.getHeight();
        double scale = Math.min(1.0, Math.min((double) THUMB_MAX_W / sw, (double) THUMB_MAX_H / sh));
        int tw = Math.max(1, (int) Math.round(sw * scale)), th = Math.max(1, (int) Math.round(sh * scale));
        NativeImage out = new NativeImage(tw, th, false);
        for (int y = 0; y < th; y++) {
            int y0 = (int) ((long) y * sh / th), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / th));
            for (int x = 0; x < tw; x++) {
                int x0 = (int) ((long) x * sw / tw), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / tw));
                long a = 0, b = 0, c = 0, d = 0;
                int n = 0;
                for (int yy = y0; yy < y1 && yy < sh; yy++) {
                    for (int xx = x0; xx < x1 && xx < sw; xx++) {
                        int p = full.getPixel(xx, yy);
                        a += (p >>> 24) & 255;
                        b += (p >>> 16) & 255;
                        c += (p >>> 8) & 255;
                        d += p & 255;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                out.setPixel(x, y, (int) (a / n) << 24 | (int) (b / n) << 16 | (int) (c / n) << 8 | (int) (d / n));
            }
        }
        return out;
    }

    /** Render thread: turns finished thumbnails into textures, two per frame at most. */
    private void uploadReady() {
        Minecraft mc = Minecraft.getInstance();
        for (int n = 0; n < 2; ) {
            Loaded l = ready.poll();
            if (l == null) break;
            if (l.image() == null) { failed.add(l.file()); continue; }
            if (closed || !files.contains(l.file())) { l.image().close(); continue; }
            Identifier id = Identifier.fromNamespaceAndPath("mineberry_essentials", "screenshot_thumb/" + (textureCounter++));
            int w = l.image().getWidth(), h = l.image().getHeight();
            mc.getTextureManager().register(id, new DynamicTexture(() -> "MineBerry screenshot thumbnail", l.image()));
            thumbs.put(l.file(), new Thumb(id, w, h));
            n++;
        }
    }

    private void drainReady() {
        Loaded l;
        while ((l = ready.poll()) != null) if (l.image() != null) l.image().close();
    }

    private void releaseThumb(Path file) {
        Thumb t = thumbs.remove(file);
        if (t != null) Minecraft.getInstance().getTextureManager().release(t.id());
    }

    private void releaseThumbs() {
        for (Path p : new ArrayList<>(thumbs.keySet())) releaseThumb(p);
        requested.clear();
        failed.clear();
    }

    @Override
    public void removed() {
        closed = true;
        drainReady();
        releaseThumbs();
        super.removed();
    }

    // ---------------------------------------------------------------- drawing

    private static String size(Path file) {
        try {
            long b = Files.size(file);
            return b >= 1_048_576 ? String.format("%.1f MB", b / 1_048_576.0) : (b / 1024) + " KB";
        } catch (IOException e) {
            return "";
        }
    }

    private void drawDecor(GuiGraphicsExtractor g, int mx, int my) {
        int left = px + MARGIN, right = px + pw - MARGIN;

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Screenshots", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        int count = files.size();
        Ui.headerRight(g, this.font, right, headerTextY, count == 0 ? "No screenshots" : count + (count == 1 ? " screenshot" : " screenshots"));

        // status line: a fresh message first, otherwise the name of the hovered picture
        int hover = menuIndex >= 0 ? -1 : fileAt(mx, my);
        String line;
        if (System.currentTimeMillis() < statusUntil && !status.isEmpty()) line = status;
        else if (hover >= 0) line = files.get(hover).getFileName().toString().replaceFirst("(?i)\\.png$", "") + "  " + size(files.get(hover));
        else line = "Right-click a picture for options";
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(line, pw - 2 * MARGIN - 160),
                left + 156, py + topY + 6, Ui.MUTED, false);

        if (files.isEmpty()) {
            Ui.txt(g, this.font, "Nothing here yet. Press F2 in game to take one.", left, py + gridY + 10, Ui.MUTED, false);
        }

        int solid = Ui.BG | 0xFF000000;
        for (int slot = 0; slot < perPage; slot++) {
            int index = page * perPage + slot;
            if (index >= files.size()) break;
            Path file = files.get(index);
            int x = cellX(slot), y = cellY(slot);
            boolean sel = index == selected, hov = index == hover || index == menuIndex;

            Ui.rect(g, x, y, cellW, thumbH, sel ? Ui.ACCENT : hov ? Ui.BTN_BORDER_HOVER : Ui.BTN_BORDER, Ui.INSET);
            drawThumb(g, file, x + 1, y + 1, cellW - 2, thumbH - 2);
            if (hov && !sel) Ui.fill(g, x + 1, y + 1, x + cellW - 1, y + thumbH - 1, 0x18FFFFFF);

            String when = WHEN.format(Instant.ofEpochMilli(ScreenshotModule.modified(file)));
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(when, cellW), x + 1, y + thumbH + 3, sel ? Ui.TEXT : Ui.MUTED, false);
        }

        String pageLabel = (page + 1) + " / " + pageCount();
        Ui.txt(g, this.font, pageLabel, px + pw / 2 - this.font.width(pageLabel) / 2, py + pagerY + 6, Ui.MUTED, false);
        Ui.footerLine(g, left, right, py + footerY);
        Ui.txt(g, this.font, "Double-click opens", left, py + footerY + 14, Ui.MUTED, false);
    }

    /** The thumbnail centred inside the box (aspect ratio kept), or a short note while it loads. */
    private void drawThumb(GuiGraphicsExtractor g, Path file, int x, int y, int w, int h) {
        Thumb t = thumbs.get(file);
        if (t == null) {
            request(file);
            String note = failed.contains(file) ? "No preview" : "...";
            Ui.txt(g, this.font, note, x + (w - this.font.width(note)) / 2, y + (h - 8) / 2, Ui.MUTED, false);
            return;
        }
        double s = Math.min((double) w / t.w(), (double) h / t.h());
        int dw = Math.max(1, (int) Math.round(t.w() * s)), dh = Math.max(1, (int) Math.round(t.h() * s));
        int dx = x + (w - dw) / 2, dy = y + (h - dh) / 2;
        // NOTE: the one line that depends on the 26.2 GuiGraphicsExtractor API. Adjust the name or arguments if the compiler complains.
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), dx, dy, 0f, 0f, dw, dh, t.w(), t.h(), t.w(), t.h());
    }

    private void drawMenu(GuiGraphicsExtractor g, int mx, int my) {
        if (menuIndex < 0) return;
        Action[] all = Action.values();
        int h = all.length * MENU_ITEM_H + 2 * MENU_PAD;
        Ui.rect(g, menuX, menuY, MENU_W, h, Ui.BTN_BORDER, Ui.BG | 0xFF000000);
        Action over = menuItemAt(mx, my);
        for (int i = 0; i < all.length; i++) {
            int y = menuY + MENU_PAD + i * MENU_ITEM_H;
            if (all[i] == over) Ui.fill(g, menuX + 2, y, menuX + MENU_W - 2, y + MENU_ITEM_H, Ui.BTN_HOVER);
            String label = all[i].label;
            int color = Ui.TEXT;
            if (all[i] == Action.DELETE) {
                color = RED;
                if (menuIndex < files.size() && files.get(menuIndex).equals(deleteArmed)) label = "Click to confirm";
            }
            Ui.txt(g, this.font, label, menuX + 8, y + (MENU_ITEM_H - 8) / 2 + 1, color, false);
        }
    }

    @Override
    public void onClose() {
        if (menuIndex >= 0) { menuIndex = -1; return; }       // Esc closes the menu first
        this.minecraft.gui.setScreen(parent);
    }
}
