package ai.moeru.airicraft.settings;

import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Masks both rendering and narration until the user explicitly reveals credentials. */
final class SecretEntry extends StringListEntry {
	SecretEntry(Text label, String value, BooleanSupplier reveal, Consumer<String> save) {
		super(label, value, Text.translatable("controls.reset"), () -> "", save);
		TextFieldWidget previous = textFieldWidget;
		textFieldWidget = new TextFieldWidget(MinecraftClient.getInstance().textRenderer,
			previous.getX(), previous.getY(), previous.getWidth(), previous.getHeight(), label) {
			@Override
			protected MutableText getNarrationMessage() {
				return reveal.getAsBoolean() ? super.getNarrationMessage() : label.copy().append(" — ••••");
			}
		};
		textFieldWidget.setDrawsBackground(previous.drawsBackground());
		textFieldWidget.setMaxLength(8192);
		textFieldWidget.setText(value);
		textFieldWidget.setRenderTextProvider((text, offset) -> OrderedText.styledForwardsVisitedString(
			reveal.getAsBoolean() ? text : "•".repeat(text.length()), Style.EMPTY));
		widgets = List.of(textFieldWidget, resetButton);
	}
}
