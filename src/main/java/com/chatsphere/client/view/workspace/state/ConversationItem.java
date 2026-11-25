package com.chatsphere.client.view.workspace.state;

import com.chatsphere.common.model.TargetType;
import javafx.beans.property.*;

/**
 * Data model for sidebar rows (rooms and private users).
 * Owned by Person 4.
 */
public class ConversationItem {
    private final TargetType type;
    private final String key;
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty preview = new SimpleStringProperty("");
    private final LongProperty lastTimestamp = new SimpleLongProperty(0);
    private final IntegerProperty unread = new SimpleIntegerProperty(0);
    private final BooleanProperty online = new SimpleBooleanProperty(false);
    private final BooleanProperty joined = new SimpleBooleanProperty(true);
    private final IntegerProperty memberCount = new SimpleIntegerProperty(0);

    public ConversationItem(TargetType type, String key, String title) {
        this.type = type;
        this.key = key;
        this.title.set(title);
    }

    public TargetType type() { return type; }
    public String key() { return key; }

    public StringProperty titleProperty() { return title; }
    public String title() { return title.get(); }
    public void setTitle(String t) { title.set(t); }

    public StringProperty previewProperty() { return preview; }
    public String preview() { return preview.get(); }
    public void setPreview(String p) { preview.set(p); }

    public LongProperty lastTimestampProperty() { return lastTimestamp; }
    public long lastTimestamp() { return lastTimestamp.get(); }
    public void setLastTimestamp(long ts) { lastTimestamp.set(ts); }

    public IntegerProperty unreadProperty() { return unread; }
    public int unread() { return unread.get(); }
    public void setUnread(int u) { unread.set(u); }

    public BooleanProperty onlineProperty() { return online; }
    public boolean isOnline() { return online.get(); }
    public void setOnline(boolean o) { online.set(o); }

    public BooleanProperty joinedProperty() { return joined; }
    public boolean isJoined() { return joined.get(); }
    public void setJoined(boolean j) { joined.set(j); }

    public IntegerProperty memberCountProperty() { return memberCount; }
    public int memberCount() { return memberCount.get(); }
    public void setMemberCount(int count) { memberCount.set(count); }

    @Override
    public String toString() {
        return "ConversationItem[" + type + ":" + key + ", unread=" + unread.get() + "]";
    }
}
