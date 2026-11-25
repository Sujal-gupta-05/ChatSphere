package com.chatsphere.client.view.workspace;

import com.chatsphere.common.model.DeliveryStatus;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Message status ticks (own private messages only).
 * PENDING: Clock (Sending)
 * SENT: Single check (Sent)
 * DELIVERED: Double check (Delivered)
 * READ: Double check in -cs-tick-read blue (Read)
 * Owned by Person 4.
 */
public class StatusTick extends StackPane {
    private static final String SVG_SINGLE_CHECK = "M2 8l4 4L14 3";
    private static final String SVG_DOUBLE_CHECK = "M1 8l4 4L13 3 M6 8l4 4L18 3";

    private final ObjectProperty<DeliveryStatus> status = new SimpleObjectProperty<>(DeliveryStatus.SENT);
    private final SVGPath checkPath = new SVGPath();
    private final FontIcon clockIcon = new FontIcon("fth-clock");

    public StatusTick() {
        checkPath.setStrokeWidth(1.6);
        checkPath.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        checkPath.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        checkPath.setFill(Color.TRANSPARENT);

        clockIcon.setIconSize(12);
        clockIcon.setStyle("-fx-icon-color: -cs-text-tertiary;");

        getChildren().addAll(clockIcon, checkPath);
        updateVisuals(status.get());

        status.addListener((obs, oldVal, newVal) -> updateVisuals(newVal));
    }

    public ObjectProperty<DeliveryStatus> statusProperty() { return status; }
    public void setStatus(DeliveryStatus s) { status.set(s); }

    private void updateVisuals(DeliveryStatus s) {
        if (s == null) s = DeliveryStatus.SENT;
        switch (s) {
            case PENDING -> {
                clockIcon.setVisible(true);
                clockIcon.setManaged(true);
                checkPath.setVisible(false);
                checkPath.setManaged(false);
                setAccessibleText("Sending");
            }
            case SENT -> {
                clockIcon.setVisible(false);
                clockIcon.setManaged(false);
                checkPath.setVisible(true);
                checkPath.setManaged(true);
                checkPath.setContent(SVG_SINGLE_CHECK);
                checkPath.setStyle("-fx-stroke: -cs-text-tertiary;");
                setAccessibleText("Sent");
            }
            case DELIVERED -> {
                clockIcon.setVisible(false);
                clockIcon.setManaged(false);
                checkPath.setVisible(true);
                checkPath.setManaged(true);
                checkPath.setContent(SVG_DOUBLE_CHECK);
                checkPath.setStyle("-fx-stroke: -cs-text-tertiary;");
                setAccessibleText("Delivered");
            }
            case READ -> {
                clockIcon.setVisible(false);
                clockIcon.setManaged(false);
                checkPath.setVisible(true);
                checkPath.setManaged(true);
                checkPath.setContent(SVG_DOUBLE_CHECK);
                checkPath.setStyle("-fx-stroke: -cs-tick-read;");
                setAccessibleText("Read");
            }
            case FAILED -> {
                clockIcon.setVisible(false);
                clockIcon.setManaged(false);
                checkPath.setVisible(false);
                checkPath.setManaged(false);
                setAccessibleText("Failed");
            }
        }
    }
}
