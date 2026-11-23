package com.chatsphere.server.dev;

import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.server.net.ChatServer;
import com.chatsphere.server.service.InMemoryChatService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleClientTest {

    @Test void toFrameEncodesPipeSeparatedInput() {
        assertEquals("PM\t5\tbob\thello", ConsoleClient.toFrame("PM|5|bob|hello"));
    }

    @Test void typedBackslashNBecomesALineBreakInsideOneFrame() {
        String frame = ConsoleClient.toFrame("MSG|4|general|hello\\nthere");
        assertEquals("MSG\t4\tgeneral\thello\\nthere", frame);          // still one line on the wire
        assertFalse(frame.contains("\n"));
    }

    @Test void escapedBackslashAndEscapedPipe() {
        assertEquals("MSG\t1\tgeneral\ta\\\\nb", ConsoleClient.toFrame("MSG|1|general|a\\\\nb"));   // a \ n b
        assertEquals("MSG\t1\tgeneral\ta|b", ConsoleClient.toFrame("MSG|1|general|a\\|b"));
    }

    @Test void emptyFieldsAreKept() {
        assertEquals("SEARCH\t9\tbuild\t\t0\t0\tALL\t\t", ConsoleClient.toFrame("SEARCH|9|build||0|0|ALL||"));
    }

    @Test void commandOnlyInput() {
        assertEquals("SESSION_REPLACED", ConsoleClient.toFrame("SESSION_REPLACED"));
    }

    @Test void badEscapeAndMissingCommandAreRejected() {
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ChatException.class, () -> ConsoleClient.toFrame("PM|1|bob|bad\\q")).code());
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(ChatException.class, () -> ConsoleClient.toFrame("|1|x")).code());
    }

    @Test void formatPrintsPipesAndShowsLineBreaksAsBackslashN() {
        assertEquals("MSG_PUSH|general|52|alice|1767225600000|hello\\nthere",
                ConsoleClient.format("MSG_PUSH\tgeneral\t52\talice\t1767225600000\thello\\nthere"));
        assertEquals("OK|1", ConsoleClient.format("OK\t1"));
        assertEquals("ROOM_LIST_RESULT|3|MINE|1|general|4|1|0||0",
                ConsoleClient.format("ROOM_LIST_RESULT\t3\tMINE\t1\tgeneral\t4\t1\t0\t\t0"));
    }

    @Test void formatOfGarbageIsMarked() {
        assertEquals("?? bad\\", ConsoleClient.format("bad\\"));
    }

    @Test void specialCommands() {
        assertEquals(70_000, ConsoleClient.lineFor("/big 70000").length());
        assertEquals("not\tencoded", ConsoleClient.lineFor("/raw not\tencoded"));
    }

    @Test void endToEndAgainstARealServer() throws Exception {
        ChatServer server = new ChatServer(0, new InMemoryChatService());
        int port = server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            PipedOutputStream keyboard = new PipedOutputStream();
            PipedInputStream stdin = new PipedInputStream(keyboard);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
            Thread t = Thread.ofVirtual().start(() -> {
                try { ConsoleClient.run(socket, stdin, out, () -> { }); } catch (Exception ignored) { }
            });
            keyboard.write("REGISTER|1|alice|password1\nLOGIN|2|alice|password1\nPING|3\n/raw garbage\n".getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
            String expected = "OK|1\nLOGIN_OK|2|alice\nPONG|3\nERROR|0|UNKNOWN_COMMAND\n";
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end && !captured.toString(StandardCharsets.UTF_8).replace("\r", "").equals(expected))
                Thread.sleep(20);
            assertEquals(expected, captured.toString(StandardCharsets.UTF_8).replace("\r", ""));
            keyboard.write("/quit\n".getBytes(StandardCharsets.UTF_8));
            keyboard.flush();
            t.join(2000);
            assertFalse(t.isAlive());
        } finally {
            server.stop();
        }
    }
}
