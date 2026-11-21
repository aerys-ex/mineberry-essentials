package com.mineberry.essentials.modules;

import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.Terms;
import com.mineberry.essentials.MineBerryModule;
import com.mineberry.essentials.ScreenshotScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Press F2 (the normal Minecraft screenshot key) and the screenshot is also copied to the clipboard as an image, so you
 * can paste it straight into Discord with Ctrl+V. Minecraft saves the file in screenshots/ as always; a small file
 * watcher notices it and copies it, so no screenshot is taken twice. There is also an optional extra key (unbound by
 * default) that takes one into screenshots/mineberry/. The Screenshots screen lists them all and can copy any of them again.
 *
 * The clipboard copy uses the system's own tools (PowerShell on Windows, osascript on macOS, wl-copy / xclip on Linux),
 * because Minecraft runs without AWT. If that fails, the file path is copied as text instead.
 */
public class ScreenshotModule extends MineBerryModule {

    public static final String FOLDER = "mineberry";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
    private static final int MAX_LISTED = 200;

    private KeyMapping shotKey, menuKey;

    public ScreenshotModule() {
        super("screenshot", "Screenshot", "Press F2 to screenshot and copy it to the clipboard");
    }

    @Override
    public void init() {
        shotKey = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.screenshot",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,         // F2 does the job through the watcher below; this is an optional extra key
                MineBerryEssentials.CATEGORY));
        menuKey = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.screenshots_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                MineBerryEssentials.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (shotKey.consumeClick()) {
                if (enabled() && client.player != null && client.level != null) capture(client);
            }
            while (menuKey.consumeClick()) {
                if (enabled() && client.gui.screen() == null) client.gui.setScreen(new ScreenshotScreen(null));
            }
        });
        startWatcher();
    }

    // ---------------------------------------------------------------- F2 (the normal Minecraft screenshot)

    /**
     * Watches the game's screenshots folder. When Minecraft saves a new picture there (F2 does), it is copied to the
     * clipboard. Our own screenshots live in a sub-folder, which is not watched, so nothing is copied twice.
     */
    private void startWatcher() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots");
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                    dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE);
                    while (true) {
                        WatchKey key = ws.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() != StandardWatchEventKinds.ENTRY_CREATE) continue;
                            Path file = dir.resolve((Path) event.context());
                            if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) onGameScreenshot(file);
                        }
                        if (!key.reset()) break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                MineBerryEssentials.LOGGER.warn("Screenshot watcher stopped", e);
            }
        }, "mineberry-screenshot-watcher");
        t.setDaemon(true);
        t.start();
    }

    private void onGameScreenshot(Path file) throws InterruptedException {
        if (!enabled()) return;
        // the file shows up before the game has finished writing it: wait until its size stops changing
        long last = -1;
        for (int i = 0; i < 40; i++) {
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                return;                                    // gone again (deleted or renamed)
            }
            if (size > 0 && size == last) break;
            last = size;
            Thread.sleep(150);
        }
        Minecraft mc = Minecraft.getInstance();
        copyAsync(file, ok -> {
            // success is silent (the game already says "Saved screenshot as ..."); only tell the player when the copy failed
            if (!ok) say(mc, "Screenshot saved, but the image couldn't be copied. File path copied instead", ChatFormatting.YELLOW);
        });
    }

    // ---------------------------------------------------------------- taking one

    /** The folder our screenshots are saved in. */
    public static Path folder(Minecraft mc) {
        return Path.of(mc.gameDirectory.toString(), "screenshots", FOLDER);
    }

    private static Path nextFile(Path dir) {
        String stamp = LocalDateTime.now().format(STAMP);
        Path file = dir.resolve(stamp + ".png");
        for (int i = 2; Files.exists(file); i++) file = dir.resolve(stamp + "_" + i + ".png");
        return file;
    }

    /** Takes the screenshot of the game as it is now, saves it and copies it to the clipboard. */
    public static void capture(Minecraft mc) {
        Path dir = folder(mc);
        File file;
        try {
            Files.createDirectories(dir);
            file = nextFile(dir).toFile();
        } catch (IOException e) {
            say(mc, "Couldn't create the screenshots folder", ChatFormatting.RED);
            return;
        }
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> {
            Thread t = new Thread(() -> {
                try (img) {
                    img.writeToFile(file);
                } catch (Exception e) {
                    MineBerryEssentials.LOGGER.warn("Could not save screenshot", e);
                    say(mc, "Couldn't save the screenshot", ChatFormatting.RED);
                    return;
                }
                copyAsync(file.toPath(), ok -> {
                    // success is silent; only tell the player when the image could not be copied
                    if (!ok) say(mc, "Screenshot saved, but the image couldn't be copied. File path copied instead", ChatFormatting.YELLOW);
                });
            }, "mineberry-screenshot-writer");
            t.setDaemon(true);
            t.start();
        });
    }

    // ---------------------------------------------------------------- listing (for the Screenshots screen)

    /** Our screenshots plus the normal F2 ones, newest first. Sub-folders (evidence, reports) are left out. */
    public static List<Path> list(Minecraft mc) {
        List<Path> out = new ArrayList<>();
        Path root = Path.of(mc.gameDirectory.toString(), "screenshots");
        for (Path dir : new Path[]{folder(mc), root}) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png") && Files.isRegularFile(p))
                        .forEach(out::add);
            } catch (IOException ignored) {
            }
        }
        out.sort(Comparator.comparingLong(ScreenshotModule::modified).reversed());
        return out.size() > MAX_LISTED ? new ArrayList<>(out.subList(0, MAX_LISTED)) : out;
    }

    public static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------- clipboard

    /**
     * Copies the image to the system clipboard on a background thread. done gets true on success; on failure the file
     * path is copied as text and done gets false. done runs on that background thread.
     */
    public static void copyAsync(Path file, Consumer<Boolean> done) {
        Thread t = new Thread(() -> {
            boolean ok = copyImage(file);
            if (!ok) {
                Minecraft mc = Minecraft.getInstance();
                String path = file.toAbsolutePath().toString();
                mc.execute(() -> mc.keyboardHandler.setClipboard(path));
            }
            if (done != null) done.accept(ok);
        }, "mineberry-screenshot-clipboard");
        t.setDaemon(true);
        t.start();
    }

    private static boolean copyImage(Path file) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String path = file.toAbsolutePath().toString();
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                // The path goes in through an environment variable, never into the script text: PowerShell treats
                // typographic quotes (\u2018 \u2019 ...) like ' so escaping inside the script is not safe.
                String script = "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                        + "$i = [System.Drawing.Image]::FromFile($env:MINEBERRY_IMG); "
                        + "[System.Windows.Forms.Clipboard]::SetImage($i); $i.Dispose()";
                pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Sta", "-Command", script);
                pb.environment().put("MINEBERRY_IMG", path);
            } else if (os.contains("mac")) {
                String script = "set the clipboard to (read (POSIX file \"" + path.replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\") as \u00abclass PNGf\u00bb)";
                pb = new ProcessBuilder("osascript", "-e", script);
            } else if (System.getenv("WAYLAND_DISPLAY") != null) {
                pb = new ProcessBuilder("wl-copy", "--type", "image/png");
                pb.redirectInput(file.toFile());
            } else {
                pb = new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "image/png", "-i", path);
            }
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            MineBerryEssentials.LOGGER.warn("Could not copy the screenshot to the clipboard", e);
            return false;
        }
    }

    // ---------------------------------------------------------------- opening files

    /** Opens the image in the system's default viewer. */
    public static void open(Path file) {
        launch(file, false);
    }

    /** Opens the screenshots folder in the file manager. */
    public static void openFolder(Minecraft mc) {
        Path dir = folder(mc);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        launch(dir, true);
    }

    private static void launch(Path target, boolean folder) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String path = target.toAbsolutePath().toString();
        try {
            if (os.contains("win")) {
                new ProcessBuilder(folder ? new String[]{"explorer.exe", path}
                        : new String[]{"rundll32", "url.dll,FileProtocolHandler", path}).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", path).start();
            } else {
                new ProcessBuilder("xdg-open", path).start();
            }
        } catch (IOException e) {
            MineBerryEssentials.LOGGER.warn("Could not open {}", path, e);
        }
    }

    // ---------------------------------------------------------------- chat feedback

    private static void say(Minecraft mc, String text, ChatFormatting color) {
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(text).withStyle(color)));
        });
    }
}
