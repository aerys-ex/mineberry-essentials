package com.mineberry.essentials;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.modules.AdminChatModule;
import com.mineberry.essentials.modules.AerionAlertsModule;
import com.mineberry.essentials.modules.AerionChatCommandModule;
import com.mineberry.essentials.modules.AerionMiniChatModule;
import com.mineberry.essentials.modules.AirdropModule;
import com.mineberry.essentials.modules.ChatExtrasModule;
import com.mineberry.essentials.modules.ChatSearchModule;
import com.mineberry.essentials.modules.ClanChatModule;
import com.mineberry.essentials.modules.ColorCodeChatModule;
import com.mineberry.essentials.modules.EvidenceModule;
import com.mineberry.essentials.modules.FreelookModule;
import com.mineberry.essentials.modules.PartyChatModule;
import com.mineberry.essentials.modules.PvpCommandLockModule;
import com.mineberry.essentials.modules.RadialMenuModule;
import com.mineberry.essentials.modules.QuickReplyModule;
import com.mineberry.essentials.modules.SafeLogoutModule;
import com.mineberry.essentials.modules.ScreenshotModule;
import com.mineberry.essentials.modules.SelfNametagModule;
import com.mineberry.essentials.modules.SendGuardModule;
import com.mineberry.essentials.modules.ServerStats;
import com.mineberry.essentials.modules.TournamentHudModule;
import com.mineberry.essentials.modules.TournamentModeModule;
import com.mineberry.essentials.modules.WaypointsModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
	public static final String CREDITS = "Agio12 (ex.aerys) & Nice_EX (nice_ex.)";

	/** Key bind category (lang key: key.category.mineberry_essentials.main). */
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mineberry_essentials", "main"));

	private static final List<MineBerryModule> MODULES = new ArrayList<>();
	private static KeyMapping openPanelKey;
	private static boolean wasHurt;

	@Override
	public void onInitializeClient() {
		Config.load();
		ClientHud.init();    // HUD + its keybind
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Config.flushNow());
		Terms.init();       // the first-launch agreement (must come before Changelog so it opens first)
		Changelog.init();   // the "What's New" popup after an update
		UpdateChecker.init();   // update check at launch: title screen popup + chat link

		openPanelKey = Terms.registerKey(new KeyMapping(
				"key.mineberry_essentials.open_panel",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_CONTROL,
				CATEGORY));

		// To add a new feature: create a MineBerryModule subclass and register it here.
		register(new AerionChatCommandModule());   // first: a chat mode (clan / admin / party) must not swallow !aerion
		register(new ClanChatModule());
		register(new AdminChatModule());
		register(new PartyChatModule());
		register(new ColorCodeChatModule());
		register(new ChatExtrasModule());
		register(new ChatSearchModule());
		register(new QuickReplyModule());
		register(new SafeLogoutModule());
		register(new PvpCommandLockModule());
		register(new TournamentModeModule());
		register(new SendGuardModule());
		register(new EvidenceModule());
		register(new WaypointsModule());
		register(new AirdropModule());
		register(new AerionMiniChatModule());
		register(new AerionAlertsModule());
		register(new ScreenshotModule());
		register(new RadialMenuModule());
		register(new TournamentHudModule());
		register(new FreelookModule());
		register(new SelfNametagModule());
		ServerStats.init();   // Aerion: "server stats" runs /gc

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Config.flush();
			boolean hurt = client.player != null && client.player.hurtTime > 0;
			if (hurt && !wasHurt) closePanelForDanger(client);
			wasHurt = hurt;
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
