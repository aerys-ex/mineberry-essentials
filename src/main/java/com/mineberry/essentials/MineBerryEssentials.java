package com.mineberry.essentials;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.modules.AdminChatModule;
import com.mineberry.essentials.modules.ChatExtrasModule;
import com.mineberry.essentials.modules.ClanChatModule;
import com.mineberry.essentials.modules.EvidenceModule;
import com.mineberry.essentials.modules.PartyChatModule;
import com.mineberry.essentials.modules.SpawnEscapeModule;
import com.mineberry.essentials.modules.TpDangerEscapeModule;
import com.mineberry.essentials.modules.WaypointsModule;
import com.mineberry.essentials.modules.WorldEditCuiModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MineBerryEssentials implements ClientModInitializer {
	public static final String MOD_ID = "mineberry-essentials";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Shown on the credits card in the panel. */
	public static final String CREDITS = "Agio12 (aerys_ex_) & Nice_EX (nice_ex.)";

	/** Key bind category (lang key: key.category.mineberry_essentials.main). */
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mineberry_essentials", "main"));

	private static final List<MineBerryModule> MODULES = new ArrayList<>();
	private static KeyMapping openPanelKey;
	private static KeyMapping toggleHudKey;
	private static boolean wasHurt;

	@Override
	public void onInitializeClient() {
		Config.load();

		openPanelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mineberry_essentials.open_panel",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_CONTROL,
				CATEGORY));
		toggleHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mineberry_essentials.toggle_hud",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_Y,
				CATEGORY));

		// To add a new feature: create a MineBerryModule subclass and register it here.
		register(new ClanChatModule());
		register(new AdminChatModule());
		register(new PartyChatModule());
		register(new ChatExtrasModule());
		register(new SpawnEscapeModule());
		register(new TpDangerEscapeModule());
		register(new EvidenceModule());
		register(new WorldEditCuiModule());
		register(new WaypointsModule());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean hurt = client.player != null && client.player.hurtTime > 0;
			if (hurt && !wasHurt) closePanelForDanger(client);
			wasHurt = hurt;
			while (toggleHudKey.consumeClick()) {
				Config.setHudHidden(!Config.hudHidden());
				if (client.player != null) {
					client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MineBerry] ")
							.withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE)
							.append(net.minecraft.network.chat.Component.literal(Config.hudHidden() ? "HUD hidden" : "HUD shown")
									.withStyle(Config.hudHidden() ? net.minecraft.ChatFormatting.GRAY : net.minecraft.ChatFormatting.GREEN)));
				}
			}
			while (openPanelKey.consumeClick()) {
				if (client.gui.screen() == null) {
					client.gui.setScreen(new PanelScreen(null));
				}
			}
		});

		LOGGER.info("MineBerry - Essentials loaded with {} modules", MODULES.size());
	}

	private static void register(MineBerryModule module) {
		MODULES.add(module);
		module.init();
	}

	public static List<MineBerryModule> modules() {
		return Collections.unmodifiableList(MODULES);
	}

	public static KeyMapping openPanelKey() {
		return openPanelKey;
	}

	public static KeyMapping toggleHudKey() {
		return toggleHudKey;
	}

	public static boolean hudHidden() {
		return Config.hudHidden();
	}

	/** Closes only our Control Center, and only when the player enabled the safety option. */
	public static void closePanelForDanger(Minecraft client) {
		if (Config.autoClosePanelOnDanger() && client.gui.screen() instanceof PanelScreen) {
			client.gui.setScreen(null);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
