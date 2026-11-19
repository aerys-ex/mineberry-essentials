package com.mineberry.essentials;

/**
 * Base class for every MineBerry feature. To add a new feature in the future:
 * extend this class, then register it in MineBerryEssentials#onInitializeClient.
 * It automatically appears in the panel with its own ON/OFF switch.
 */
public abstract class MineBerryModule {

    private final String id;
    private final String name;
    private final String description;

    protected MineBerryModule(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }

    /** Always false until the player has agreed to the Terms (see Terms), so no feature does anything before that. */
    public boolean enabled() { return Terms.accepted() && Config.isEnabled(id); }

    public void toggle() {
        Config.setEnabled(id, !enabled());
        onToggled(enabled());
    }

    /** Called when the player flips the switch in the panel. */
    protected void onToggled(boolean nowEnabled) {}

    /** Register events / keybinds here. Always runs; check enabled() inside handlers. */
    public abstract void init();
}
