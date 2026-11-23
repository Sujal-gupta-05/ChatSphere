package com.chatsphere.client.component;

import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Owned by Person 3. Label + input + helper line + error line. The error line shows an icon and the message
 * (never colour alone) and replaces the helper while present.
 */
public class FormField extends VBox {
    private final Label label = new Label();
    private final Label helper = new Label();
    private final Label errorText = new Label();
    private final HBox errorRow = new HBox(6);
    private final TextInputControl focusTarget;
    private final StringProperty error = new SimpleStringProperty("");
    private String helperText = "";

    public FormField(String labelText, String prompt) {
        this(labelText, prompt, new TextField());
    }

    private FormField(String labelText, String prompt, TextInputControl field) {
        this(labelText, prompt, field, field);
    }

    /** For subclasses with a composite input: inputNode is what is shown, focusTarget receives focus and errors. */
    protected FormField(String labelText, String prompt, Node inputNode, TextInputControl focusTarget) {
        super(4);
        this.focusTarget = focusTarget;
        getStyleClass().add("form-field");

        label.setText(labelText);
        label.getStyleClass().add("form-label");
        label.setLabelFor(focusTarget);
        focusTarget.setPromptText(prompt == null ? "" : prompt);
        focusTarget.setAccessibleText(labelText);

        helper.getStyleClass().addAll("form-helper", "small", "text-tertiary");
        helper.setWrapText(true);
        helper.setVisible(false);
        helper.setManaged(false);

        FontIcon icon = new FontIcon("fth-alert-circle");
        icon.setIconSize(14);
        errorText.getStyleClass().addAll("form-error", "small");
        errorText.setWrapText(true);
        errorRow.setAlignment(Pos.TOP_LEFT);
        errorRow.getStyleClass().add("form-error-row");
        errorRow.getChildren().addAll(icon, errorText);
        errorRow.setVisible(false);
        errorRow.setManaged(false);

        getChildren().addAll(label, inputNode, helper, errorRow);
    }

    public TextInputControl input() { return focusTarget; }
    public StringProperty textProperty() { return focusTarget.textProperty(); }
    public String getText() { return focusTarget.getText(); }
    public void setText(String text) { focusTarget.setText(text); }
    public void clear() { focusTarget.clear(); }

    /** Moves keyboard focus to the input. */
    public void requestInputFocus() { focusTarget.requestFocus(); }

    /** Enter in the field runs this. */
    public void setOnSubmit(Runnable action) {
        if (focusTarget instanceof TextField tf) tf.setOnAction(e -> action.run());
    }

    public void setHelper(String text) {
        helperText = text == null ? "" : text;
        refreshLines();
    }

    /** Shows the error (null or blank clears it). */
    public void setError(String message) {
        error.set(message == null ? "" : message);
        refreshLines();
    }

    public void clearError() { setError(null); }
    public boolean hasError() { return !error.get().isEmpty(); }
    public ReadOnlyStringProperty errorProperty() { return error; }

    private void refreshLines() {
        boolean hasErr = hasError();
        errorText.setText(error.get());
        errorRow.setVisible(hasErr);
        errorRow.setManaged(hasErr);
        boolean showHelper = !hasErr && !helperText.isEmpty();
        helper.setText(helperText);
        helper.setVisible(showHelper);
        helper.setManaged(showHelper);
        if (hasErr) { if (!getStyleClass().contains("has-error")) getStyleClass().add("has-error"); }
        else getStyleClass().remove("has-error");
        focusTarget.setAccessibleHelp(hasErr ? error.get() : helperText);
    }
}
