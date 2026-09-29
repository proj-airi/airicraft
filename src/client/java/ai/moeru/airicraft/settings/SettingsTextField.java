package ai.moeru.airicraft.settings;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Use the event's modifier snapshot: GLFW's polled key state can already have changed. */
class SettingsTextField extends TextFieldWidget {
	private boolean editingAllowed = true;
	SettingsTextField(TextRenderer renderer, int x, int y, int width, int height, Text label) {
		super(renderer, x, y, width, height, label);
	}

	@Override public void setEditable(boolean editable) {
		super.setEditable(editable);
		editingAllowed = editable;
	}

	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		int shortcut = MinecraftClient.IS_SYSTEM_MAC ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL;
		if (isFocused() && isNarratable() && (modifiers & shortcut) != 0
			&& (modifiers & (GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SHIFT)) == 0) {
			var keyboard = MinecraftClient.getInstance().keyboard;
			switch (key) {
				case GLFW.GLFW_KEY_A -> { setCursorToEnd(false); setSelectionEnd(0); return true; }
				case GLFW.GLFW_KEY_V -> { if (editingAllowed) write(keyboard.getClipboard()); return true; }
				case GLFW.GLFW_KEY_C -> { keyboard.setClipboard(getSelectedText()); return true; }
				case GLFW.GLFW_KEY_X -> {
					keyboard.setClipboard(getSelectedText());
					if (editingAllowed) write("");
					return true;
				}
				default -> { }
			}
		}
		return super.keyPressed(key, scan, modifiers);
	}
}
