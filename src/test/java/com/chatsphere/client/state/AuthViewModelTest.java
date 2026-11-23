package com.chatsphere.client.state;

import com.chatsphere.client.component.Severity;
import com.chatsphere.client.net.ConnectionState;
import com.chatsphere.client.state.AuthViewModel.Field;
import com.chatsphere.client.state.AuthViewModel.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** No JavaFX toolkit needed: the view-model only uses properties. The client is null, so any server call would fail loudly. */
class AuthViewModelTest {
    private SessionState session;
    private AuthViewModel vm;
    private final List<Field> focused = new ArrayList<>();

    @BeforeEach void setUp() {
        session = new SessionState();
        vm = new AuthViewModel(null, session, new ClientConfig("localhost", 5050), new AuthViewModel.Actions() {
            @Override public void toast(String text, Severity severity) { }
            @Override public void authenticated(String username) { }
        });
        vm.setFocusHandler(focused::add);
    }

    @Test void configIsPrefilled() {
        assertEquals("localhost", vm.hostProperty().get());
        assertEquals("5050", vm.portProperty().get());
    }

    @Test void usernameRule() {
        vm.usernameProperty().set("ab");
        assertFalse(vm.validateUsername());
        assertEquals("Use 3 to 20 letters, numbers or underscores.", vm.usernameErrorProperty().get());
        vm.usernameProperty().set("alice_1");
        assertTrue(vm.validateUsername());
        assertEquals("", vm.usernameErrorProperty().get());
    }

    @Test void loginPasswordOnlyHasToBeNonEmpty() {
        vm.passwordProperty().set("");
        assertFalse(vm.validatePassword());
        assertEquals("Enter your password.", vm.passwordErrorProperty().get());
        vm.passwordProperty().set("abc");
        assertTrue(vm.validatePassword());
    }

    @Test void registerPasswordNeedsEightCharacters() {
        vm.setMode(Mode.REGISTER);
        vm.passwordProperty().set("short");
        assertFalse(vm.validatePassword());
        assertEquals("Use at least 8 characters.", vm.passwordErrorProperty().get());
        vm.passwordProperty().set("longenough");
        assertTrue(vm.validatePassword());
    }

    @Test void confirmMustMatchInRegisterMode() {
        vm.setMode(Mode.REGISTER);
        vm.passwordProperty().set("longenough");
        vm.confirmProperty().set("different1");
        assertFalse(vm.validateConfirm());
        assertEquals("Passwords don't match.", vm.confirmErrorProperty().get());
        vm.confirmProperty().set("longenough");
        assertTrue(vm.validateConfirm());
    }

    @Test void confirmIsIgnoredInLoginMode() {
        vm.passwordProperty().set("a");
        vm.confirmProperty().set("b");
        assertTrue(vm.validateConfirm());
    }

    @Test void editingClearsTheFieldError() {
        vm.usernameProperty().set("a");
        vm.validateUsername();
        assertFalse(vm.usernameErrorProperty().get().isEmpty());
        vm.usernameProperty().set("ab");
        assertEquals("", vm.usernameErrorProperty().get());
    }

    @Test void canSubmitNeedsConnectionAndNothingPending() {
        assertFalse(vm.canSubmit().get());
        session.connectionStateProperty().set(ConnectionState.CONNECTING);
        assertFalse(vm.canSubmit().get());
        session.connectionStateProperty().set(ConnectionState.CONNECTED);
        assertTrue(vm.canSubmit().get());
        vm.pendingProperty().set(true);
        assertFalse(vm.canSubmit().get());
    }

    @Test void invalidSubmitFocusesFirstInvalidFieldAndSendsNothing() {
        session.connectionStateProperty().set(ConnectionState.CONNECTED);
        vm.setMode(Mode.REGISTER);
        vm.usernameProperty().set("alice");
        vm.passwordProperty().set("short");
        vm.confirmProperty().set("other");
        vm.submit();                       // client is null: a server call would throw
        assertEquals(List.of(Field.PASSWORD), focused);
        assertFalse(vm.pendingProperty().get());
        assertEquals("Use at least 8 characters.", vm.passwordErrorProperty().get());
    }

    @Test void submitIgnoredWhileOffline() {
        vm.usernameProperty().set("");
        vm.submit();
        assertTrue(focused.isEmpty());
        assertEquals("", vm.usernameErrorProperty().get());
    }

    @Test void switchingModeClearsPasswordsAndErrors() {
        vm.passwordProperty().set("secret");
        vm.errorProperty().set("Username or password is incorrect.");
        vm.toggleMode();
        assertEquals(Mode.REGISTER, vm.mode());
        assertEquals("", vm.passwordProperty().get());
        assertEquals("", vm.errorProperty().get());
    }

    @Test void portParsing() {
        assertEquals(5050, AuthViewModel.parsePort("5050"));
        assertEquals(-1, AuthViewModel.parsePort("0"));
        assertEquals(-1, AuthViewModel.parsePort("70000"));
        assertEquals(-1, AuthViewModel.parsePort("abc"));
        assertEquals(-1, AuthViewModel.parsePort(null));
    }

    @Test void connectionTextFollowsState() {
        assertEquals("Can't reach server", vm.connectionTextBinding().get());
        session.connectionStateProperty().set(ConnectionState.CONNECTING);
        assertEquals("Connecting...", vm.connectionTextBinding().get());
        session.connectionStateProperty().set(ConnectionState.CONNECTED);
        assertEquals("Connected", vm.connectionTextBinding().get());
    }
}
