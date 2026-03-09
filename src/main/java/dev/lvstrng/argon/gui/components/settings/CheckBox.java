package dev.lvstrng.argon.gui.components.settings;

import dev.lvstrng.argon.gui.GuiTheme;
import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.Setting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

/**
 * Boolean toggle — renders as a smooth pill (iOS-style toggle switch).
 *
 *  ┌────────────────────────────────────────────────────┐
 *  │ ┊ Setting Name               [  ●  ] / [●      ] │
 *  └────────────────────────────────────────────────────┘
 */
public final class CheckBox extends RenderableSetting {

    private final BooleanSetting setting;
    private Color hoverColor;
    // Animated thumb X position
    private double thumbLerp = 0;

    public CheckBox(ModuleButton parent, Setting<?> setting, int offset) {
        super(parent, setting, offset);
        this.setting = (BooleanSetting) setting;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int idx = parent.settings.indexOf(this);
        int co  = GuiTheme.categoryColorOffset(parent.module.getCategory());
        int ry  = parentY() + parentOffset() + offset;
        int rh  = parentHeight();

        // Label
        TextRenderer.drawString(setting.getName(), context,
                parentX() + 14, ry + rh / 2 + 3, GuiTheme.TEXT_DIM.getRGB());

        // Toggle pill
        boolean val   = setting.getValue();
        int pillW = 28;
        int pillH = 14;
        int pillX = parentX() + parentWidth() - pillW - 8;
        int pillY = ry + (rh - pillH) / 2;
        int s     = 10;

        Color pillBg = val ? Utils.getMainColor(200, idx + co) : new Color(28, 28, 36, 210);
        RenderUtils.renderRoundedQuad(context.getMatrices(), pillBg,
                pillX, pillY, pillX + pillW, pillY + pillH, 7, 7, 7, 7, s);

        Color outline = val
                ? Utils.getMainColor(110, idx + co)
                : new Color(48, 48, 60, 180);
        RenderUtils.renderRoundedOutline(context, outline,
                pillX, pillY, pillX + pillW, pillY + pillH, 7, 7, 7, 7, 1.0, s);

        // Animated thumb
        double targetThumb = val ? pillW - 12.5 : 2.5;
        thumbLerp += (targetThumb - thumbLerp) * Math.min(0.25 * delta, 1.0);
        double tx = pillX + thumbLerp;
        double ty = pillY + 7.0;
        RenderUtils.renderCircle(context.getMatrices(), new Color(0, 0, 0, 100), tx + 4, ty, 4.8, 12);
        RenderUtils.renderCircle(context.getMatrices(), Color.WHITE,              tx + 4, ty, 4.0, 12);

        // Hover overlay
        renderHoverOverlay(context, mouseX, mouseY, ry, rh);
    }

    private void renderHoverOverlay(DrawContext context, int mouseX, int mouseY, int ry, int rh) {
        if (parent.parent.dragging) return;
        int toA = isHovered(mouseX, mouseY) ? 20 : 0;
        if (hoverColor == null) hoverColor = new Color(255, 255, 255, toA);
        else hoverColor = new Color(255, 255, 255, hoverColor.getAlpha());
        if (hoverColor.getAlpha() != toA)
            hoverColor = ColorUtils.smoothAlphaTransition(0.05f, toA, hoverColor);
        context.fill(parentX(), ry, parentX() + parentWidth(), ry + rh, hoverColor.getRGB());
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mouseOver && parent.extended && keyCode == GLFW.GLFW_KEY_BACKSPACE)
            setting.setValue(setting.getOriginalValue());
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (isHovered(mouseX, mouseY) && button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
            setting.toggle();
    }
}
