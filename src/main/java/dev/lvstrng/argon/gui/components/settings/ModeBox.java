package dev.lvstrng.argon.gui.components.settings;

import dev.lvstrng.argon.gui.GuiTheme;
import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.Setting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

/**
 * Mode cycle — left label, right accent chip with current mode name.
 * Left-click cycles to next mode; backspace resets.
 */
public final class ModeBox extends RenderableSetting {

    public final ModeSetting<?> setting;
    private Color hoverColor;

    public ModeBox(ModuleButton parent, Setting<?> setting, int offset) {
        super(parent, setting, offset);
        this.setting = (ModeSetting<?>) setting;
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

        // Mode chip (right-aligned)
        String modeName = setting.getMode().name();
        int chipW = TextRenderer.getWidth(modeName) + 16;
        int chipH = 16;
        int chipX = parentX() + parentWidth() - chipW - 8;
        int chipY = ry + (rh - chipH) / 2;
        Color accent = Utils.getMainColor(195, idx + co);

        // Chip fill (subtle tint)
        RenderUtils.renderRoundedQuad(context.getMatrices(),
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30),
                chipX, chipY, chipX + chipW, chipY + chipH, 4, 4, 4, 4, GuiTheme.SAMPLES);

        // Chip outline
        RenderUtils.renderRoundedOutline(context,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 115),
                chipX, chipY, chipX + chipW, chipY + chipH, 4, 4, 4, 4, 1.0, GuiTheme.SAMPLES);

        // Mode label inside chip
        int labelX = chipX + chipW / 2 - TextRenderer.getWidth(modeName) / 2;
        TextRenderer.drawString(modeName, context, labelX, chipY + chipH - 5,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 230).getRGB());

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
            setting.setModeIndex(setting.getOriginalValue());
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (isHovered(mouseX, mouseY) && button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
            setting.cycle();
    }
}
