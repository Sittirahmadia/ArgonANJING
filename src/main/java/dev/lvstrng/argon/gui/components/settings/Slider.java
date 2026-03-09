package dev.lvstrng.argon.gui.components.settings;

import dev.lvstrng.argon.gui.GuiTheme;
import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.module.setting.Setting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

/**
 * Number slider — shows label + value on top, gradient track + thumb below.
 *
 *  ┌────────────────────────────────────────────────────┐
 *  │ ┊ Setting Name                              3.50  │
 *  │   [██████████░░░░░░░░░░░░]                        │
 *  └────────────────────────────────────────────────────┘
 */
public final class Slider extends RenderableSetting {

    public boolean dragging;
    public double  offsetX;
    public double  lerpedOffsetX = 0;

    private final NumberSetting setting;
    public  Color  currentColor1, currentColor2;
    private Color  hoverColor;
    private Color  trackBg;

    public Slider(ModuleButton parent, Setting<?> setting, int offset) {
        super(parent, setting, offset);
        this.setting = (NumberSetting) setting;
    }

    @Override
    public void onUpdate() {
        int idx = parent.settings.indexOf(this);
        int co  = GuiTheme.categoryColorOffset(parent.module.getCategory());
        Color c1 = Utils.getMainColor(0, idx + co).darker();
        Color c2 = Utils.getMainColor(0, idx + co + 1).darker();

        if (currentColor1 == null) currentColor1 = new Color(c1.getRed(), c1.getGreen(), c1.getBlue(), 0);
        else currentColor1 = new Color(c1.getRed(), c1.getGreen(), c1.getBlue(), currentColor1.getAlpha());
        if (currentColor2 == null) currentColor2 = new Color(c2.getRed(), c2.getGreen(), c2.getBlue(), 0);
        else currentColor2 = new Color(c2.getRed(), c2.getGreen(), c2.getBlue(), currentColor2.getAlpha());

        if (currentColor1.getAlpha() != 255) currentColor1 = ColorUtils.smoothAlphaTransition(0.05f, 255, currentColor1);
        if (currentColor2.getAlpha() != 255) currentColor2 = ColorUtils.smoothAlphaTransition(0.05f, 255, currentColor2);
        super.onUpdate();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int ry = parentY() + parentOffset() + offset;
        int rh = parentHeight();

        offsetX       = (setting.getValue() - setting.getMin()) / (setting.getMax() - setting.getMin()) * parentWidth();
        lerpedOffsetX = MathUtils.goodLerp((float)(0.45 * delta), lerpedOffsetX, offsetX);

        // Label + current value
        String label = setting.getName() + ":  " + setting.getValue();
        TextRenderer.drawString(label, context,
                parentX() + 14, ry + 10, GuiTheme.TEXT_DIM.getRGB());

        int trackY1 = ry + 23;
        int trackY2 = ry + rh - 5;

        // Track background (pill shape)
        if (trackBg == null) trackBg = new Color(20, 20, 28, 190);
        RenderUtils.renderRoundedQuad(context.getMatrices(), trackBg,
                parentX(), trackY1, parentX() + parentWidth(), trackY2,
                2, 2, 2, 2, 8);

        // Filled portion (gradient, clamped)
        if (lerpedOffsetX > 1) {
            context.fillGradient(
                    parentX(), trackY1,
                    (int)(parentX() + lerpedOffsetX), trackY2,
                    currentColor1.getRGB(), currentColor2.getRGB());
        }

        // Hover overlay
        renderHoverOverlay(context, mouseX, mouseY, ry, rh);
    }

    private void renderHoverOverlay(DrawContext context, int mouseX, int mouseY, int ry, int rh) {
        if (parent.parent.dragging) return;
        int toA = isHovered(mouseX, mouseY) ? 18 : 0;
        if (hoverColor == null) hoverColor = new Color(255, 255, 255, toA);
        else hoverColor = new Color(255, 255, 255, hoverColor.getAlpha());
        if (hoverColor.getAlpha() != toA)
            hoverColor = ColorUtils.smoothAlphaTransition(0.05f, toA, hoverColor);
        context.fill(parentX(), ry, parentX() + parentWidth(), ry + rh, hoverColor.getRGB());
    }

    @Override
    public void onGuiClose() { currentColor1 = null; currentColor2 = null; super.onGuiClose(); }

    private void slide(double mouseX) {
        double pct = MathHelper.clamp((mouseX - parentX()) / parentWidth(), 0, 1);
        setting.setValue(MathUtils.roundToDecimal(
                pct * (setting.getMax() - setting.getMin()) + setting.getMin(),
                setting.getIncrement()));
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mouseOver && parent.extended && keyCode == GLFW.GLFW_KEY_BACKSPACE)
            setting.setValue(setting.getOriginalValue());
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (isHovered(mouseX, mouseY) && button == 0) { dragging = true; slide(mouseX); }
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) dragging = false;
    }

    @Override
    public void mouseDragged(double mouseX, double mouseY, int button, double dX, double dY) {
        if (dragging) slide(mouseX);
    }
}
