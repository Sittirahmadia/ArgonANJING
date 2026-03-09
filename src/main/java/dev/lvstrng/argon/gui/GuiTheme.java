package dev.lvstrng.argon.gui;

import java.awt.*;

/**
 * Centralized theme constants for the Argon ClickGUI.
 * All visual constants live here so tweaking the look only requires
 * changing values in one place.
 */
public final class GuiTheme {

    private GuiTheme() {}

    // ── Panel / Window geometry ───────────────────────────────────────────
    /** Width of each category window (module list). */
    public static final int WINDOW_W     = 205;
    /** Height of the category header row and each module row. */
    public static final int ROW_H        = 30;
    /** Corner radius used for windows, buttons, chips. */
    public static final int RADIUS       = 6;

    // ── Colours ───────────────────────────────────────────────────────────
    /** Primary window background. */
    public static final Color BG         = new Color(12, 12, 16, 215);
    /** Slightly lighter, used for the header. */
    public static final Color BG_HEADER  = new Color(18, 18, 24, 230);
    /** Module row background. */
    public static final Color BG_ROW     = new Color(10, 10, 13, 200);
    /** Settings row background (darker indent). */
    public static final Color BG_SETTING = new Color(8,  8, 10, 195);
    /** Drop-shadow tint. */
    public static final Color SHADOW     = new Color(0, 0, 0, 80);
    /** Subtle separator / divider. */
    public static final Color DIVIDER    = new Color(255, 255, 255, 9);
    /** Text colour for labels and settings. */
    public static final Color TEXT_DIM   = new Color(175, 175, 185);
    /** Placeholder / hint text. */
    public static final Color TEXT_HINT  = new Color(70, 70, 82);

    // ── Search bar ────────────────────────────────────────────────────────
    public static final int   SEARCH_W   = 230;
    public static final int   SEARCH_H   = 22;
    /** Gap between search bar and save button. */
    public static final int   SEARCH_GAP = 6;

    // ── Save button ───────────────────────────────────────────────────────
    public static final int   SAVE_W     = 56;
    public static final int   SAVE_H     = 22;

    // ── Toast notification ────────────────────────────────────────────────
    public static final long  TOAST_MS   = 1800;

    // ── Glow / shadow passes ──────────────────────────────────────────────
    /** Number of gradient samples for rounded quads. */
    public static final int   SAMPLES    = 12;

    // ── Watermark (bottom-left) ───────────────────────────────────────────
    public static final String CLIENT_NAME    = "Argon";
    public static final Color  WATERMARK_TEXT = new Color(200, 200, 210, 160);

    // ── Per-category accent hues (HSB-derived, layered over main accent) ──
    /**
     * Returns a per-category accent colour offset so categories can each
     * carry a slightly different hue while still respecting the user's
     * global colour setting.
     */
    public static int categoryColorOffset(dev.lvstrng.argon.module.Category cat) {
        return switch (cat) {
            case COMBAT -> 0;
            case MISC   -> 3;
            case RENDER -> 6;
            case CLIENT -> 9;
        };
    }
}
