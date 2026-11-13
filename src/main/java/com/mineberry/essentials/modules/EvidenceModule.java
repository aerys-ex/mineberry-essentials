package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.lwjgl.glfw.GLFW;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Evidence recorder for /tpahere + region PvP abuse.
 *  1. "X has requested that you teleport to them." -> remembered
 *  2. "Teleport request accepted."                 -> screenshots/tpaheres/
 *  3. "Sorry, but you can't PvP here"              -> screenshots/testimony/
 *  4. "You were killed by X"                       -> screenshots/testimony/ (+ log.txt)
 * Steps 3 and 4 only count within 2 minutes after an accepted tpahere.
 *
 * Abuse alert: after an accepted tpahere, when the server announces that PvP mode was entered
 * (someone started hitting), a HUD box appears top-left: "possible tp kill / rg abuse detected".
 * "-" confirms (screenshot + report zip right away), "=" skips. Both keys are rebindable.
 *
 * Every sequence is also tracked as one "incident". When the kill is captured the incident is
 * bundled into screenshots/reports/<date>_<player>.zip (screenshots + evidence-log.txt +
 * HOW_TO_REPORT.txt). The panel's Export button re-builds the zip for the latest incident.
 */
public class EvidenceModule extends MineBerryModule {

    /** Sub-folder of screenshots/ that holds the testimony screenshots (cannot-PvP, kill, confirmed) and log.txt. */
    private static final String TESTIMONY_DIR = "testimony";

    private static final long REQUEST_VALID_MS = 60_000;
    private static final long WINDOW_MS = 120_000;
    private static final long PVP_COOLDOWN_MS = 3_000;
    private static final int SCREENSHOT_DELAY_TICKS = 10;   // minimum wait after the message (20 ticks = 1 second)
    private static final int POST_LOAD_SETTLE_TICKS = 20;   // after a loading screen closes, let the world render for 1s
    private static final int MAX_WAIT_TICKS = 600;          // give up waiting for the world after 30s and shoot anyway
    private static final long TPAHERE_COOLDOWN_MS = 15_000; // one screenshot per tpahere, even if the server repeats "accepted"
    private static final long DUPLICATE_MSG_MS = 300;       // same line arriving twice (system + chat channel)

    private static final Pattern REQUEST = Pattern.compile("~?(\\w{1,16}) has requested that you teleport to them", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCEPTED = Pattern.compile("Teleport request accepted", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_PVP = Pattern.compile("Sorry, but you can'?t PvP here", Pattern.CASE_INSENSITIVE);
    private static final Pattern KILLED = Pattern.compile("You were killed by (\\w{1,16})", Pattern.CASE_INSENSITIVE);
    private static final long ALERT_MS = 30_000;            // the HUD prompt disappears by itself after 30s
    private static final Pattern PVP_MODE_END = Pattern.compile("\\b(left|ended|expired|disabled|no longer)\\b", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private String requester = null;
    private long requestTime = 0;
    private long acceptedTime = 0;
    private String acceptedFrom = null;
    private long lastPvpShot = 0;
    private long lastTpahereShot = 0;
    private String lastText = null;
    private long lastTextTime = 0;

    // abuse alert (HUD prompt)
    private KeyMapping confirmKey, skipKey;
    private String alertSuspect = null;       // non-null while the HUD prompt is showing
    private Incident alertIncident = null;
    private long alertTime = 0;
    private boolean alertHandled = false;     // the prompt shows once per tpahere (a skip is final)
    private Pattern pvpModePattern = null;
    private String pvpModeSource = null;

    /** A screenshot that is waiting for the world to be visible (not loading) before it is taken. */
    private static class Pending {
        final String folder, label, logLine;
        final Incident incident;
        final boolean finalShot;   // the kill screenshot: bundle the incident once it is on disk
        int waited = 0;
        int settle = 0;
        boolean sawLoading = false;
        Pending(String folder, String label, String logLine, Incident incident, boolean finalShot) {
            this.folder = folder; this.label = label; this.logLine = logLine;
            this.incident = incident; this.finalShot = finalShot;
        }
    }

    /** One tpahere -> (can't PvP) -> kill sequence and everything captured for it. */
    private static class Incident {
        final String suspect;               // who sent the tpahere
        final String stamp = LocalDateTime.now().format(STAMP);
        volatile String killer = null;      // set when the kill message arrives
        final List<Path> files = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        Incident(String suspect) { this.suspect = suspect; }
    }
    private Incident latest = null;
    private final List<Pending> queue = new ArrayList<>();

    public EvidenceModule() {
        super("evidence", "Evidence Recorder", "Screenshots tpahere > kill, then zips a report");
    }

    @Override
    public void init() {
        confirmKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.confirm_abuse",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_MINUS,
                MineBerryEssentials.CATEGORY));
        skipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.skip_abuse",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_EQUAL,
                MineBerryEssentials.CATEGORY));

        HudElementRegistry.addLast(MineBerryEssentials.id("abuse_alert"), (g, delta) -> drawAlert(g));

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) handle(msg); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> handle(msg));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (alertSuspect != null && System.currentTimeMillis() - alertTime > ALERT_MS) alertSuspect = null;
            while (confirmKey.consumeClick()) {
                if (enabled() && alertSuspect != null) confirmAlert(client);
            }
            while (skipKey.consumeClick()) {
                if (enabled() && alertSuspect != null) skipAlert(client);
            }

            Iterator<Pending> it = queue.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                p.waited++;

                boolean loading = isLoading(client);
                if (loading) { p.sawLoading = true; p.settle = 0; } else { p.settle++; }

                boolean ready = !loading
                        && p.waited >= SCREENSHOT_DELAY_TICKS
                        && (!p.sawLoading || p.settle >= POST_LOAD_SETTLE_TICKS);
                boolean timedOut = p.waited >= MAX_WAIT_TICKS;

                if (ready || timedOut) { it.remove(); capture(p); }
            }
        });
    }

    /** True while the world is not actually visible: no level/player yet, or a loading/connecting screen is open. */
    private static boolean isLoading(Minecraft mc) {
        if (mc.level == null || mc.player == null) return true;
        Object screen = mc.gui.screen();
        if (screen == null) return false;
        String n = screen.getClass().getSimpleName();
        return n.contains("Loading") || n.contains("Receiving") || n.contains("Progress")
                || n.contains("Connect") || n.contains("Download") || n.contains("Reconfig");
    }

    private boolean hasPending(String folder) {
        for (Pending p : queue) if (p.folder.equals(folder)) return true;
        return false;
    }

    private void handle(Component text) {
        if (!enabled()) return;
        String s = text.getString();
        long now = System.currentTimeMillis();

        // the same line can reach us twice (system + chat channel) -> handle it once
        if (s.equals(lastText) && now - lastTextTime < DUPLICATE_MSG_MS) return;
        lastText = s;
        lastTextTime = now;

        Matcher m = REQUEST.matcher(s);
        if (m.find()) {
            requester = m.group(1);
            requestTime = now;
            return;
        }

        if (ACCEPTED.matcher(s).find()) {
            if (requester != null && now - requestTime <= REQUEST_VALID_MS) {
                // already captured (or still waiting to capture) this tpahere -> never a second screenshot
                if (hasPending("tpaheres") || now - lastTpahereShot < TPAHERE_COOLDOWN_MS) {
                    requester = null;
                    return;
                }
                lastTpahereShot = now;
                acceptedFrom = requester;
                acceptedTime = now;
                latest = new Incident(acceptedFrom);
                alertSuspect = null;
                alertIncident = null;
                alertHandled = false;
                shoot("tpaheres", "tpahere_" + acceptedFrom, "TPAHERE ACCEPTED from " + acceptedFrom + " | " + s, false);
                requester = null;
            }
            return;
        }

        if (acceptedFrom == null || now - acceptedTime > WINDOW_MS) return;

        // someone started hitting -> server says PvP mode was entered -> ask the player on the HUD
        if (!alertHandled && pvpMode().matcher(s).find() && !PVP_MODE_END.matcher(s).find()) {
            alertHandled = true;
            alertSuspect = acceptedFrom;
            alertIncident = latest;
            alertTime = now;
            MineBerryEssentials.closePanelForDanger(Minecraft.getInstance());
            return;
        }

        if (NO_PVP.matcher(s).find()) {
            if (now - lastPvpShot < PVP_COOLDOWN_MS) return;
            lastPvpShot = now;
            shoot(TESTIMONY_DIR, "cannot_pvp_" + acceptedFrom, "CANNOT PVP message after tpahere from " + acceptedFrom, false);
            return;
        }

        Matcher k = KILLED.matcher(s);
        if (k.find()) {
            if (latest != null) latest.killer = k.group(1);
            shoot(TESTIMONY_DIR, "KILL_by_" + k.group(1), "KILLED by " + k.group(1) + " (tpahere from " + acceptedFrom + ") | " + s, true);
            acceptedFrom = null; // case closed
            alertSuspect = null;
        }
    }

    // ---------------------------------------------------------------- abuse alert

    /** The "PvP mode entered" pattern from the config; falls back to the default if the regex is invalid. */
    private Pattern pvpMode() {
        String src = Config.pvpModePattern();
        if (pvpModePattern == null || !src.equals(pvpModeSource)) {
            try {
                pvpModePattern = Pattern.compile(src, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                pvpModePattern = Pattern.compile(Config.DEFAULT_PVP_MODE, Pattern.CASE_INSENSITIVE);
            }
            pvpModeSource = src;
        }
        return pvpModePattern;
    }

    private void confirmAlert(Minecraft mc) {
        String who = alertSuspect;
        Incident inc = alertIncident;
        alertSuspect = null;
        alertIncident = null;
        // screenshot now + bundle the report zip (finalShot = true)
        queue.add(new Pending(TESTIMONY_DIR, "CONFIRMED_" + who,
                "CONFIRMED by player: possible tp kill / rg abuse (tpahere from " + who + ")", inc, true));
        say(mc, "Abuse confirmed - capturing evidence...", ChatFormatting.GREEN);
    }

    private void skipAlert(Minecraft mc) {
        alertSuspect = null;
        alertIncident = null;
        say(mc, "Alert skipped.", ChatFormatting.GRAY);
    }

    private static void say(Minecraft mc, String text, ChatFormatting color) {
        if (mc.player == null) return;
        mc.player.sendSystemMessage(
                Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal(text).withStyle(color)));
    }

    private static String keyName(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString();
    }

    private void drawAlert(GuiGraphicsExtractor g) {
        String suspect = alertSuspect;
        // Danger prompts always remain visible, even when the optional status HUD is hidden with Y.
        if (suspect == null || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;

        long leftMs = Math.max(0, ALERT_MS - (System.currentTimeMillis() - alertTime));
        String title = "POSSIBLE TP KILL / RG ABUSE DETECTED";
        String line2 = "tpahere from " + suspect + " - PvP mode entered";
        String line3 = "[" + keyName(confirmKey) + "] Yes, confirm     [" + keyName(skipKey) + "] Skip     ("
                + ((leftMs + 999) / 1000) + "s)";

        int w = Math.max(font.width(title), Math.max(font.width(line2), font.width(line3))) + 24;
        int x = 6, y = 6, h = 46;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x66000000);
        g.fill(x, y, x + w, y + h, 0xE0200A12);
        g.fill(x, y, x + 4, y + h, 0xFFE5243B);
        g.text(font, title, x + 12, y + 7, 0xFFFF5566, true);
        g.text(font, line2, x + 12, y + 20, 0xFFFFFFFF, false);
        g.text(font, line3, x + 12, y + 32, 0xFFDDDDDD, false);
    }

    private void shoot(String folder, String label, String logLine, boolean finalShot) {
        queue.add(new Pending(folder, label, logLine, latest, finalShot));
    }

    private void capture(Pending p) {
        Minecraft mc = Minecraft.getInstance();
        Path root = Path.of(mc.gameDirectory.toString(), "screenshots");
        Path dir = root.resolve(p.folder);
        String stamp = LocalDateTime.now().format(STAMP);
        File file = dir.resolve(stamp + "_" + p.label + ".png").toFile();
        try {
            Files.createDirectories(dir);
            Files.createDirectories(root.resolve(TESTIMONY_DIR));
            if (p.incident != null) {
                synchronized (p.incident) {
                    p.incident.files.add(file.toPath());
                    p.incident.lines.add("[" + stamp + "] " + p.logLine);
                }
            }
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> {
                Thread t = new Thread(() -> {
                    try (img) { img.writeToFile(file); } catch (Exception e) { e.printStackTrace(); }
                    if (p.finalShot && p.incident != null) autoExport(p.incident);
                }, "mineberry-evidence-writer");
                t.setDaemon(true);
                t.start();
            });
            Files.writeString(root.resolve(TESTIMONY_DIR).resolve("log.txt"),
                    "[" + stamp + "] " + p.logLine + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------------- report bundle

    /** Builds the zip for the most recent incident. Returns the zip, or null if there is nothing to export. */
    public Path exportLatest() {
        Incident inc = latest;
        return inc == null ? null : buildReport(inc);
    }

    /** Called from the screenshot writer thread once the kill screenshot is on disk. */
    private void autoExport(Incident inc) {
        Path zip = buildReport(inc);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            if (zip != null) {
                mc.player.sendSystemMessage(
                        Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                                .append(Component.literal("Report saved: ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal("screenshots/reports/" + zip.getFileName()).withStyle(ChatFormatting.GREEN)));
            }
        });
    }

    private Path buildReport(Incident inc) {
        List<Path> shots;
        List<String> lines;
        synchronized (inc) {
            shots = new ArrayList<>(inc.files);
            lines = new ArrayList<>(inc.lines);
        }
        if (shots.isEmpty()) return null;

        Path dir = Path.of(Minecraft.getInstance().gameDirectory.toString(), "screenshots", "reports");
        String who = inc.killer != null ? inc.killer : inc.suspect;
        Path zip = dir.resolve(inc.stamp + "_" + who + ".zip");
        try {
            Files.createDirectories(dir);
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                addText(out, "HOW_TO_REPORT.txt", guide(inc, shots));
                addText(out, "evidence-log.txt", String.join(NL, lines) + NL);
                for (Path f : shots) {
                    if (!Files.isRegularFile(f)) continue;
                    out.putNextEntry(new ZipEntry(f.getFileName().toString()));
                    Files.copy(f, out);
                    out.closeEntry();
                }
            }
            return zip;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static final String NL = "\r\n"; // CRLF so it reads correctly in Windows Notepad

    private static void addText(ZipOutputStream out, String name, String text) throws java.io.IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static String guide(Incident inc, List<Path> shots) {
        StringBuilder b = new StringBuilder();
        b.append("MINEBERRY - HOW TO REPORT THIS INCIDENT").append(NL);
        b.append("======================================").append(NL).append(NL);
        b.append("Incident:        ").append(inc.stamp).append(NL);
        b.append("tpahere from:    ").append(inc.suspect).append(NL);
        b.append("Killed by:       ").append(inc.killer != null ? inc.killer : "(no kill recorded)").append(NL);
        b.append("Screenshots:     ").append(shots.size()).append(NL).append(NL);
        b.append("WHAT TO DO").append(NL);
        b.append("----------").append(NL);
        b.append("1. Go to  www.mineberry.org/discord  and join the Discord.").append(NL);
        b.append("2. Open the #support channel and make a ticket.").append(NL);
        b.append("3. Extract this zip (if you are reading this file, you already did).").append(NL);
        b.append("4. In your ticket, upload the evidence files all at once:").append(NL);
        b.append("   select every .png screenshot plus evidence-log.txt, then drag them").append(NL);
        b.append("   into the ticket in a single message.").append(NL);
        b.append("   (Discord allows 10 files per message. If you have more, send the rest").append(NL);
        b.append("   in a second message right after.)").append(NL);
        b.append("5. Write the name of the player and roughly when it happened.").append(NL).append(NL);
        b.append("IMPORTANT: do NOT upload the .zip itself. Staff do not open zip files").append(NL);
        b.append("from players because they can be unsafe. Only upload the loose").append(NL);
        b.append("screenshots and evidence-log.txt. You do not need to upload this file.").append(NL);
        return b.toString();
    }
}
