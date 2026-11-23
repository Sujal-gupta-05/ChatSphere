package com.chatsphere.client.view.dialog;

import com.chatsphere.client.AppContext;
import com.chatsphere.client.ErrorMessages;
import com.chatsphere.client.component.AppButton;
import com.chatsphere.client.component.Banner;
import com.chatsphere.client.component.DialogFrame;
import com.chatsphere.client.component.FormField;
import com.chatsphere.client.component.Severity;
import com.chatsphere.common.protocol.ErrorCode;
import com.chatsphere.common.protocol.Validation;

import java.util.function.Consumer;

/** Owned by Person 3. D1: create a room. Calls onCreated(name) after the server accepted it and the dialog closed. */
public class CreateRoomDialog extends DialogFrame {
    private final FormField name = new FormField("Room name", "general");
    private final Banner error = new Banner(Severity.ERROR, "");
    private final AppButton cancel = new AppButton("Cancel", AppButton.Variant.GHOST);
    private final AppButton create = new AppButton("Create");
    private final Consumer<String> onCreated;

    public CreateRoomDialog(Consumer<String> onCreated) {
        super("Create a room", 420);
        this.onCreated = onCreated;

        error.setVisible(false);
        error.setManaged(false);
        name.setHelper("3 to 30 characters");
        name.textProperty().addListener((o, a, b) -> name.clearError());
        name.setOnSubmit(this::submit);
        body().getChildren().addAll(error, name);

        cancel.setOnAction(e -> requestClose());
        create.setOnAction(e -> submit());
        footer().getChildren().addAll(cancel, create);
        setInitialFocus(name.input());
    }

    private void submit() {
        if (create.isPending()) return;
        error.setVisible(false);
        error.setManaged(false);

        String room = name.getText() == null ? "" : name.getText().trim();
        if (!Validation.isValidRoomName(room)) {
            name.setError(ErrorMessages.of(ErrorCode.INVALID_ROOM_NAME));
            name.requestInputFocus();
            return;
        }
        name.clearError();
        setBusy(true);

        AppContext ctx = AppContext.get();
        ctx.client().createRoom(room).whenComplete((v, t) -> {
            setBusy(false);
            if (t == null) {
                close();
                ctx.toast("Room created", Severity.SUCCESS);
                onCreated.accept(room);
                return;
            }
            ErrorCode code = ErrorMessages.codeOf(t);
            if (code == ErrorCode.ROOM_EXISTS || code == ErrorCode.INVALID_ROOM_NAME) {
                name.setError(ErrorMessages.of(t));
                name.requestInputFocus();
            } else {
                error.setText(ErrorMessages.of(t));
                error.setVisible(true);
                error.setManaged(true);
            }
        });
    }

    /** While the request runs: spinner on Create, Esc and Cancel do nothing, field is read-only. */
    private void setBusy(boolean busy) {
        create.setPending(busy);
        closableProperty().set(!busy);
        cancel.setDisable(busy);
        name.input().setEditable(!busy);
    }
}
