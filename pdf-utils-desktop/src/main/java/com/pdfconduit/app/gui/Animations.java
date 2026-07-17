package com.pdfconduit.app.gui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Small, reusable UI animations. All durations are short and use ease-out so the
 * interface feels responsive rather than sluggish.
 */
public final class Animations {

    private Animations() {}

    /** Fades a node in while sliding it up a few pixels — used when swapping views. */
    public static void fadeSlideIn(Node node) {
        node.setOpacity(0);
        node.setTranslateY(14);

        FadeTransition fade = new FadeTransition(Duration.millis(240), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);

        TranslateTransition slide = new TranslateTransition(Duration.millis(240), node);
        slide.setFromY(14);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, slide).play();
    }

    /** Simple fade-in from transparent. */
    public static void fadeIn(Node node) {
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(220), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);
        fade.play();
    }

    /** Fades a node in with a slight pop (scale 0.96 → 1.0) — for success / result reveals. */
    public static void popIn(Node node) {
        node.setOpacity(0);
        node.setScaleX(0.96);
        node.setScaleY(0.96);

        FadeTransition fade = new FadeTransition(Duration.millis(200), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(Interpolator.EASE_OUT);

        ScaleTransition scale = new ScaleTransition(Duration.millis(200), node);
        scale.setFromX(0.96);
        scale.setFromY(0.96);
        scale.setToX(1.0);
        scale.setToY(1.0);
        scale.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, scale).play();
    }

    /** Animates a node's scale toward {@code target}. */
    public static void scaleTo(Node node, double target) {
        ScaleTransition scale = new ScaleTransition(Duration.millis(120), node);
        scale.setToX(target);
        scale.setToY(target);
        scale.setInterpolator(Interpolator.EASE_BOTH);
        scale.play();
    }

    /** Grows a node slightly on hover and restores it on exit. */
    public static void installHoverScale(Node node, double hoverScale) {
        node.setOnMouseEntered(e -> scaleTo(node, hoverScale));
        node.setOnMouseExited(e -> scaleTo(node, 1.0));
    }
}
