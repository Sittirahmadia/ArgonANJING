package dev.lvstrng.argon.gui.components.settings;

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.gui.components.ModuleButton;
import dev.lvstrng.argon.module.modules.client.ClickGUI;
import dev.lvstrng.argon.module.setting.Setting;
import dev.lvstrng.argon.module.setting.StringSetting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

public final class StringBox extends RenderableSetting {
	private final StringSetting setting;
	private Color hoverColor;

	public StringBox(ModuleButton parent, Setting<?> setting, int offset) {
		super(parent, setting, offset);
		this.setting = (StringSetting) setting;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		int ry = parentY() + parentOffset() + offset;
		int rh = parentHeight();

		String display = setting.getValue().length() <= 9 ? setting.getValue() : setting.getValue().substring(0, 9) + "...";
		TextRenderer.drawString(setting.getName() + ": " + display, context,
				parentX() + 8, ry + rh / 2 + 3, new Color(195, 195, 200).getRGB());

		if (!parent.parent.dragging) {
			int toA = isHovered(mouseX, mouseY) ? 16 : 0;
			if (hoverColor == null) hoverColor = new Color(255, 255, 255, toA);
			else hoverColor = new Color(255, 255, 255, hoverColor.getAlpha());
			if (hoverColor.getAlpha() != toA)
				hoverColor = ColorUtils.smoothAlphaTransition(0.05F, toA, hoverColor);
			context.fill(parentX(), ry, parentX() + parentWidth(), ry + rh, hoverColor.getRGB());
		}
	}

	@Override
	public void mouseClicked(double mouseX, double mouseY, int button) {
		if (!isHovered(mouseX, mouseY) || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
		mc.setScreen(new Screen(Text.empty()) {
			private String content = setting.getValue();

			@Override
			public void render(DrawContext context, int mouseX, int mouseY, float delta) {
				RenderUtils.unscaledProjection();
				mouseX *= (int) MinecraftClient.getInstance().getWindow().getScaleFactor();
				mouseY *= (int) MinecraftClient.getInstance().getWindow().getScaleFactor();
				super.render(context, mouseX, mouseY, delta);

				context.fill(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight(),
						new Color(0, 0, 0, ClickGUI.background.getValue() ? 180 : 0).getRGB());

				int cx = mc.getWindow().getWidth() / 2;
				int cy = mc.getWindow().getHeight() / 2;
				int contentW = Math.max(TextRenderer.getWidth(content), 600);
				int w = contentW + 30;
				int sx = cx - w / 2;

				RenderUtils.renderRoundedQuad(context.getMatrices(),
						new Color(14, 14, 17, ClickGUI.alphaWindow.getValueInt()),
						sx, cy - 30, sx + w, cy + 30, 5, 5, 5, 5, 20);
				// Title bar top line
				context.fillGradient(sx, cy - 30, sx + w, cy - 29,
						Utils.getMainColor(200, 1).getRGB(), Utils.getMainColor(200, 2).getRGB());

				int nameW = TextRenderer.getWidth(setting.getName());
				TextRenderer.drawString(setting.getName(), context, cx - nameW / 2, cy - 20, Color.WHITE.getRGB());

				context.fill(sx, cy, sx + w, cy + 30, new Color(8, 8, 11, 120).getRGB());
				RenderUtils.renderRoundedOutline(context, new Color(45, 45, 50, 255),
						sx + 10, cy + 5, sx + w - 10, cy + 25, 4, 4, 4, 4, 1, 20);
				TextRenderer.drawString(content, context, sx + 15, cy + 9, new Color(210, 210, 215).getRGB());
				RenderUtils.scaledProjection();
			}

			@Override
			public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
				if (keyCode == GLFW.GLFW_KEY_ESCAPE) { setting.setValue(content.strip()); mc.setScreen(Argon.INSTANCE.clickGui); }
				if (isPaste(keyCode)) content += mc.keyboard.getClipboard();
				if (isCopy(keyCode)) GLFW.glfwSetClipboardString(mc.getWindow().getHandle(), content);
				if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !content.isEmpty())
					content = content.substring(0, content.length() - 1);
				return super.keyPressed(keyCode, scanCode, modifiers);
			}

			@Override public void renderBackground(DrawContext ctx, int mx, int my, float d) {}
			@Override public boolean charTyped(char c, int m) { content += c; return super.charTyped(c, m); }
			@Override public boolean shouldCloseOnEsc() { return false; }
		});
	}
}
