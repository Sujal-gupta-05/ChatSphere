package com.chatsphere.server.dev;

import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.Codec;
import com.chatsphere.common.protocol.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny terminal client for testing the server before any UI exists (a dev tool, not a product).
 *
 *   mvn compile exec:java -Dexec.mainClass=com.chatsphere.server.dev.ConsoleClient -Dexec.args="localhost 5050"
 *
 * You type    PM|5|bob|hello\nthere          (fields separated by |, \n = line break, \\ = backslash, \| = a pipe)
 * It sends    the correctly encoded frame (via Codec) and prints every frame the server sends in the same | format.
 *
 * Extra test commands (start with /):
 *   /big N      send one raw line of N characters (use 70000 to test the oversized-line rule)
 *   /raw TEXT   send TEXT exactly as typed, without encoding (to test malformed frames)
 *   /quit       close the connection and exit
 */
public final class ConsoleClient {
    private ConsoleClient() {}

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : Protocol.DEFAULT_PORT;
        try (Socket socket = new Socket(host, port)) {
            System.err.println("Connected to " + host + ":" + port + ". Type frames like PING|1 ; /quit to leave.");
            run(socket, System.in, System.out, () -> {
                System.err.println("[connection closed by server]");
                System.exit(0);
            });
        } catch (java.net.ConnectException e) {
            System.err.println("Cannot connect to " + host + ":" + port + " - is the server running?");
            System.exit(1);
        }
    }

    /**
     * Pumps frames in both directions until stdin ends or /quit is typed. A background thread prints incoming frames;
     * onServerClosed runs when the server closes the connection.
     */
    static void run(Socket socket, InputStream stdin, PrintStream out, Runnable onServerClosed) throws IOException {
        BufferedReader fromServer = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread printer = new Thread(() -> {
            try {
                for (String line; (line = fromServer.readLine()) != null; ) out.println(format(line));
            } catch (IOException ignored) {
                // socket closed by us or by the server
            }
            if (!socket.isClosed()) onServerClosed.run();
        }, "console-printer");
        printer.setDaemon(true);
        printer.start();

        OutputStream toServer = socket.getOutputStream();
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        for (String input; (input = keyboard.readLine()) != null; ) {
            if (input.isBlank()) continue;
            if (input.equals("/quit")) break;
            try {
                String line = lineFor(input);
                toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                toServer.flush();
            } catch (ChatException e) {
                System.err.println("Cannot encode that input: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("Cannot send (connection closed?): " + e.getMessage());
                break;
            }
        }
    }

    /** What to put on the wire for one line the user typed (adds the special / commands). */
    static String lineFor(String input) {
        if (input.startsWith("/big ")) {
            int n = Integer.parseInt(input.substring(5).trim());
            return "X".repeat(n);
        }
        if (input.startsWith("/raw ")) return input.substring(5);
        return toFrame(input);
    }

    /** "PM|5|bob|hi\nthere" -> encoded frame. Throws ChatException(BAD_REQUEST) for a bad escape such as \q. */
    static String toFrame(String input) {
        List<String> parts = splitOnPipes(input);
        if (parts.get(0).isEmpty()) throw new ChatException(com.chatsphere.common.protocol.ErrorCode.BAD_REQUEST, "missing command");
        List<String> fields = new ArrayList<>();
        for (int i = 1; i < parts.size(); i++) fields.add(Codec.unescape(parts.get(i)));
        return Codec.encode(parts.get(0), fields);
    }

    /** Splits on '|' but keeps "\|" (turned into a literal pipe) and leaves all other escapes for Codec.unescape. */
    private static List<String> splitOnPipes(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '|') cur.append('|'); else cur.append(c).append(n);
                i++;
            } else if (c == '|') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    /** Server frame -> "NAME|field|field" with line breaks shown as \n. Garbage is shown as-is after "??". */
    static String format(String frame) {
        try {
            List<String> f = Codec.decode(frame);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < f.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(Codec.escape(f.get(i)).replace("|", "\\|"));
            }
            return sb.toString();
        } catch (ChatException e) {
            return "?? " + frame;
        }
    }
}
