package ai.moeru.airicraft.settings;

import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Masks both rendering and narration until the user explicitly reveals credentials. */
final class SecretEntry extends StringListEntry {
	SecretEntry(Component label, String value, BooleanSupplier reveal, Consumer<String> save) {
		super(label, value, Component.translatable("controls.reset"), () -> "", save);
		EditBox previous = textFieldWidget;
		textFieldWidget = new SettingsTextField(Minecraft.getInstance().font,
			previous.getX(), previous.getY(), previous.getWidth(), previous.getHeight(), label) {
			@Override
			protected MutableComponent createNarrationMessage() {
				return reveal.getAsBoolean() ? super.createNarrationMessage() : label.copy().append(" — ••••");
			}
		};
		textFieldWidget.setBordered(previous.isBordered());
		textFieldWidget.setMaxLength(8192);
		textFieldWidget.setValue(value);
		textFieldWidget.setFormatter((text, offset) -> FormattedCharSequence.forward(
			reveal.getAsBoolean() ? text : "•".repeat(text.length()), Style.EMPTY));
		widgets = List.of(textFieldWidget, resetButton);
	}
}
