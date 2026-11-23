package com.chatsphere.client.state;

import com.chatsphere.client.state.SearchViewModel.Scope;
import com.chatsphere.client.state.SearchViewModel.State;
import com.chatsphere.common.model.SearchHit;
import com.chatsphere.common.model.SearchQuery;
import com.chatsphere.common.model.TargetType;
import com.chatsphere.common.model.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** No JavaFX toolkit and no client: building queries and the idle path never talk to the server. */
class SearchViewModelTest {
    private SearchViewModel vm;

    private static long start(LocalDate d) { return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }

    @BeforeEach void setUp() { vm = new SearchViewModel(null, ZoneOffset.UTC); }

    @Test void blankKeywordBuildsNoQuery() {
        assertNull(vm.buildQuery());
        vm.queryProperty().set("   ");
        assertNull(vm.buildQuery());
    }

    @Test void blankKeywordSearchStaysIdleAndSendsNothing() {
        vm.search();   // client is null: a request would throw
        assertEquals(State.IDLE, vm.state());
        assertTrue(vm.results().isEmpty());
    }

    @Test void defaultsSearchEverywhereFromAnyone() {
        vm.queryProperty().set("  build ");
        SearchQuery q = vm.buildQuery();
        assertEquals("build", q.keyword());
        assertEquals("", q.sender());
        assertEquals(0, q.fromTs());
        assertEquals(0, q.toTs());
        assertFalse(q.currentOnly());
    }

    @Test void senderFilterIsPassedAndAnyoneMeansEmpty() {
        vm.queryProperty().set("x");
        vm.senderProperty().set("bob");
        assertEquals("bob", vm.buildQuery().sender());
        vm.senderProperty().set(SearchViewModel.ANYONE);
        assertEquals("", vm.buildQuery().sender());
    }

    @Test void conversationEnablesAndSelectsThisConversation() {
        assertFalse(vm.hasCurrentProperty().get());
        vm.setCurrent(TargetType.ROOM, "general");
        assertTrue(vm.hasCurrentProperty().get());
        assertEquals(Scope.CURRENT, vm.scopeProperty().get());
        vm.queryProperty().set("x");
        SearchQuery q = vm.buildQuery();
        assertTrue(q.currentOnly());
        assertEquals(TargetType.ROOM, q.targetType());
        assertEquals("general", q.target());
    }

    @Test void privateConversationUsesTheOtherUsersName() {
        vm.setCurrent(TargetType.PRIVATE, "bob");
        vm.queryProperty().set("x");
        SearchQuery q = vm.buildQuery();
        assertEquals(TargetType.PRIVATE, q.targetType());
        assertEquals("bob", q.target());
    }

    @Test void clearingTheConversationFallsBackToEverywhere() {
        vm.setCurrent(TargetType.ROOM, "general");
        vm.setCurrent(null, null);
        assertFalse(vm.hasCurrentProperty().get());
        assertEquals(Scope.ALL, vm.scopeProperty().get());
        vm.queryProperty().set("x");
        assertFalse(vm.buildQuery().currentOnly());
    }

    @Test void thisConversationWithoutAConversationIsNotRestricted() {
        vm.scopeProperty().set(Scope.CURRENT);
        vm.queryProperty().set("x");
        SearchQuery q = vm.buildQuery();
        assertFalse(q.currentOnly());
        assertEquals("", q.target());
    }

    @Test void everywhereIgnoresTheConversation() {
        vm.setCurrent(TargetType.ROOM, "general");
        vm.scopeProperty().set(Scope.ALL);
        vm.queryProperty().set("x");
        assertFalse(vm.buildQuery().currentOnly());
    }

    @Test void dateRangeIsStartOfFromDayToEndOfToDay() {
        vm.queryProperty().set("x");
        vm.fromProperty().set(LocalDate.of(2026, 11, 10));
        vm.toProperty().set(LocalDate.of(2026, 11, 12));
        SearchQuery q = vm.buildQuery();
        assertEquals(start(LocalDate.of(2026, 11, 10)), q.fromTs());
        assertEquals(start(LocalDate.of(2026, 11, 13)) - 1, q.toTs());
    }

    @Test void oneSidedRangesStayUnbounded() {
        vm.queryProperty().set("x");
        vm.fromProperty().set(LocalDate.of(2026, 11, 10));
        assertEquals(0, vm.buildQuery().toTs());
        vm.fromProperty().set(null);
        vm.toProperty().set(LocalDate.of(2026, 11, 10));
        assertEquals(0, vm.buildQuery().fromTs());
    }

    @Test void reversedRangeIsSwapped() {
        vm.queryProperty().set("x");
        vm.fromProperty().set(LocalDate.of(2026, 11, 12));
        vm.toProperty().set(LocalDate.of(2026, 11, 10));
        SearchQuery q = vm.buildQuery();
        assertTrue(q.fromTs() < q.toTs());
        assertEquals(start(LocalDate.of(2026, 11, 10)), q.fromTs());
    }

    @Test void sendersListStartsWithAnyoneThenSortedNames() {
        List<UserSummary> users = List.of(new UserSummary("carol", false, 0, "", 0), new UserSummary("alice", true, 0, "", 0));
        assertEquals(List.of("Anyone", "alice", "carol"), SearchViewModel.sendersFrom(users));
    }

    @Test void resultLabelsAndDates() {
        assertEquals("#general", SearchViewModel.scopeLabel(new SearchHit(1, TargetType.ROOM, "general", "a", 0, "t")));
        assertEquals("Private with bob", SearchViewModel.scopeLabel(new SearchHit(1, TargetType.PRIVATE, "bob", "a", 0, "t")));
        assertEquals("1 Jan 1970, 00:00", SearchViewModel.formatDate(0, ZoneOffset.UTC));
    }
}
