package dev.lvstrng.argon.gui.components.settings;

import dev.lvstrng.argon.gui.GuiTheme;
import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.setting.Setting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.awt.*;

/**
 * Base class for all expandable setting rows.
 *
 * Each row has:
 *  ┌─────────────────────────────────────────────────┐
 *  │ ┊ [label]               [control]               │
 *  └─────────────────────────────────────────────────┘
 *    ↑ subtle left indent line (9px from edge)
 */
public abstract class RenderableSetting {

    public MinecraftClient mc = MinecraftClient.getInstance();

    // ── Refs ──────────────────────────────────────────────────────────────
    public ModuleButton parent;
    public Setting<?>   setting;
    public int          offset;

    // ── State ─────────────────────────────────────────────────────────────
    public Color   currentColor;
    public boolean mouseOver;
    protected int x, y, width, height;

    // ─────────────────────────────────────────────────────────────────────

    public RenderableSetting(ModuleButton parent, Setting<?> setting, int offset) {
        this.parent  = parent;
        this.setting = setting;
        this.offset  = offset;
    }

    // ── Accessors into parent window ──────────────────────────────────────

    public int parentX()      { return parent.parent.getX();      }
    public int parentY()      { return parent.parent.getY();      }
    public int parentWidth()  { return parent.parent.getWidth();  }
    public int parentHeight() { return parent.parent.getHeight(); }
    public int parentOffset() { return parent.offset;             }

    // ─────────────────────────────────────────────────────────────────────
    //  Rendering
    // ─────────────────────────────────────────────────────────────────────

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        mouseOver = isHovered(mouseX, mouseY);
        x      = parentX();
        y      = parentY() + parentOffset() + offset;
        width  = parentX() + parentWidth();
        height = parentY() + parentOffset() + offset + parentHeight();

        // Row background
        context.fill(x, y, width, height, currentColor.getRGB());

        // Subtle left indent marker (slightly to the right of the window accent bar)
        context.fill(x + 9, y + 4, x + 10, height - 4,
                new Color(255, 255, 255, 10).getRGB());

        // Thin separator between setting rows
        context.fill(x + 12, height - 1, width - 8, height,
                GuiTheme.DIVIDER.getRGB());
    }

    /** Tooltip shown at the bottom of the screen when hovering this setting. */
    public void renderDescription(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!isHovered(mouseX, mouseY) || setting.getDescription() == null || parent.parent.dragging)
            return;

        CharSequence desc = setting.getDescription();
        int tw = TextRenderer.getWidth(desc);
        int fw = mc.getWindow().getFramebufferWidth();
        int fh = mc.getWindow().getFramebufferHeight();
        int tx = fw / 2 - tw / 2;
        int ty = fh - 38;
        int s  = GuiTheme.SAMPLES;
        int co = GuiTheme.categoryColorOffset(parent.module.getCategory());

        RenderUtils.renderRoundedQuad(context.getMatrices(), new Color(13, 13, 17, 225),
                tx - 10, ty - 14, tx + tw + 10, ty + 14, 5, 5, 5, 5, s);
        context.fillGradient(tx - 10, ty - 14, tx + tw + 10, ty - 13,
                Utils.getMainColor(140, co).getRGB(),
                Utils.getMainColor(140, co + 2).getRGB());
        TextRenderer.drawString(desc, context, tx, ty, GuiTheme.TEXT_DIM.getRGB());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    public void onUpdate() {
        if (currentColor == null) currentColor = new Color(10, 10, 13, 0);
        else currentColor = new Color(10, 10, 13, currentColor.getAlpha());
        if (currentColor.getAlpha() != 132)
            currentColor = ColorUtils.smoothAlphaTransition(0.05f, 132, currentColor);
    }

    public void onGuiClose()   { currentColor = null; }

    // ─────────────────────────────────────────────────────────────────────
    //  Input stubs (override as needed)
    // ─────────────────────────────────────────────────────────────────────

    public void keyPressed(int keyCode, int scanCode, int modifiers) {}
    public void mouseClicked(double mouseX, double mouseY, int button) {}
    public void mouseReleased(double mouseX, double mouseY, int button) {}
    public void mouseDragged(double mouseX, double mouseY, int button, double dX, double dY) {}

    // ─────────────────────────────────────────────────────────────────────
    //  Hit testing
    // ─────────────────────────────────────────────────────────────────────

    public boolean isHovered(double mx, double my) {
        return mx > parentX() && mx < parentX() + parentWidth()
                && my > offset + parentOffset() + parentY()
                && my < offset + parentOffset() + parentY() + parentHeight();
    }
}
