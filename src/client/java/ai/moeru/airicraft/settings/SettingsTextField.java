package ai.moeru.airicraft.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Use the event's modifier snapshot: GLFW's polled key state can already have changed. */
class SettingsTextField extends EditBox {
	private boolean editingAllowed = true;
	SettingsTextField(Font font, int x, int y, int width, int height, Component label) {
		super(font, x, y, width, height, label);
	}

	@Override public void setEditable(boolean editable) {
		super.setEditable(editable);
		editingAllowed = editable;
	}

	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		int shortcut = Minecraft.ON_OSX ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL;
		if (isFocused() && isActive() && (modifiers & shortcut) != 0
			&& (modifiers & (GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SHIFT)) == 0) {
			var keyboardHandler = Minecraft.getInstance().keyboardHandler;
			switch (key) {
				case GLFW.GLFW_KEY_A -> { moveCursorToEnd(false); setHighlightPos(0); return true; }
				case GLFW.GLFW_KEY_V -> { if (editingAllowed) insertText(keyboardHandler.getClipboard()); return true; }
				case GLFW.GLFW_KEY_C -> { keyboardHandler.setClipboard(getHighlighted()); return true; }
				case GLFW.GLFW_KEY_X -> {
					keyboardHandler.setClipboard(getHighlighted());
					if (editingAllowed) insertText("");
					return true;
				}
				default -> { }
			}
		}
		return super.keyPressed(key, scan, modifiers);
	}
}
