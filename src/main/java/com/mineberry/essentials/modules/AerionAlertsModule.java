package com.mineberry.essentials.modules;

import com.mineberry.essentials.AerionNotifier;
import com.mineberry.essentials.MineBerryModule;

/**
 * Aerion Alerts: Aerion shows short notices at the top of your screen when something important happens
 * (an update is out, you arrived at a waypoint). Switch it off here, or tell Aerion "turn off alerts".
 * The drawing lives in {@link AerionNotifier}; this module is its ON/OFF switch in the Control Center.
 */
public class AerionAlertsModule extends MineBerryModule {

    public AerionAlertsModule() {
        super(AerionNotifier.MODULE_ID, "Aerion Alerts", "Aerion shows important notices at the top of your screen");
    }

    @Override
    public void init() {
        AerionNotifier.init();
    }
}
