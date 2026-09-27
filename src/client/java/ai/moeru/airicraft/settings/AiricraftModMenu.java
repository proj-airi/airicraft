package ai.moeru.airicraft.settings;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Loaded only through Mod Menu's optional entrypoint. */
public final class AiricraftModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AiricraftSettingsScreen::create;
	}
}
