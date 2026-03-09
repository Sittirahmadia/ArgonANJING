package dev.lvstrng.argon.gui;

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.modules.client.ClickGUI;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.gui.DrawContext;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A draggable category window.
 *
 * Visual layout (one window):
 *
 *  ╔══════════════════════════════════╗  ← shadow glow
 *  ║ [icon]  CategoryName   [N] ▾    ║  ← header (accent gradient)
 *  ╟──────────────────────────────────╢
 *  ║ ▌ ModuleA                   ●   ║  ← enabled (accent bar + dot)
 *  ║   ModuleB              [KEY]    ║  ← disabled
 *  ║   ModuleC  ▸                   ║  ← has settings
 *  ╚══════════════════════════════════╝
 */
public final class Window {

    // ── Module buttons ────────────────────────────────────────────────────
    public ArrayList<ModuleButton> moduleButtons = new ArrayList<>();

    // ── Position & size ───────────────────────────────────────────────────
    public int x, y;
    private final int width, height;

    // ── State ─────────────────────────────────────────────────────────────
    public Color currentColor;
    private final Category category;
    public boolean dragging, extended;
    private int dragX, dragY;
    private int prevX, prevY;

    // ── Parent ────────────────────────────────────────────────────────────
    public ClickGui parent;

    // ─────────────────────────────────────────────────────────────────────
    //  Category icon helper
    // ─────────────────────────────────────────────────────────────────────

    private static String categoryIcon(Category cat) {
        String n = cat.name.toString().toUpperCase();
        if (n.contains("COMBAT")) return "\u2694";   // ⚔
        if (n.contains("MISC"))   return "\u2699";   // ⚙
        if (n.contains("RENDER")) return "\u25A6";   // ▦
        if (n.contains("CLIENT")) return "\u2605";   // ★
        if (n.contains("MOVE"))   return "\u27A4";   // ➤
        return "\u25CF";                              // ●
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────

    public Window(int x, int y, int width, int height, Category category, ClickGui parent) {
        this.x        = x;
        this.y        = y;
        this.width    = width;
        this.height   = height;
        this.category = category;
        this.parent   = parent;
        this.dragging = false;
        this.extended = true;
        this.prevX    = x;
        this.prevY    = y;

        int offset = height;
        for (Module m : new ArrayList<>(Argon.INSTANCE.getModuleManager().getModulesInCategory(category))) {
            moduleButtons.add(new ModuleButton(this, m, offset));
            offset += height;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Filtered button list (search support)
    // ─────────────────────────────────────────────────────────────────────

    private List<ModuleButton> getVisible() {
        String q = parent.searchQuery;
        if (q == null || q.isEmpty()) return moduleButtons;
        return moduleButtons.stream()
                .filter(mb -> mb.matchesSearch(q))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Rendering
    // ─────────────────────────────────────────────────────────────────────

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        List<ModuleButton> visible = getVisible();
        if (!parent.searchQuery.isEmpty() && visible.isEmpty()) return;

        int alpha = computeAlpha();
        int r     = ClickGUI.roundQuads.getValueInt();
        int co    = GuiTheme.categoryColorOffset(category);

        // Total height occupied by expanded modules
        int totalModuleH = 0;
        for (ModuleButton mb : visible) totalModuleH += (int) mb.animation.getValue();

        renderShadow(context, alpha, r, totalModuleH);
        renderModuleListBody(context, alpha, r, co, totalModuleH);
        renderHeader(context, alpha, r, co, visible);

        // Module buttons
        updateButtons(delta, visible);
        for (ModuleButton mb : visible)
            mb.render(context, mouseX, mouseY, delta);
    }

    /** Animated alpha → target window alpha from settings. */
    private int computeAlpha() {
        int target = ClickGUI.alphaWindow.getValueInt();
        if (currentColor == null) currentColor = new Color(14, 14, 18, 0);
        else currentColor = new Color(14, 14, 18, currentColor.getAlpha());
        if (currentColor.getAlpha() != target)
            currentColor = ColorUtils.smoothAlphaTransition(0.05f, target, currentColor);
        return currentColor.getAlpha();
    }

    /** Multi-layer drop shadow: outer diffuse glow + hard offset shadow. */
    private void renderShadow(DrawContext context, int alpha, int r, int totalModuleH) {
        int s = GuiTheme.SAMPLES;
        // Outer diffuse glow (larger, more transparent)
        RenderUtils.renderRoundedQuad(context.getMatrices(),
                new Color(0, 0, 0, Math.min(alpha / 4, 55)),
                prevX - 8, prevY - 8, prevX + width + 8, prevY + height + totalModuleH + 8,
                r + 3, r + 3, r + 3, r + 3, s);
        // Hard shadow (smaller, more opaque)
        RenderUtils.renderRoundedQuad(context.getMatrices(),
                new Color(0, 0, 0, Math.min(alpha / 2, 80)),
                prevX - 3, prevY + 4, prevX + width + 3, prevY + height + totalModuleH + 4,
                r + 1, r + 1, r + 1, r + 1, s);
    }

    /** Dark body below the header that contains all module rows. */
    private void renderModuleListBody(DrawContext context, int alpha, int r, int co, int totalModuleH) {
        if (totalModuleH <= 0) return;
        int s = GuiTheme.SAMPLES;

        // Main body
        RenderUtils.renderRoundedQuad(context.getMatrices(),
                new Color(10, 10, 13, Math.min(alpha, 215)),
                prevX, prevY + height,
                prevX + width, prevY + height + totalModuleH,
                0, 0, r, r, s);

        // Subtle right-edge shimmer (accent hue)
        context.fillGradient(
                prevX + width - 1, prevY + height,
                prevX + width,     prevY + height + totalModuleH,
                Utils.getMainColor(40, co).getRGB(),
                Utils.getMainColor(40, co + 2).getRGB());

        // Bottom outline
        RenderUtils.renderRoundedOutline(context, new Color(30, 30, 38, 90),
                prevX, prevY + height, prevX + width, prevY + height + totalModuleH,
                0, 0, r, r, 1.0, s);
    }

    /** Gradient header with icon, category name, and module count badge. */
    private void renderHeader(DrawContext context, int alpha, int r, int co, List<ModuleButton> visible) {
        int s           = GuiTheme.SAMPLES;
        int hasModules  = visible.isEmpty() ? 0 : 1;
        int btmR        = (hasModules > 0 && extended) ? 0 : r;

        // Header background (slightly lighter)
        RenderUtils.renderRoundedQuad(context.getMatrices(),
                new Color(17, 17, 22, Math.min(alpha + 20, 255)),
                prevX, prevY, prevX + width, prevY + height,
                r, r, btmR, btmR, s);

        // Accent gradient overlay on header (subtle top highlight)
        Color ac1 = Utils.getMainColor(Math.min(alpha / 3, 40), co);
        Color ac2 = Utils.getMainColor(Math.min(alpha / 3, 40), co + 2);
        context.fillGradient(prevX, prevY, prevX + width, prevY + height,
                ac1.getRGB(), ac2.getRGB());

        // Accent bottom border line
        context.fillGradient(
                prevX + r,         prevY + height - 2,
                prevX + width - r, prevY + height,
                Utils.getMainColor(Math.min(alpha + 60, 255), co).getRGB(),
                Utils.getMainColor(Math.min(alpha + 60, 255), co + 3).getRGB());

        // Header outline
        RenderUtils.renderRoundedOutline(context, new Color(35, 35, 45, 100),
                prevX, prevY, prevX + width, prevY + height, r, r, btmR, btmR, 1.0, s);

        // ── Icon + Category label ──────────────────────────────────────────
        String icon    = categoryIcon(category);
        CharSequence iconSeq  = EncryptedString.of(icon + " ");
        CharSequence labelSeq;

        if (!parent.searchQuery.isEmpty())
            labelSeq = EncryptedString.of(category.name + "  \u00B7  " + visible.size());
        else
            labelSeq = category.name;

        int iconW  = TextRenderer.getWidth(iconSeq);
        int labelW = TextRenderer.getWidth(labelSeq);
        int totalTW = iconW + labelW;
        int textX  = prevX + width / 2 - totalTW / 2;
        int textY  = prevY + height / 2 + 3;

        TextRenderer.drawString(iconSeq,  context, textX,         textY,
                Utils.getMainColor(Math.min(alpha + 100, 255), co + 1).getRGB());
        TextRenderer.drawString(labelSeq, context, textX + iconW, textY, Color.WHITE.getRGB());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Input
    // ─────────────────────────────────────────────────────────────────────

    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        for (ModuleButton mb : moduleButtons) mb.keyPressed(keyCode, scanCode, modifiers);
    }

    public void onGuiClose() {
        currentColor = null;
        dragging     = false;
        for (ModuleButton mb : moduleButtons) mb.onGuiClose();
    }

    public boolean isDraggingAlready() {
        for (Window w : parent.windows) if (w.dragging) return true;
        return false;
    }

    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (isHovered(mouseX, mouseY) && button == 0 && !isDraggingAlready()) {
            dragging = true;
            dragX    = (int)(mouseX - x);
            dragY    = (int)(mouseY - y);
        }
        if (extended) {
            List<ModuleButton> visible = getVisible();
            for (ModuleButton mb : visible) mb.mouseClicked(mouseX, mouseY, button);
        }
    }

    public void mouseDragged(double mouseX, double mouseY, int button, double dX, double dY) {
        if (extended) {
            List<ModuleButton> visible = getVisible();
            for (ModuleButton mb : visible) mb.mouseDragged(mouseX, mouseY, button, dX, dY);
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) dragging = false;
        for (ModuleButton mb : moduleButtons) mb.mouseReleased(mouseX, mouseY, button);
    }

    public void mouseScrolled(double mouseX, double mouseY, double h, double v) {
        prevX = x;
        prevY = (int)(y + v * 20);
        setY((int)(y + v * 20));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Layout helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Recalculate vertical offsets and animate each visible button. */
    public void updateButtons(float delta, List<ModuleButton> visible) {
        int offset = height;
        for (ModuleButton mb : visible) {
            mb.animation.animate(0.5 * delta,
                    mb.extended ? height * (mb.settings.size() + 1) : height);
            mb.offset = offset;
            offset   += (int) mb.animation.getValue();
        }
    }

    public void updatePosition(double mouseX, double mouseY, float delta) {
        prevX = x;
        prevY = y;
        if (dragging) {
            x = (int) MathUtils.goodLerp(0.3f * delta, x, mouseX - dragX);
            y = (int) MathUtils.goodLerp(0.3f * delta, y, mouseY - dragY);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Accessors
    // ─────────────────────────────────────────────────────────────────────

    public int     getX()      { return prevX;  }
    public int     getY()      { return prevY;  }
    public void    setX(int x) { this.x = x;   }
    public void    setY(int y) { this.y = y;   }
    public int     getWidth()  { return width;  }
    public int     getHeight() { return height; }

    public boolean isHovered(double mx, double my) {
        return mx > x && mx < x + width && my > y && my < y + height;
    }
}
