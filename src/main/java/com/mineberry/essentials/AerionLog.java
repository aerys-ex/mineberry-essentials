package com.mineberry.essentials;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A plain text list of what players asked Aerion that it could not answer, so the guide can be improved
 * (config/mineberry_essentials_aerion_unanswered.txt, one "server TAB question" per line). It stays on the player's own
 * computer: nothing is sent anywhere. Each question is written once per game session and the file stops growing at 64 KB.
 */
public final class AerionLog {

    private static final long MAX_BYTES = 64 * 1024;
    private static final Set<String> SEEN = new HashSet<>();

    private AerionLog() {}

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mineberry_essentials_aerion_unanswered.txt");
    }

    /** Notes a question Aerion did not know. Never throws. */
    public static synchronized void unanswered(String serverId, String question) {
        try {
            if (question == null) return;
            String q = question.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
            if (q.length() < 3 || q.length() > 160) return;
            Path f = file();
            if (Files.exists(f) && Files.size(f) > MAX_BYTES) return;   // full: stop before remembering anything more
            if (!SEEN.add(serverId + "\t" + q.toLowerCase(Locale.ROOT))) return;
            Files.writeString(f, serverId + "\t" + q + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // a log that fails is not worth bothering anyone about
        }
    }
}
