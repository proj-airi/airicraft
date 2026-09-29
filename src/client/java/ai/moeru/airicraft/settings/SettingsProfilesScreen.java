package ai.moeru.airicraft.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Profile operations only mutate the parent menu's unsaved draft. */
final class SettingsProfilesScreen extends Screen {
	private final SettingsProfiles profiles;
	private final Runnable back;
	private EditBox name;
	private String error = "";

	SettingsProfilesScreen(SettingsProfiles profiles, Runnable back) {
		super(Component.literal("Connection profiles"));
		this.profiles = profiles;
		this.back = back;
	}

	@Override protected void init() {
		int left = width / 2 - 150;
		int top = Math.max(40, height / 2 - 75);
		addRenderableWidget(Button.builder(Component.literal("Profile: " + profiles.active()), button -> {
			var names = profiles.names();
			profiles.select(names.get((names.indexOf(profiles.active()) + 1) % names.size()));
			error = "";
			rebuildWidgets();
		}).bounds(left, top, 300, 20).build());
		name = addRenderableWidget(new EditBox(font, left, top + 44, 300, 20, Component.literal("Profile name")));
		name.setMaxLength(64);
		name.setValue(profiles.active());
		addRenderableWidget(Button.builder(Component.literal("Duplicate"), button -> edit(() -> profiles.duplicate(name.getValue())))
			.bounds(left, top + 70, 96, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Rename"), button -> edit(() -> profiles.rename(name.getValue())))
			.bounds(left + 102, top + 70, 96, 20).build());
		var delete = addRenderableWidget(Button.builder(Component.literal("Delete"), button -> minecraft.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) profiles.delete();
			minecraft.setScreen(this);
		}, Component.literal("Delete “" + profiles.active() + "”?"), Component.literal("This removes the saved connection from the draft. Save the settings menu to apply it."))))
			.bounds(left + 204, top + 70, 96, 20).build());
		delete.active = profiles.names().size() > 1;
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
			.bounds(left, height - 28, 300, 20).build());
	}

	private void edit(Runnable operation) {
		try { operation.run(); error = ""; rebuildWidgets(); }
		catch (IllegalArgumentException exception) { error = exception.getMessage(); }
	}

	@Override public void onClose() { back.run(); }

	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		int top = Math.max(40, height / 2 - 75);
		graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.drawString(font, Component.literal("New name (Duplicate copies this connection)"), width / 2 - 150, top + 30, 0xFFFFFFFF);
		graphics.drawCenteredString(font, Component.literal(error.isEmpty() ? "Click the profile to switch. Changes apply on Save & reload." : error), width / 2, top + 100, error.isEmpty() ? 0xFFAAAAAA : 0xFFFF5555);
	}
}
