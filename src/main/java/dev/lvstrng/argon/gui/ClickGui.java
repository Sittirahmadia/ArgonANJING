package dev.lvstrng.argon.gui;

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.modules.client.ClickGUI;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static dev.lvstrng.argon.Argon.mc;

/**
 * Main ClickGUI screen — redesigned with centralized theme constants,
 * a cleaner toolbar, client watermark, and keyboard hints.
 */
public final class ClickGui extends Screen {

    // ── Windows (one per category) ────────────────────────────────────────
    public List<Window> windows = new ArrayList<>();

    // ── Background dim colour (animated) ─────────────────────────────────
    public Color currentColor;

    // ── Search state ──────────────────────────────────────────────────────
    public String  searchQuery   = "";
    public boolean searchFocused = false;

    // ── Toast state ───────────────────────────────────────────────────────
    private long saveNotifTime = 0;

    // ── Interactive colours (animated) ────────────────────────────────────
    private Color saveButtonColor = new Color(18, 18, 24, 200);

    // ── Hint fade-in ──────────────────────────────────────────────────────
    private float hintAlpha = 0f;

    // ─────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────

    public ClickGui() {
        super(Text.empty());
        int offsetX = 30;
        for (Category category : Category.values()) {
            windows.add(new Window(offsetX, 50, GuiTheme.WINDOW_W, GuiTheme.ROW_H, category, this));
            offsetX += GuiTheme.WINDOW_W + 14;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Rendering
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (mc.currentScreen != this) return;

        if (Argon.INSTANCE.previousScreen != null)
            Argon.INSTANCE.previousScreen.render(context, 0, 0, delta);

        // Animated background dim
        if (currentColor == null) currentColor = new Color(0, 0, 0, 0);
        else currentColor = new Color(0, 0, 0, currentColor.getAlpha());
        int targetAlpha = ClickGUI.background.getValue() ? 155 : 0;
        if (currentColor.getAlpha() != targetAlpha)
            currentColor = ColorUtils.smoothAlphaTransition(0.05f, targetAlpha, currentColor);
        if (currentColor.getAlpha() > 0)
            context.fill(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight(), currentColor.getRGB());

        // Switch to framebuffer-pixel space
        RenderUtils.unscaledProjection();
        int smx = (int)(mouseX * mc.getWindow().getScaleFactor());
        int smy = (int)(mouseY * mc.getWindow().getScaleFactor());
        super.render(context, smx, smy, delta);

        int fw = mc.getWindow().getFramebufferWidth();
        int fh = mc.getWindow().getFramebufferHeight();

        // Category windows
        for (Window w : windows) {
            w.render(context, smx, smy, delta);
            w.updatePosition(smx, smy, delta);
        }

        // Toolbar (search + save)
        renderToolbar(context, smx, smy, fw);

        // Watermark (bottom-left)
        renderWatermark(context, fh);

        // Keyboard hints (bottom-right)
        renderHints(context, fw, fh, delta);

        // Save toast (bottom-centre)
        renderToast(context, fw, fh);

        // Search result count (below toolbar)
        renderSearchCount(context, fw);

        RenderUtils.scaledProjection();
    }

    // ── Toolbar ───────────────────────────────────────────────────────────

    private void renderToolbar(DrawContext context, int mouseX, int mouseY, int fw) {
        int totalW  = GuiTheme.SEARCH_W + GuiTheme.SEARCH_GAP + GuiTheme.SAVE_W;
        int sx      = fw / 2 - totalW / 2;
        int sy      = 10;
        renderSearchBar(context, mouseX, mouseY, sx, sy);
        renderSaveButton(context, mouseX, mouseY, sx + GuiTheme.SEARCH_W + GuiTheme.SEARCH_GAP, sy);
    }

    private void renderSearchBar(DrawContext context, int mouseX, int mouseY, int sx, int sy) {
        int sw = GuiTheme.SEARCH_W;
        int sh = GuiTheme.SEARCH_H;
        int r  = GuiTheme.RADIUS;
        int s  = GuiTheme.SAMPLES;

        // Shadow
        RenderUtils.renderRoundedQuad(context.getMatrices(), new Color(0, 0, 0, 50),
                sx - 3, sy - 3, sx + sw + 3, sy + sh + 3, r + 1, r + 1, r + 1, r + 1, s);

        // Background
        RenderUtils.renderRoundedQuad(context.getMatrices(), GuiTheme.BG_HEADER,
                sx, sy, sx + sw, sy + sh, r, r, r, r, s);

        // Outline — glows when focused
        if (searchFocused)
            RenderUtils.renderRoundedOutline(context, Utils.getMainColor(195, 0),
                    sx, sy, sx + sw, sy + sh, r, r, r, r, 1.4, s);
        else
            RenderUtils.renderRoundedOutline(context, new Color(42, 42, 54, 120),
                    sx, sy, sx + sw, sy + sh, r, r, r, r, 1.0, s);

        // Search icon
        CharSequence icon = EncryptedString.of("\u2315 ");
        int iconColor = searchFocused
                ? Utils.getMainColor(210, 1).getRGB()
                : GuiTheme.TEXT_HINT.getRGB();
        TextRenderer.drawString(icon, context, sx + 8, sy + sh / 2 + 3, iconColor);

        // Text / placeholder
        int iconW   = TextRenderer.getWidth(icon);
        boolean cur = searchFocused && (System.currentTimeMillis() % 800 < 400);
        CharSequence display = (searchQuery.isEmpty() && !searchFocused)
                ? EncryptedString.of("Search modules...")
                : EncryptedString.of(searchQuery + (cur ? "|" : ""));
        int textColor = (searchQuery.isEmpty() && !searchFocused)
                ? GuiTheme.TEXT_HINT.getRGB()
                : Color.WHITE.getRGB();
        TextRenderer.drawString(display, context, sx + 8 + iconW, sy + sh / 2 + 3, textColor);

        // Clear (×) hint when query is non-empty
        if (!searchQuery.isEmpty()) {
            CharSequence x = EncryptedString.of("\u00D7");
            int xw = TextRenderer.getWidth(x);
            TextRenderer.drawString(x, context, sx + sw - xw - 7, sy + sh / 2 + 3,
                    new Color(110, 110, 125, 200).getRGB());
        }
    }

    private void renderSaveButton(DrawContext context, int mouseX, int mouseY, int bx, int sy) {
        int bw = GuiTheme.SAVE_W;
        int bh = GuiTheme.SAVE_H;
        int r  = GuiTheme.RADIUS;
        int s  = GuiTheme.SAMPLES;
        boolean hov = mouseX >= bx && mouseX <= bx + bw && mouseY >= sy && mouseY <= sy + bh;

        Color target = hov ? new Color(26, 26, 36, 240) : GuiTheme.BG_HEADER;
        saveButtonColor = ColorUtils.smoothColorTransition(0.14f, target, saveButtonColor);

        // Shadow
        RenderUtils.renderRoundedQuad(context.getMatrices(), new Color(0, 0, 0, 50),
                bx - 3, sy - 3, bx + bw + 3, sy + bh + 3, r + 1, r + 1, r + 1, r + 1, s);

        // Background
        RenderUtils.renderRoundedQuad(context.getMatrices(), saveButtonColor,
                bx, sy, bx + bw, sy + bh, r, r, r, r, s);

        // Outline
        RenderUtils.renderRoundedOutline(context,
                hov ? Utils.getMainColor(195, 5) : new Color(42, 42, 54, 120),
                bx, sy, bx + bw, sy + bh, r, r, r, r, 1.0, s);

        // Label
        CharSequence label = EncryptedString.of("\u2713  Save");
        int lw = TextRenderer.getWidth(label);
        int lc = hov ? Utils.getMainColor(240, 4).getRGB() : new Color(160, 160, 175).getRGB();
        TextRenderer.drawString(label, context, bx + bw / 2 - lw / 2, sy + bh / 2 + 3, lc);
    }

    // ── Watermark ─────────────────────────────────────────────────────────

    private void renderWatermark(DrawContext context, int fh) {
        CharSequence name = EncryptedString.of(GuiTheme.CLIENT_NAME);
        CharSequence ver  = EncryptedString.of(" \u00B7 client");
        int nw  = TextRenderer.getWidth(name);
        int vw  = TextRenderer.getWidth(ver);
        int px  = 14;
        int py  = fh - 20;

        RenderUtils.renderRoundedQuad(context.getMatrices(), new Color(10, 10, 14, 150),
                px - 8, py - 8, px + nw + vw + 8, py + 8, 4, 4, 4, 4, GuiTheme.SAMPLES);

        TextRenderer.drawString(name, context, px, py + 3, Utils.getMainColor(215, 0).getRGB());
        TextRenderer.drawString(ver,  context, px + nw, py + 3, GuiTheme.WATERMARK_TEXT.getRGB());
    }

    // ── Hints ─────────────────────────────────────────────────────────────

    private void renderHints(DrawContext context, int fw, int fh, float delta) {
        hintAlpha = Math.min(1f, hintAlpha + 0.018f * delta);
        if (hintAlpha < 0.01f) return;
        CharSequence hint = EncryptedString.of("Ctrl+S \u00B7 Save    Esc \u00B7 Close    RMB \u00B7 Settings");
        int tw  = TextRenderer.getWidth(hint);
        int col = new Color(60, 60, 72, (int)(165 * hintAlpha)).getRGB();
        TextRenderer.drawString(hint, context, fw - tw - 14, fh - 17, col);
    }

    // ── Toast ─────────────────────────────────────────────────────────────

    private void renderToast(DrawContext context, int fw, int fh) {
        long elapsed = System.currentTimeMillis() - saveNotifTime;
        if (saveNotifTime <= 0 || elapsed >= GuiTheme.TOAST_MS) return;

        float p = (float) elapsed / GuiTheme.TOAST_MS;
        int alpha = p < 0.12f ? (int)(p / 0.12f * 210)
                  : p > 0.72f ? (int)((1f - (p - 0.72f) / 0.28f) * 210)
                  : 210;

        CharSequence text = EncryptedString.of("\u2713  Config Saved");
        int tw = TextRenderer.getWidth(text) + 26;
        int th = 26;
        int nx = fw / 2 - tw / 2;
        int ny = fh - 50;

        RenderUtils.renderRoundedQuad(context.getMatrices(), new Color(12, 12, 16, Math.min(alpha, 210)),
                nx, ny, nx + tw, ny + th, 5, 5, 5, 5, GuiTheme.SAMPLES);
        context.fillGradient(nx + 5, ny, nx + tw - 5, ny + 2,
                Utils.getMainColor(alpha, 0).getRGB(), Utils.getMainColor(alpha, 4).getRGB());
        TextRenderer.drawString(text, context,
                nx + tw / 2 - TextRenderer.getWidth(text) / 2, ny + th / 2 + 3,
                new Color(195, 195, 208, alpha).getRGB());
    }

    // ── Search result count ───────────────────────────────────────────────

    private void renderSearchCount(DrawContext context, int fw) {
        if (searchQuery.isEmpty()) return;
        int total = windows.stream()
                .mapToInt(w -> (int) w.moduleButtons.stream()
                        .filter(mb -> mb.matchesSearch(searchQuery)).count())
                .sum();
        CharSequence label = EncryptedString.of(total + " result" + (total != 1 ? "s" : ""));
        int lw = TextRenderer.getWidth(label);
        TextRenderer.drawString(label, context,
                fw / 2 - lw / 2, 10 + GuiTheme.SEARCH_H + 5,
                new Color(82, 82, 96, 210).getRGB());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Config
    // ─────────────────────────────────────────────────────────────────────

    private void saveConfig() {
        try {
            Argon.INSTANCE.getProfileManager().saveProfile();
            saveNotifTime = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    public boolean isDraggingAlready() {
        for (Window w : windows) if (w.dragging) return true;
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override protected void setInitialFocus() { if (client != null) super.setInitialFocus(); }
    @Override public boolean shouldPause()      { return false; }

    @Override
    public void close() {
        saveConfig();
        Argon.INSTANCE.getModuleManager().getModule(ClickGUI.class).setEnabledStatus(false);
        onGuiClose();
    }

    public void onGuiClose() {
        mc.setScreenAndRender(Argon.INSTANCE.previousScreen);
        currentColor  = null;
        searchQuery   = "";
        searchFocused = false;
        hintAlpha     = 0f;
        for (Window w : windows) w.onGuiClose();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Input
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchFocused && chr >= 32 && chr != 127) { searchQuery += chr; return true; }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_S && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            saveConfig(); return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (searchFocused && !searchQuery.isEmpty()) { searchQuery  = ""; return true; }
            if (searchFocused)                          { searchFocused = false; return true; }
        }
        if (searchFocused && keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
            searchQuery = searchQuery.substring(0, searchQuery.length() - 1); return true;
        }
        for (Window w : windows) w.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double sx = mouseX * mc.getWindow().getScaleFactor();
        double sy = mouseY * mc.getWindow().getScaleFactor();

        int fw      = mc.getWindow().getFramebufferWidth();
        int totalW  = GuiTheme.SEARCH_W + GuiTheme.SEARCH_GAP + GuiTheme.SAVE_W;
        int searchX = fw / 2 - totalW / 2;
        int barY    = 10;
        int saveX   = searchX + GuiTheme.SEARCH_W + GuiTheme.SEARCH_GAP;

        if (sx >= searchX && sx <= searchX + GuiTheme.SEARCH_W
                && sy >= barY && sy <= barY + GuiTheme.SEARCH_H) {
            searchFocused = true; return true;
        }
        if (sx >= saveX && sx <= saveX + GuiTheme.SAVE_W
                && sy >= barY && sy <= barY + GuiTheme.SAVE_H) {
            saveConfig(); return true;
        }

        searchFocused = false;
        double mx = mouseX * mc.getWindow().getScaleFactor();
        double my = mouseY * mc.getWindow().getScaleFactor();
        for (Window w : windows) w.mouseClicked(mx, my, button);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dX, double dY) {
        mouseX *= mc.getWindow().getScaleFactor();
        mouseY *= mc.getWindow().getScaleFactor();
        for (Window w : windows) w.mouseDragged(mouseX, mouseY, button, dX, dY);
        return super.mouseDragged(mouseX, mouseY, button, dX, dY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmt, double vAmt) {
        mouseY *= mc.getWindow().getScaleFactor();
        for (Window w : windows) w.mouseScrolled(mouseX, mouseY, hAmt, vAmt);
        return super.mouseScrolled(mouseX, mouseY, hAmt, vAmt);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseX *= mc.getWindow().getScaleFactor();
        mouseY *= mc.getWindow().getScaleFactor();
        for (Window w : windows) w.mouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
