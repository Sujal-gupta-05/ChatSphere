package com.chatsphere.client.state;

import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.net.ChatClient;
import com.chatsphere.common.model.SearchHit;
import com.chatsphere.common.model.SearchQuery;
import com.chatsphere.common.model.TargetType;
import com.chatsphere.common.model.UserSummary;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Owned by Person 3. State and query building of the Search panel. Only JavaFX properties, so tests need no toolkit.
 * Futures complete on the FX thread (ChatClient contract); stale answers are ignored.
 */
public class SearchViewModel {
    public enum Scope { CURRENT, ALL }
    public enum State { IDLE, LOADING, RESULTS, EMPTY, ERROR }

    public static final String ANYONE = "Anyone";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final ChatClient client;
    private final ZoneId zone;

    private final StringProperty query = new SimpleStringProperty("");
    private final ObjectProperty<String> sender = new SimpleObjectProperty<>(ANYONE);
    private final ObjectProperty<LocalDate> from = new SimpleObjectProperty<>();
    private final ObjectProperty<LocalDate> to = new SimpleObjectProperty<>();
    private final ObjectProperty<Scope> scope = new SimpleObjectProperty<>(Scope.ALL);
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.IDLE);
    private final StringProperty errorText = new SimpleStringProperty("");
    private final BooleanProperty hasCurrent = new SimpleBooleanProperty(false);
    private final ObservableList<SearchHit> results = FXCollections.observableArrayList();
    private final ObservableList<String> senders = FXCollections.observableArrayList(ANYONE);

    private TargetType currentType;
    private String currentTarget;
    private String lastKeyword = "";
    private int sequence;
    private boolean usersLoaded;

    public SearchViewModel(ChatClient client, ZoneId zone) {
        this.client = client;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ properties
    public StringProperty queryProperty() { return query; }
    public ObjectProperty<String> senderProperty() { return sender; }
    public ObjectProperty<LocalDate> fromProperty() { return from; }
    public ObjectProperty<LocalDate> toProperty() { return to; }
    public ObjectProperty<Scope> scopeProperty() { return scope; }
    public ReadOnlyObjectProperty<State> stateProperty() { return state; }
    public State state() { return state.get(); }
    public ReadOnlyStringProperty errorTextProperty() { return errorText; }
    /** True while a conversation is selected, so "This conversation" can be chosen. */
    public ReadOnlyBooleanProperty hasCurrentProperty() { return hasCurrent; }
    public ObservableList<SearchHit> results() { return results; }
    /** "Anyone" first, then usernames. */
    public ObservableList<String> senders() { return senders; }
    /** The keyword of the search whose results are shown (for highlighting). */
    public String lastKeyword() { return lastKeyword; }
    public ZoneId zone() { return zone; }

    // ------------------------------------------------------------------ conversation
    /** Pass a null target (or type) to clear. With a conversation, "This conversation" becomes available and is selected. */
    public void setCurrent(TargetType type, String target) {
        boolean had = hasCurrent.get();
        boolean changed = !(Objects.equals(type, currentType) && Objects.equals(target, currentTarget));
        Scope before = scope.get();
        if (type == null || target == null) {
            currentType = null;
            currentTarget = null;
            hasCurrent.set(false);
            scope.set(Scope.ALL);
        } else {
            currentType = type;
            currentTarget = target;
            hasCurrent.set(true);
            if (!had) scope.set(Scope.CURRENT);
        }
        // a scope change already triggers a refresh in the view; otherwise refresh results scoped to the old conversation
        if (scope.get() == before && changed && scope.get() == Scope.CURRENT && state.get() != State.IDLE) search();
    }

    // ------------------------------------------------------------------ query
    /** The request for the current inputs, or null when the keyword is blank (no request is made then). */
    public SearchQuery buildQuery() {
        String keyword = query.get() == null ? "" : query.get().trim();
        if (keyword.isEmpty()) return null;
        String who = sender.get() == null || ANYONE.equals(sender.get()) ? "" : sender.get();
        LocalDate f = from.get(), t = to.get();
        if (f != null && t != null && f.isAfter(t)) { LocalDate swap = f; f = t; t = swap; }
        long fromTs = f == null ? 0 : f.atStartOfDay(zone).toInstant().toEpochMilli();
        long toTs = t == null ? 0 : t.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;   // end of day, inclusive
        boolean current = scope.get() == Scope.CURRENT && hasCurrent.get();
        return new SearchQuery(keyword, who, fromTs, toTs, current,
                current ? currentType : TargetType.ROOM, current ? currentTarget : "");
    }

    // ------------------------------------------------------------------ actions
    /** Runs the search. A blank keyword makes no request and returns to the idle hint. */
    public void search() {
        SearchQuery q = buildQuery();
        int mine = ++sequence;
        if (q == null) {
            results.clear();
            state.set(State.IDLE);
            return;
        }
        lastKeyword = q.keyword();
        state.set(State.LOADING);
        client.search(q).whenComplete((hits, t) -> {
            if (mine != sequence) return;   // a newer search superseded this one
            if (t != null) {
                errorText.set(ErrorMessages.of(t));
                state.set(State.ERROR);
            } else {
                results.setAll(hits);
                state.set(hits.isEmpty() ? State.EMPTY : State.RESULTS);
            }
        });
    }

    /** Called when a filter changes: re-run only if there is something to search for. */
    public void searchIfActive() {
        if (buildQuery() != null) search();
    }

    /** Fills the sender filter once (from listUsers). */
    public void loadUsers() {
        if (usersLoaded) return;
        usersLoaded = true;
        client.listUsers().whenComplete((users, t) -> {
            if (t != null) { usersLoaded = false; return; }   // try again next time the panel is focused
            senders.setAll(sendersFrom(users));
        });
    }

    static List<String> sendersFrom(List<UserSummary> users) {
        List<String> names = users.stream().map(UserSummary::username).sorted(Comparator.naturalOrder()).toList();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(names.size() + 1);
        out.add(ANYONE);
        out.addAll(names);
        return out;
    }

    // ------------------------------------------------------------------ display helpers
    /** "#general" or "Private with bob". */
    public static String scopeLabel(SearchHit hit) {
        return hit.targetType() == TargetType.ROOM ? "#" + hit.target() : "Private with " + hit.target();
    }

    /** Local time, for example "12 Nov 2026, 14:05". */
    public static String formatDate(long epochMillis, ZoneId zone) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }
}
