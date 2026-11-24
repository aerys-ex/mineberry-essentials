package com.mineberry.essentials;

import com.mineberry.essentials.modules.AerionIntents;
import com.mineberry.essentials.modules.AerionQuery;
import com.mineberry.essentials.modules.CoordConvert;
import com.mineberry.essentials.modules.AerionIntents.Intent;
import com.mineberry.essentials.modules.AerionIntents.Item;
import com.mineberry.essentials.modules.WaypointsModule;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lets Aerion DO things, not only answer: switch modules and HUD elements on or off, hide / remove waypoints, undo.
 * AerionIntents reads the message; this class carries it out on the real mod. AerionTalk asks it first, so a command
 * never reaches the guide, and a question ("how do I turn off the compass?") never reaches here.
 *
 * Safety: deleting every waypoint asks "yes or no" first, an unclear name asks "which one?", and the last change can
 * always be taken back with "undo". Nothing here is ever sent to the server.
 */
public final class AerionActions {

    private static final long ASK_MS = 30_000;
    private static final int MAX_OPTIONS = 6;
    private static final java.util.Set<String> SAFETY = java.util.Set.of("safe_logout", "pvp_command_lock", "send_guard", "tournament_mode");

    private record Pending(Supplier<String> run, long until) {}
    private record Choice(List<Item> options, Function<Item, String> then, long until) {}

    private static Pending pending;      // "delete all waypoints?" waiting for yes / no
    private static Choice choice;        // "which one?" waiting for a number or a name
    private static Supplier<String> undo;   // takes back the last change
    private static String lastId;        // what "turn it back on" means
    private static CoordConvert.Result lastConversion;   // what "add that to waypoints" means
    private static Integer lastConversionY;

    private AerionActions() {}

    /** Aerion's answer when the message was a command (or the answer to his question), empty when it is for the guide. */
    public static Optional<String> tryHandle(String text) {
        if (!Terms.accepted() || text == null) return Optional.empty();
        long now = Clock.ms();

        Pending p = pending;
        pending = null;
        if (p != null && now < p.until()) {
            if (AerionIntents.isYes(text)) return Optional.of(p.run().get());
            if (AerionIntents.isNo(text)) return Optional.of("Okay, I left everything as it is.");
        }

        Choice c = choice;
        choice = null;
        if (c != null && now < c.until() && AerionIntents.parse(text).isEmpty()) {
            Item picked = pick(text, c.options());
            if (picked != null) return Optional.of(c.then().apply(picked));
            choice = c;   // not an answer to this; keep asking until it expires (a real question still goes to the guide)
            return Optional.empty();
        }

        try {
            Optional<CoordConvert.Request> coords = CoordConvert.parse(text);   // before the rest: "show nether coords for ..." is not "show"
            if (coords.isPresent()) return Optional.of(convertCoords(coords.get()));
            Optional<AerionQuery.Query> query = AerionQuery.parse(text);   // "where am I", "how far is home"
            if (query.isPresent()) {
                Optional<String> answer = answerQuery(query.get());
                if (answer.isPresent()) return answer;   // otherwise it is a question for the guide ("where is the shop")
            }
            Optional<String> many = toggleMany(text);   // "turn off compass, fps and ping"
            if (many.isPresent()) return many;
            Optional<Intent> parsed = AerionIntents.parse(text);
            if (parsed.isPresent()) return Optional.of(run(parsed.get()));
            if (lastConversion != null && CoordConvert.wantsAddLast(text)) return Optional.of(addConversion(lastConversion, lastConversionY));
            return Optional.empty();
        } catch (RuntimeException e) {
            MineBerryEssentials.LOGGER.warn("Aerion could not do that", e);
            return Optional.of("Something went wrong when I tried that, sorry. Nothing was changed that I know of.");
        }
    }

    private static String run(Intent in) {
        return switch (in.kind()) {
            case TOGGLE -> toggle(in);
            case WAYPOINT_REMOVE -> removeWaypoint(in.target());
            case WAYPOINT_REMOVE_ALL -> removeAllWaypoints();
            case WAYPOINT_VISIBILITY -> waypointVisibility(in);
            case LIST_WAYPOINTS -> listWaypoints();
            case LIST_MODULES -> listModules();
            case SET_DEFAULT -> setDefault(in.target());
            case UNDO -> undoLast();
            case TEST_ALERT -> testAlert();
        };
    }

    // ---------------------------------------------------------------- modules and HUD elements

    private static List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (MineBerryModule m : MineBerryEssentials.modules()) out.add(new Item(m.id(), m.name(), AerionIntents.aliasesFor(m.id())));
        for (HudMod h : ClientHud.MODS) out.add(new Item("hud_" + h.id(), h.name() + " (HUD)", AerionIntents.aliasesFor("hud_" + h.id())));
        return out;
    }

    private static HudMod hudMod(String id) {
        if (!id.startsWith("hud_")) return null;
        for (HudMod h : ClientHud.MODS) if (("hud_" + h.id()).equals(id)) return h;
        return null;
    }

    private static MineBerryModule module(String id) {
        for (MineBerryModule m : MineBerryEssentials.modules()) if (m.id().equals(id)) return m;
        return null;
    }

    private static boolean isOn(String id) {
        HudMod h = hudMod(id);
        return h != null ? h.enabled() : Config.isEnabled(id);
    }

    private static void setOn(String id, boolean on) {
        HudMod h = hudMod(id);
        if (h != null) {
            h.setEnabled(on);
            ClientHud.sync();   // keep the active HUD profile in step, like the layout editor does
            return;
        }
        MineBerryModule m = module(id);
        if (m == null) return;
        Config.setEnabled(id, on);
        m.onToggled(on);
    }

    private static String label(String id) {
        HudMod h = hudMod(id);
        if (h != null) return h.name();
        MineBerryModule m = module(id);
        return m == null ? id : m.name();
    }

    private static String toggle(Intent in) {
        String target = in.target();
        if (target.isBlank()) {
            if (lastId == null) return "Which one? Say it like \"turn off compass\" or \"enable airdrop helper\".";
            return applyToggle(lastId, in.on());
        }
        List<Item> found = AerionIntents.resolve(target, items());
        if (found.isEmpty()) return "I don't have a module or HUD element called \"" + target + "\". Say \"list modules\" and I'll show you what there is.";
        if (found.size() == 1) return applyToggle(found.get(0).id(), in.on());
        Boolean on = in.on();
        return ask(found, item -> applyToggle(item.id(), on));
    }

    private static String applyToggle(String id, Boolean on) {
        boolean was = isOn(id);
        boolean want = on == null ? !was : on;
        String name = label(id);
        lastId = id;
        if (was == want) return name + " is already " + (want ? "on" : "off") + ".";
        setOn(id, want);
        undo = () -> { setOn(id, was); lastId = id; return name + " is " + (was ? "on" : "off") + " again."; };
        String out = "Done, " + name + " is " + (want ? "on" : "off") + ".";
        if (!want && SAFETY.contains(id)) out += " Heads up: that one is a safety net.";
        if (!want && (id.equals("aerion_chat") || id.equals("aerion_mini"))) out += " I'm still here in the Aerion tab if you want it back.";
        if (want && id.equals("waypoints")) out += " Your saved waypoints show in the world again.";
        return out;
    }

    private static String listModules() {
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        for (MineBerryModule m : MineBerryEssentials.modules()) (Config.isEnabled(m.id()) ? on : off).add(m.name());
        for (HudMod h : ClientHud.MODS) (h.enabled() ? on : off).add(h.name() + " (HUD)");
        return "On: " + (on.isEmpty() ? "nothing" : String.join(", ", on)) + ".\nOff: " + (off.isEmpty() ? "nothing" : String.join(", ", off)) + ".";
    }

    // ---------------------------------------------------------------- waypoints

    private static List<Item> waypointItems() {
        List<Item> out = new ArrayList<>();
        List<Config.Waypoint> all = Config.waypoints();
        for (int i = 0; i < all.size(); i++) out.add(new Item(String.valueOf(i), all.get(i).name));
        return out;
    }

    /** Removes the waypoint a player means by a name, a number, "nearest", "last"... or asks which one when it is unclear. */
    private static String removeWaypoint(String target) {
        List<Config.Waypoint> all = new ArrayList<>(Config.waypoints());   // snapshot: a later "which one?" answer must not index a changed list
        if (all.isEmpty()) return "You have no waypoints to remove.";
        String t = target.toLowerCase(Locale.ROOT).strip();

        if (t.isEmpty()) {
            if (all.size() == 1) return removeOne(all.get(0));
            return ask(waypointItems(), item -> removeOne(all.get(Integer.parseInt(item.id()))));
        }
        if (t.matches("\\d{1,3}")) {
            int n = Integer.parseInt(t);
            if (n < 1 || n > all.size()) return "You have " + all.size() + " waypoint" + (all.size() == 1 ? "" : "s") + ", so there is no number " + n + ". Say \"list waypoints\" to see them.";
            return removeOne(all.get(n - 1));
        }
        if (t.equals("last") || t.equals("latest") || t.equals("newest") || t.equals("recent")) return removeOne(all.get(all.size() - 1));
        if (t.equals("first") || t.equals("oldest")) return removeOne(all.get(0));
        if (t.equals("nearest") || t.equals("closest") || t.equals("near")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return "Join a world first, then I can tell which waypoint is nearest.";
            String dim = WaypointsModule.dimensionId(mc);
            Config.Waypoint best = null;
            double bestD = Double.MAX_VALUE;
            for (Config.Waypoint wp : all) {
                if (!dim.equals(wp.dim)) continue;
                double d = WaypointsModule.distanceTo(mc, wp);
                if (d < bestD) { bestD = d; best = wp; }
            }
            return best == null ? "None of your waypoints are in this dimension." : removeOne(best);
        }

        List<Item> found = AerionIntents.resolve(t, waypointItems());
        if (found.isEmpty()) return "No waypoint called \"" + target + "\". You have: " + names(all) + ".";
        if (found.size() == 1) return removeOne(all.get(Integer.parseInt(found.get(0).id())));
        return ask(found, item -> removeOne(all.get(Integer.parseInt(item.id()))));
    }

    private static String removeOne(Config.Waypoint wp) {
        List<Config.Waypoint> all = Config.waypoints();
        int index = all.indexOf(wp);
        if (index < 0) return "That waypoint is already gone.";
        all.remove(index);
        Config.save();
        undo = () -> {
            List<Config.Waypoint> now = Config.waypoints();
            now.add(Math.min(index, now.size()), wp);
            Config.save();
            return "Brought back " + wp.name + ".";
        };
        String where = " (" + wp.x + ", " + wp.y + ", " + wp.z + ")";
        return "Removed " + wp.name + where + ". Say \"undo\" if that was a mistake.";
    }

    private static String removeAllWaypoints() {
        int n = Config.waypoints().size();
        if (n == 0) return "You have no waypoints to remove.";
        pending = new Pending(() -> {
            List<Config.Waypoint> gone = new ArrayList<>(Config.waypoints());
            Config.waypoints().clear();
            Config.save();
            undo = () -> {
                Config.waypoints().addAll(gone);
                Config.save();
                return "Brought back all " + gone.size() + " waypoints.";
            };
            return "Done, all " + gone.size() + " waypoint" + (gone.size() == 1 ? " is" : "s are") + " gone. Say \"undo\" to bring them back.";
        }, Clock.ms() + ASK_MS);
        return "That deletes all " + n + " of your waypoints (" + names(Config.waypoints()) + "). Sure? Say yes or no.";
    }

    private static String waypointVisibility(Intent in) {
        List<Config.Waypoint> all = new ArrayList<>(Config.waypoints());   // snapshot: a later "which one?" answer must not index a changed list
        if (all.isEmpty()) return "You have no waypoints.";
        List<Item> found = AerionIntents.resolve(in.target(), waypointItems());
        if (found.isEmpty()) return "No waypoint called \"" + in.target() + "\". You have: " + names(all) + ".";
        Boolean on = in.on();
        if (found.size() == 1) return setVisible(all.get(Integer.parseInt(found.get(0).id())), on);
        return ask(found, item -> setVisible(all.get(Integer.parseInt(item.id())), on));
    }

    private static String setVisible(Config.Waypoint wp, Boolean on) {
        boolean was = wp.visible;
        boolean want = on == null ? !was : on;
        if (was == want) return wp.name + " is already " + (want ? "showing" : "hidden") + ".";
        wp.visible = want;
        Config.save();
        undo = () -> { wp.visible = was; Config.save(); return wp.name + " is " + (was ? "showing" : "hidden") + " again."; };
        return wp.name + " is now " + (want ? "showing" : "hidden") + ".";
    }

    private static String listWaypoints() {
        List<Config.Waypoint> all = Config.waypoints();
        if (all.isEmpty()) return "You have no waypoints yet. Press B in game to add one at your feet.";
        StringBuilder sb = new StringBuilder("Your waypoints:");
        for (int i = 0; i < all.size() && i < 8; i++) {
            Config.Waypoint wp = all.get(i);
            sb.append("\n").append(i + 1).append(". ").append(wp.name).append(" (").append(wp.x).append(", ").append(wp.y).append(", ").append(wp.z).append(")")
                    .append(wp.visible ? "" : " [hidden]");
        }
        if (all.size() > 8) sb.append("\n...and ").append(all.size() - 8).append(" more. The full list is on N.");
        return sb.toString();
    }

    private static String names(List<Config.Waypoint> all) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < all.size() && i < 6; i++) out.add(all.get(i).name);
        return String.join(", ", out) + (all.size() > 6 ? ", ..." : "");
    }

    // ---------------------------------------------------------------- several at once

    private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern.compile("\\s*(?:,|&|\\band\\b)\\s*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * "turn off compass, fps and ping" or "turn on compass and turn off ping". The first part carries the verb; a part with
     * no verb of its own does the same thing to its own name. Empty when the message is not like that.
     */
    private static Optional<String> toggleMany(String text) {
        String[] parts = SPLIT.split(text.strip());
        if (parts.length < 2 || parts.length > 6) return Optional.empty();
        Optional<Intent> first = AerionIntents.parse(parts[0]);
        if (first.isEmpty() || first.get().kind() != AerionIntents.Kind.TOGGLE || first.get().target().isBlank()) return Optional.empty();

        List<Intent> todo = new ArrayList<>();
        todo.add(first.get());
        List<String> unknown = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].strip();
            if (part.isEmpty()) continue;
            Optional<Intent> own = AerionIntents.parse(part);
            if (own.isPresent()) { todo.add(own.get()); continue; }
            String name = part.replaceAll("(?i)\\b(please|pls|too|also|as well|for me)\\b", "").strip();
            if (!name.isEmpty() && !AerionIntents.resolve(name, items()).isEmpty()) todo.add(new Intent(AerionIntents.Kind.TOGGLE, first.get().on(), name));
            else unknown.add(part);
        }
        if (todo.size() < 2) return Optional.empty();

        List<String> out = new ArrayList<>();
        List<Supplier<String>> undos = new ArrayList<>();
        for (Intent in : todo) {
            undo = null;
            String r = run(in);
            out.add(r);
            if (undo != null) undos.add(undo);
            if (r.startsWith("Which one")) { out.add("Tell me which one, then ask for the rest again."); break; }
        }
        if (!unknown.isEmpty()) out.add("I didn't get \"" + String.join("\", \"", unknown) + "\".");
        List<Supplier<String>> reverse = new ArrayList<>(undos);
        java.util.Collections.reverse(reverse);
        undo = reverse.isEmpty() ? null : () -> {
            List<String> done = new ArrayList<>();
            for (Supplier<String> u : reverse) done.add(u.get());
            return String.join("\n", done);
        };
        return Optional.of(String.join("\n", out));
    }

    // ---------------------------------------------------------------- what is going on in the game

    private static String where(Minecraft mc) {
        String dim = WaypointsModule.dimensionId(mc);
        return dim.equals("minecraft:the_nether") ? "the Nether" : dim.equals("minecraft:the_end") ? "the End"
                : dim.equals("minecraft:overworld") ? "the Overworld" : WaypointsModule.shortDimension(dim);
    }

    private static String describe(Minecraft mc, Config.Waypoint wp) {
        double dx = wp.x + 0.5 - mc.player.getX(), dz = wp.z + 0.5 - mc.player.getZ();
        return wp.name + " (" + wp.x + ", " + wp.y + ", " + wp.z + ") is " + WaypointsModule.formatDistance(WaypointsModule.distanceTo(mc, wp))
                + " away, " + AerionQuery.compass(dx, dz) + ", " + AerionQuery.relative(mc.player.getYRot(), dx, dz) + ".";
    }

    /** The answer, or empty when it is not something Aerion can tell from the game (then the guide gets the question). */
    private static Optional<String> answerQuery(AerionQuery.Query q) {
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.player != null && mc.level != null;
        switch (q.kind()) {
            case FPS:
                return Optional.of("You're getting " + mc.getFps() + " FPS.");
            case WHERE_AM_I:
                if (!inWorld) return Optional.of("You're not in a world right now.");
                return Optional.of("You're at " + (int) Math.floor(mc.player.getX()) + ", " + (int) Math.floor(mc.player.getY()) + ", "
                        + (int) Math.floor(mc.player.getZ()) + " in " + where(mc) + ".");
            case NEAREST: {
                if (!inWorld) return Optional.of("Join a world first, then I can tell which waypoint is nearest.");
                String dim = WaypointsModule.dimensionId(mc);
                Config.Waypoint best = null;
                double bestD = Double.MAX_VALUE;
                for (Config.Waypoint wp : Config.waypoints()) {
                    if (!dim.equals(wp.dim)) continue;
                    double d = WaypointsModule.distanceTo(mc, wp);
                    if (d < bestD) { bestD = d; best = wp; }
                }
                if (best == null) return Optional.of(Config.waypoints().isEmpty() ? "You have no waypoints yet. Press B in game to add one."
                        : "None of your waypoints are in " + where(mc) + ".");
                return Optional.of("Nearest: " + describe(mc, best));
            }
            case DISTANCE: {
                List<Item> found = AerionIntents.resolve(q.target(), waypointItems());
                if (found.isEmpty()) return Optional.empty();   // not one of your waypoints: the guide may know it
                if (!inWorld) return Optional.of("Join a world first, then I can tell you how far it is.");
                List<Config.Waypoint> all = new ArrayList<>(Config.waypoints());   // snapshot: a later "which one?" answer must not index a changed list
                if (found.size() == 1) return Optional.of(distanceAnswer(mc, all.get(Integer.parseInt(found.get(0).id()))));
                return Optional.of(ask(found, item -> distanceAnswer(Minecraft.getInstance(), all.get(Integer.parseInt(item.id())))));
            }
            default:
                return Optional.empty();
        }
    }

    private static String distanceAnswer(Minecraft mc, Config.Waypoint wp) {
        if (mc.player == null || mc.level == null) return "Join a world first.";
        if (!WaypointsModule.dimensionId(mc).equals(wp.dim)) {
            return wp.name + " is in " + WaypointsModule.shortDimension(wp.dim) + ", and you're in " + where(mc) + ".";
        }
        return describe(mc, wp);
    }

    // ---------------------------------------------------------------- coordinates

    private static String convertCoords(CoordConvert.Request r) {
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.player != null && mc.level != null;
        if (r.useMine() && !inWorld) return "Join a world first and I'll convert where you stand. Or give me numbers, like \"convert 800 64 -1600 to nether\".";
        CoordConvert.Dim current = inWorld ? CoordConvert.Dim.of(WaypointsModule.dimensionId(mc)) : CoordConvert.Dim.OVERWORLD;
        int px = inWorld ? (int) Math.floor(mc.player.getX()) : 0, py = inWorld ? (int) Math.floor(mc.player.getY()) : 64,
                pz = inWorld ? (int) Math.floor(mc.player.getZ()) : 0;
        CoordConvert.Result res = CoordConvert.convert(r, current, px, py, pz);
        if (res.from() == res.to()) return "You're already in the " + res.to().label + ", so " + res.fromX() + ", " + res.fromZ() + " stays the same.";

        lastConversion = res;
        lastConversionY = res.y();
        String out = res.text() + "  " + res.how();
        if (r.addWaypoint()) out += "\n" + addConversion(res, res.y());
        return out;
    }

    /** Puts a converted position on the waypoints, in the dimension it was converted to. Y 64 when none is known. */
    private static String addConversion(CoordConvert.Result res, Integer y) {
        Minecraft mc = Minecraft.getInstance();
        int wy = y == null ? 64 : y;
        String name = res.to().label + " (" + res.toX() + ", " + res.toZ() + ")";
        Config.Waypoint wp = WaypointsModule.add(mc, name, res.toX(), wy, res.toZ());
        wp.dim = res.to().id;   // add() uses the dimension you are in; this one belongs to the converted-to dimension
        Config.save();
        undo = () -> { Config.waypoints().remove(wp); Config.save(); return "Removed " + wp.name + " again."; };
        String out = "Waypoint added: " + name + (y == null ? ", Y " + wy + " since I didn't have one" : "") + ".";
        boolean elsewhere = mc.level == null || !res.to().id.equals(WaypointsModule.dimensionId(mc));
        if (elsewhere) out += " It shows up when you're in the " + res.to().label + ".";
        if (!Config.isEnabled("waypoints")) out += " Switch on the Waypoints module to see it.";
        return out;
    }

    private static String setDefault(String id) {
        String name = id.equals("minepeak") ? "MinePeak" : "MineBerry";
        String was = Config.aerionDefault();
        if (was.equals(id)) return name + " is already your default Aerion.";
        Config.setAerionDefault(id);
        undo = () -> { Config.setAerionDefault(was); return (was.equals("minepeak") ? "MinePeak" : "MineBerry") + " is your default Aerion again."; };
        return "Done, " + name + " is now your default Aerion for !aerion and the mini chat."
                + (AerionBrain.forServer(id).isReady() ? "" : " Its guide is still empty though, so it can't answer much yet.");
    }

    // ---------------------------------------------------------------- the rest

    private static String undoLast() {
        Supplier<String> u = undo;
        undo = null;
        return u == null ? "There is nothing to undo." : u.get();
    }

    private static String testAlert() {
        if (!AerionNotifier.enabled()) return "Aerion Alerts are switched off. Say \"turn on alerts\" and ask me again.";
        AerionNotifier.info("This is what an Aerion alert looks like. I only show these for things that matter.");
        return "Sent. Look at the top of your screen.";
    }

    // ---------------------------------------------------------------- asking which one

    private static String ask(List<Item> options, Function<Item, String> then) {
        List<Item> shown = options.size() > MAX_OPTIONS ? options.subList(0, MAX_OPTIONS) : options;
        choice = new Choice(shown, then, Clock.ms() + ASK_MS);
        StringBuilder sb = new StringBuilder("Which one do you mean?");
        for (int i = 0; i < shown.size(); i++) sb.append("\n").append(i + 1).append(". ").append(shown.get(i).name());
        if (options.size() > shown.size()) sb.append("\n(more match; say a longer name to narrow it down)");
        return sb.toString();
    }

    /** "2", "the second one" style numbers, or a name that points at exactly one of the options. */
    private static Item pick(String text, List<Item> options) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.matches("\\d{1,2}")) {
            int n = Integer.parseInt(t);
            return n >= 1 && n <= options.size() ? options.get(n - 1) : null;
        }
        List<Item> found = AerionIntents.resolve(t, options);
        return found.size() == 1 ? found.get(0) : null;
    }
}
