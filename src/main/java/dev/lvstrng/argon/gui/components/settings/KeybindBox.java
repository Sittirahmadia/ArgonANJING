package dev.lvstrng.argon.gui.components.settings;

import dev.lvstrng.argon.gui.GuiTheme;
import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.setting.KeybindSetting;
import dev.lvstrng.argon.module.setting.Setting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

/**
 * Keybind setting — click to begin listening, press a key to bind.
 * Shows a key chip on the right; pulses accent when listening.
 */
public final class KeybindBox extends RenderableSetting {

    public KeybindSetting keybind;
    private Color hoverColor;

    public KeybindBox(ModuleButton parent, Setting<?> setting, int offset) {
        super(parent, setting, offset);
        this.keybind = (KeybindSetting) setting;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int idx       = parent.settings.indexOf(this);
        int co        = GuiTheme.categoryColorOffset(parent.module.getCategory());
        int ry        = parentY() + parentOffset() + offset;
        int rh        = parentHeight();
        boolean listen = keybind.isListening();
        Color accent   = listen
                ? Utils.getMainColor(255, idx + co)
                : Utils.getMainColor(165, idx + co);

        // Name label
        Color labelColor = listen ? accent : GuiTheme.TEXT_DIM;
        TextRenderer.drawString(setting.getName(), context,
                parentX() + 14, ry + rh / 2 + 3, labelColor.getRGB());

        // Key chip (right-aligned)
        CharSequence keySeq = listen
                ? EncryptedString.of("\u2014 listening...")
                : EncryptedString.of(KeyUtils.getKey(keybind.getKey()).toString());

        int chipW = TextRenderer.getWidth(keySeq) + 14;
        int chipH = 14;
        int chipX = parentX() + parentWidth() - chipW - 8;
        int chipY = ry + (rh - chipH) / 2;
        int s     = GuiTheme.SAMPLES;

        if (!listen) {
            RenderUtils.renderRoundedQuad(context.getMatrices(),
                    new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 22),
                    chipX, chipY, chipX + chipW, chipY + chipH, 3, 3, 3, 3, s);
            RenderUtils.renderRoundedOutline(context,
                    new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 95),
                    chipX, chipY, chipX + chipW, chipY + chipH, 3, 3, 3, 3, 1.0, s);
        }

        int keyLabelX = chipX + chipW / 2 - TextRenderer.getWidth(keySeq) / 2;
        TextRenderer.drawString(keySeq, context, keyLabelX, chipY + chipH - 4,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 215).getRGB());

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
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (!isHovered(mouseX, mouseY)) return;
        if (!keybind.isListening()) {
            keybind.toggleListening();
            keybind.setListening(true);
        } else {
            if (keybind.isModuleKey()) parent.module.setKey(button);
            keybind.setKey(button);
            keybind.setListening(false);
        }
    }

    @Override
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && mouseOver) {
            if (keybind.isModuleKey()) parent.module.setKey(keybind.getOriginalKey());
            keybind.setKey(keybind.getOriginalKey());
            keybind.setListening(false);
        } else if (keybind.isListening() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keybind.isModuleKey()) parent.module.setKey(keyCode);
            keybind.setKey(keyCode);
            keybind.setListening(false);
        }
    }
}
