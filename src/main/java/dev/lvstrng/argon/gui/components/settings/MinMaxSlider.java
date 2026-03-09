package dev.lvstrng.argon.gui.components.settings;

import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.Setting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public final class MinMaxSlider extends RenderableSetting {
    public boolean draggingMin, draggingMax;
    public double offsetMinX, offsetMaxX;
    public double lerpedOffsetMinX, lerpedOffsetMaxX;
    public MinMaxSetting setting;
    public Color currentColor1, currentColor2;
    private Color hoverColor;

    public MinMaxSlider(ModuleButton parent, Setting<?> setting, int offset) {
        super(parent, setting, offset);
        this.setting        = (MinMaxSetting) setting;
        lerpedOffsetMinX    = parentX();
        lerpedOffsetMaxX    = parentX() + parentWidth();
    }

    @Override
    public void onUpdate() {
        int idx = parent.settings.indexOf(this);
        Color c1 = Utils.getMainColor(0, idx).darker();
        Color c2 = Utils.getMainColor(0, idx + 1).darker();
        if (currentColor1 == null) currentColor1 = new Color(c1.getRed(), c1.getGreen(), c1.getBlue(), 0);
        else currentColor1 = new Color(c1.getRed(), c1.getGreen(), c1.getBlue(), currentColor1.getAlpha());
        if (currentColor2 == null) currentColor2 = new Color(c2.getRed(), c2.getGreen(), c2.getBlue(), 0);
        else currentColor2 = new Color(c2.getRed(), c2.getGreen(), c2.getBlue(), currentColor2.getAlpha());
        if (currentColor1.getAlpha() != 255) currentColor1 = ColorUtils.smoothAlphaTransition(0.05F, 255, currentColor1);
        if (currentColor2.getAlpha() != 255) currentColor2 = ColorUtils.smoothAlphaTransition(0.05F, 255, currentColor2);

        if (draggingMin) draggingMax = false;
        if (setting.getMinValue() > setting.getMaxValue()) setting.setMaxValue(setting.getMinValue());
        if (setting.getMaxValue() < setting.getMinValue()) setting.setMinValue(setting.getMaxValue() - setting.getIncrement());
        super.onUpdate();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int ry = parentY() + parentOffset() + offset;
        int rh = parentHeight();

        offsetMinX = (setting.getMinValue() - setting.getMin()) / (setting.getMax() - setting.getMin()) * parentWidth();
        offsetMaxX = (setting.getMaxValue() - setting.getMin()) / (setting.getMax() - setting.getMin()) * parentWidth();
        lerpedOffsetMinX = MathUtils.goodLerp((float)(0.5 * delta), lerpedOffsetMinX, offsetMinX);
        lerpedOffsetMaxX = MathUtils.goodLerp((float)(0.5 * delta), lerpedOffsetMaxX, offsetMaxX);

        int trackY1 = ry + 24;
        int trackY2 = ry + rh - 4;

        // Track background
        RenderUtils.renderRoundedQuad(context.getMatrices(),
                new Color(24, 24, 30, 200),
                parentX(), trackY1, parentX() + parentWidth(), trackY2,
                2, 2, 2, 2, 6);

        // Range fill
        context.fillGradient(
                (int)(parentX() + lerpedOffsetMinX), trackY1,
                (int)(parentX() + lerpedOffsetMaxX), trackY2,
                currentColor1.getRGB(), currentColor2.getRGB());

        // Label — indented
        String label = setting.getName() + ":  " +
                (setting.getMinValue() == setting.getMaxValue()
                        ? setting.getMinValue()
                        : setting.getMinValue() + " \u2013 " + setting.getMaxValue());
        TextRenderer.drawString(label, context,
                parentX() + 14, ry + 9, new Color(185, 185, 192).getRGB());

        // Hover
        if (!parent.parent.dragging) {
            int toA = isHovered(mouseX, mouseY) ? 18 : 0;
            if (hoverColor == null) hoverColor = new Color(255, 255, 255, toA);
            else hoverColor = new Color(255, 255, 255, hoverColor.getAlpha());
            if (hoverColor.getAlpha() != toA)
                hoverColor = ColorUtils.smoothAlphaTransition(0.05F, toA, hoverColor);
            context.fill(parentX(), ry, parentX() + parentWidth(), ry + rh, hoverColor.getRGB());
        }
    }

    @Override
    public void onGuiClose() { currentColor1 = null; currentColor2 = null; super.onGuiClose(); }

    private double getLength() { return lerpedOffsetMaxX - lerpedOffsetMinX; }

    private void slideMin(double mx) {
        double pct = MathHelper.clamp((mx - parentX()) / parentWidth(), 0, 1);
        setting.setMinValue(MathUtils.roundToDecimal(
                pct * (setting.getMax() - setting.getMin()) + setting.getMin(),
                setting.getIncrement()));
    }

    private void slideMax(double mx) {
        double pct = MathHelper.clamp((mx - parentX()) / parentWidth(), 0, 1);
        setting.setMaxValue(MathUtils.roundToDecimal(
                pct * (setting.getMax() - setting.getMin()) + setting.getMin(),
                setting.getIncrement()));
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mouseOver && keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            setting.setMaxValue(setting.getOriginalMaxValue());
            setting.setMinValue(setting.getOriginalMinValue());
        }
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return;
        if      (isHoveredMin(mouseX, mouseY) || isMouseInMin(mouseX, mouseY)) { draggingMin = true; slideMin(mouseX); }
        else if (isHoveredMax(mouseX, mouseY) || isMouseInMax(mouseX, mouseY)) { draggingMax = true; slideMax(mouseX); }
    }

    @Override
    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) { draggingMin = false; draggingMax = false; }
    }

    @Override
    public void mouseDragged(double mouseX, double mouseY, int button, double dX, double dY) {
        if (draggingMin) slideMin(mouseX);
        if (draggingMax && !draggingMin) slideMax(mouseX);
    }

    public boolean isHoveredMin(double mx, double my) { return isHovered(mx, my) && mx > parentX() + offsetMinX - 4 && mx < parentX() + offsetMinX + 4; }
    public boolean isHoveredMax(double mx, double my) { return isHovered(mx, my) && mx > parentX() + offsetMaxX - 4 && mx < parentX() + offsetMaxX + 4; }
    public boolean isMouseInMin(double mx, double my) { return isHovered(mx, my) && (mx <= parentX() + offsetMinX || mx < parentX() + offsetMinX + getLength() / 2); }
    public boolean isMouseInMax(double mx, double my) { return isHovered(mx, my) && (mx > parentX() + offsetMaxX  || mx > parentX() + offsetMinX + getLength() / 2); }
}
