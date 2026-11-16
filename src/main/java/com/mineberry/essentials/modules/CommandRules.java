package com.mineberry.essentials.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Small helpers shared by the command-blocking modules. */
final class CommandRules {

    private CommandRules() {}

    /** "/Essentials:MSG Steve hi" -> "msg". Namespaces are dropped so "minecraft:tp" can't slip past a rule. */
    static String root(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        int space = c.indexOf(' ');
        String first = (space < 0 ? c : c.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }

    /** Lower-cased, slash-free copy of a config list. */
    static Set<String> normalize(List<String> list) {
        Set<String> out = new HashSet<>();
        if (list == null) return out;
        for (String s : list) {
            if (s == null) continue;
            String c = s.trim().toLowerCase(Locale.ROOT);
            if (c.startsWith("/")) c = c.substring(1);
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }
}
