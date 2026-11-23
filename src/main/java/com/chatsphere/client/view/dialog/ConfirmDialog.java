package com.chatsphere.client.view.dialog;

import com.chatsphere.client.component.AppButton;
import com.chatsphere.client.component.DialogFrame;
import javafx.scene.control.Label;

/** Owned by Person 3. D4: heading, one sentence, Cancel and a danger button with a caller-supplied label. */
public class ConfirmDialog extends DialogFrame {
    public ConfirmDialog(String title, String message, String confirmLabel, Runnable onConfirm) {
        super(title, 400);

        Label text = new Label(message);
        text.getStyleClass().addAll("body", "text-secondary");
        text.setWrapText(true);
        body().getChildren().add(text);

        AppButton cancel = new AppButton("Cancel", AppButton.Variant.GHOST);
        cancel.setOnAction(e -> requestClose());
        AppButton confirm = new AppButton(confirmLabel, AppButton.Variant.DANGER);
        confirm.setOnAction(e -> {
            close();            // close first, then act (Sign out replaces the whole scene)
            onConfirm.run();
        });
        footer().getChildren().addAll(cancel, confirm);
        setInitialFocus(cancel);   // the safe choice has focus
    }

    /** Several confirmations may stack (for example over Settings). */
    @Override public boolean isSingleton() { return false; }
}
