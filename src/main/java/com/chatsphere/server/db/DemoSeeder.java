package com.chatsphere.server.db;

import com.chatsphere.common.model.MessageRecord;
import com.chatsphere.common.protocol.ChatException;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.server.service.ChatService;
import com.chatsphere.server.service.ChatServiceImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Fills a database with demo data for the presentation.
 * Usage: {@code DemoSeeder chatsphere.db} (default file name chatsphere.db).
 *
 * <p>Everything goes through the service layer (never raw SQL), so the data always obeys the rules.
 * Refuses to run when the user "alice" already exists.
 */
public final class DemoSeeder {
    private DemoSeeder() {}

    public static final String PASSWORD = "password1";

    private static final String[] USERS = {"alice", "bob", "carol", "demo"};

    private static final String[] LINES = {
        "Morning everyone! Standup in 10 minutes.",
        "I pushed the new build to the test server.",
        "The build is green again after the fix.",
        "Can someone review my pull request?",
        "Lunch at 1? The new place near the station.",
        "Reminder: demo rehearsal tomorrow at 4 pm.",
        "Found a bug in the login screen, looking into it.",
        "Thanks, that fixed it for me!",
        "Who has the notes from yesterday's meeting?",
        "Let's keep the protocol frozen until Friday.",
        "Great work on the dark theme, it looks clean.",
        "Database backup finished without errors.",
        "I'll update the README tonight.",
        "Does anyone know why the build is slow today?",
        "Coffee break, back in 15.",
        "Typing indicator works now, tested with two clients.",
        "Search is fast even with a lot of messages.",
        "Please don't commit the .db file.",
        "Merged to main, please pull.",
        "Almost there, only polishing left.",
    };

    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "chatsphere.db";
        boolean seeded;
        try (ChatServiceImpl service = new ChatServiceImpl(path)) {
            seeded = seed(service);
        }
        if (seeded) {
            System.out.println("Demo data written to " + path
                    + ". Users: alice, bob, carol, demo (password: " + PASSWORD + ").");
        } else {
            System.err.println("Refusing to seed " + path + ": user 'alice' already exists. "
                    + "Delete the file (and its -wal/-shm files) to start over.");
            System.exit(1);
        }
    }

    /** @return false (and changes nothing) when alice already exists. */
    public static boolean seed(ChatService s) {
        try {
            s.register("alice", PASSWORD);
        } catch (ChatException e) {
            if (e.code() == ErrorCode.USERNAME_TAKEN) return false;
            throw e;
        }
        for (int i = 1; i < USERS.length; i++) s.register(USERS[i], PASSWORD);

        // ---- general: all four members, 130 messages so "Load earlier" can be shown ----
        s.createRoom("alice", "general");
        for (int i = 1; i < USERS.length; i++) s.joinRoom(USERS[i], "general");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            String text = (i % 25 == 24)
                    ? "Quick summary:\n- backend done\n- UI in progress\n- report next"
                    : LINES[i % LINES.length];
            MessageRecord m = s.saveRoomMessage(USERS[i % USERS.length], "general", text);
            ids.add(m.id());
        }
        long newest = ids.get(ids.size() - 1);
        for (String u : new String[]{"alice", "bob", "carol"}) s.markRoomRead(u, "general", newest);
        s.markRoomRead("demo", "general", ids.get(119)); // demo has about 10 unread messages

        // ---- random and design ----
        s.createRoom("carol", "random");
        s.joinRoom("alice", "random");
        s.joinRoom("bob", "random");
        s.joinRoom("demo", "random");
        s.saveRoomMessage("carol", "random", "Anyone up for a board game night on Friday?");
        s.saveRoomMessage("bob", "random", "Yes! I'll bring snacks.");
        s.saveRoomMessage("alice", "random", "Count me in 🎲");
        s.saveRoomMessage("carol", "random", "Great, 7 pm then.");

        s.createRoom("bob", "design");
        s.joinRoom("alice", "design");
        s.joinRoom("carol", "design");
        s.saveRoomMessage("bob", "design", "Draft of the new color tokens is ready.");
        s.saveRoomMessage("alice", "design", "Looks good. Can we make the dark theme a bit warmer?");
        s.saveRoomMessage("carol", "design", "Agreed, I'll adjust the background and send a screenshot.");

        // ---- private conversations ----
        // alice <-> bob: fully read both ways, except the last reply which alice has not read yet
        s.savePrivateMessage("alice", "bob", "Hey Bob, did you finish the schema?");
        s.savePrivateMessage("bob", "alice", "Yes, indexes included.");
        s.savePrivateMessage("alice", "bob", "Perfect. Can you also seed demo data?");
        s.markAllDeliveredFor("bob");
        s.markAllDeliveredFor("alice");
        s.markPrivateRead("bob", "alice", Long.MAX_VALUE);
        s.markPrivateRead("alice", "bob", Long.MAX_VALUE);
        s.savePrivateMessage("bob", "alice", "Done! Run the seeder on an empty database.");
        s.markAllDeliveredFor("alice");

        // messages to demo that demo has not read yet (demo is offline, so they stay SENT)
        s.savePrivateMessage("demo", "alice", "Hi Alice, I'm trying out the app.");
        s.markAllDeliveredFor("alice");
        s.markPrivateRead("alice", "demo", Long.MAX_VALUE);
        s.savePrivateMessage("alice", "demo", "Welcome to ChatSphere!");
        s.savePrivateMessage("alice", "demo", "Hey, are you free for a quick call?");
        s.savePrivateMessage("alice", "demo", "Search for \"build\" in the general room to try the filters.");
        s.savePrivateMessage("bob", "demo", "Don't forget the demo rehearsal tomorrow.");
        s.savePrivateMessage("bob", "demo", "I put the slides in the shared folder.");
        s.savePrivateMessage("carol", "demo", "Can you review the color tokens?");
        return true;
    }
}
