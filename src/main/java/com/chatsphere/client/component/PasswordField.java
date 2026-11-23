package com.chatsphere.client.component;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * Owned by Person 3. FormField with a masked input and a show/hide toggle. Optional strength hint (the only
 * hint that updates per keystroke). The password is exposed as char[] so callers can wipe it.
 */
public class PasswordField extends FormField {
    private final javafx.scene.control.PasswordField masked;
    private final TextField plain;
    private final IconButton eye = new IconButton("fth-eye", "Show password");
    private boolean revealed;
    private boolean strengthHint;
    private String baseHelper = "";

    public PasswordField(String labelText, String prompt) {
        this(labelText, prompt, new javafx.scene.control.PasswordField(), new TextField(), new StackPane());
    }

    private PasswordField(String labelText, String prompt, javafx.scene.control.PasswordField masked,
                          TextField plain, StackPane stack) {
        super(labelText, prompt, stack, masked);
        this.masked = masked;
        this.plain = plain;

        plain.setPromptText(prompt == null ? "" : prompt);
        plain.setAccessibleText(labelText);
        plain.textProperty().bindBidirectional(masked.textProperty());
        plain.setVisible(false);
        plain.setManaged(false);
        masked.getStyleClass().add("has-trailing-icon");
        plain.getStyleClass().add("has-trailing-icon");

        eye.setFocusTraversable(false);   // Tab goes username, password, confirm, submit
        eye.setOnAction(e -> setRevealed(!revealed));
        StackPane.setAlignment(eye, Pos.CENTER_RIGHT);
        StackPane.setMargin(eye, new Insets(0, 2, 0, 0));
        stack.getChildren().addAll(masked, plain, eye);

        masked.textProperty().addListener((o, a, b) -> refreshStrength());
    }

    public char[] chars() { return getText() == null ? new char[0] : getText().toCharArray(); }

    /** Clears the text (used after a failed login). */
    @Override public void clear() { masked.clear(); }

    @Override
    public void requestInputFocus() { (revealed ? plain : masked).requestFocus(); }

    @Override
    public void setOnSubmit(Runnable action) {
        masked.setOnAction(e -> action.run());
        plain.setOnAction(e -> action.run());
    }

    /** Shows "Strength: weak / fair / strong" under the field while enabled. */
    public void setStrengthHint(boolean enabled) {
        strengthHint = enabled;
        refreshStrength();
    }

    @Override
    public void setHelper(String text) {
        baseHelper = text == null ? "" : text;
        refreshStrength();
    }

    private void refreshStrength() {
        String t = getText() == null ? "" : getText();
        super.setHelper(strengthHint && !t.isEmpty() ? "Strength: " + strengthOf(t) : baseHelper);
    }

    /** weak / fair / strong from length and character mix. Pure, no JavaFX. */
    public static String strengthOf(String password) {
        int score = 0;
        if (password.length() >= 8) score++;
        if (password.length() >= 12) score++;
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digitOrSymbol = password.chars().anyMatch(c -> !Character.isLetter(c));
        if (letter && digitOrSymbol) score++;
        return score <= 1 ? "weak" : score == 2 ? "fair" : "strong";
    }

    private void setRevealed(boolean show) {
        revealed = show;
        masked.setVisible(!show);
        masked.setManaged(!show);
        plain.setVisible(show);
        plain.setManaged(show);
        eye.setIcon(show ? "fth-eye-off" : "fth-eye");
        String tip = show ? "Hide password" : "Show password";
        eye.getTooltip().setText(tip);
        eye.setAccessibleText(tip);
        requestInputFocus();
    }
}
