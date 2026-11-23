package com.chatsphere;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads src/test/resources/golden-frames.txt. Lines come back with real TAB characters instead of "<T>". */
public final class GoldenFrames {
    private GoldenFrames() {}

    private static List<String> section(String marker) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        try (InputStream is = GoldenFrames.class.getResourceAsStream("/golden-frames.txt")) {
            if (is == null) throw new IllegalStateException("golden-frames.txt not on the test classpath");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) continue;
                if (line.startsWith("#")) {
                    if (line.contains("client -> server")) in = marker.equals("client");
                    else if (line.contains("server -> client")) in = marker.equals("server");
                    continue;
                }
                if (in) out.add(line.replace("<T>", "\t"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public static List<String> clientFrames() { return section("client"); }

    public static List<String> serverFrames() { return section("server"); }

    /** Server frames keyed by command name (first field); every command appears once in the golden file. */
    public static Map<String, String> serverByCommand() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : serverFrames()) m.put(l.split("\t", -1)[0], l);
        return m;
    }
}
