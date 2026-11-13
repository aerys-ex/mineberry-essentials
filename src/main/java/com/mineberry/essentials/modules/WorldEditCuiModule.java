package com.mineberry.essentials.modules;

import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.enginehub.worldeditcui.fabric.FabricModWorldEditCUI;

/**
 * Panel switch for the WorldEditCUI code that is now built directly into this mod
 * (see the org.enginehub.worldeditcui package, EPL-2.0). Off hides the selection rendering.
 * Configure colours/behaviour via Mod Menu (WorldEditCUI config screen) and Controls (WorldEditCUI keys).
 */
public class WorldEditCuiModule extends MineBerryModule {

    public WorldEditCuiModule() {
        super("worldeditcui", "WorldEdit CUI", "Shows your WorldEdit selection in the world");
    }

    @Override
    public void init() {
        // The CUI resets itself to visible on every join; re-apply the switch.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> apply());
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        apply();
    }

    private void apply() {
        FabricModWorldEditCUI cui = FabricModWorldEditCUI.getInstance();
        if (cui != null) cui.setVisible(enabled());
    }
}
