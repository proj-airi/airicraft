package ai.moeru.airicraft.settings;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Profile operations only mutate the parent menu's unsaved draft. */
final class SettingsProfilesScreen extends Screen {
	private final SettingsProfiles profiles;
	private final Runnable back;
	private TextFieldWidget name;
	private String error = "";

	SettingsProfilesScreen(SettingsProfiles profiles, Runnable back) {
		super(Text.literal("Connection profiles"));
		this.profiles = profiles;
		this.back = back;
	}

	@Override protected void init() {
		int left = width / 2 - 150;
		int top = Math.max(40, height / 2 - 75);
		addDrawableChild(ButtonWidget.builder(Text.literal("Profile: " + profiles.active()), button -> {
			var names = profiles.names();
			profiles.select(names.get((names.indexOf(profiles.active()) + 1) % names.size()));
			error = "";
			clearAndInit();
		}).dimensions(left, top, 300, 20).build());
		name = addDrawableChild(new TextFieldWidget(textRenderer, left, top + 44, 300, 20, Text.literal("Profile name")));
		name.setMaxLength(64);
		name.setText(profiles.active());
		addDrawableChild(ButtonWidget.builder(Text.literal("Duplicate"), button -> edit(() -> profiles.duplicate(name.getText())))
			.dimensions(left, top + 70, 96, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), button -> edit(() -> profiles.rename(name.getText())))
			.dimensions(left + 102, top + 70, 96, 20).build());
		var delete = addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), button -> client.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) profiles.delete();
			client.setScreen(this);
		}, Text.literal("Delete “" + profiles.active() + "”?"), Text.literal("This removes the saved connection from the draft. Save the settings menu to apply it."))))
			.dimensions(left + 204, top + 70, 96, 20).build());
		delete.active = profiles.names().size() > 1;
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), button -> close())
			.dimensions(left, height - 28, 300, 20).build());
	}

	private void edit(Runnable operation) {
		try { operation.run(); error = ""; clearAndInit(); }
		catch (IllegalArgumentException exception) { error = exception.getMessage(); }
	}

	@Override public void close() { back.run(); }

	@Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int top = Math.max(40, height / 2 - 75);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, Text.literal("New name (Duplicate copies this connection)"), width / 2 - 150, top + 30, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(error.isEmpty() ? "Click the profile to switch. Changes apply on Save & reload." : error), width / 2, top + 100, error.isEmpty() ? 0xFFAAAAAA : 0xFFFF5555);
	}
}
