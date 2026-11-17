package com.mineberry.essentials;

/**
 * Stacks the fixed status boxes on the right edge (clan badge, Tournament Mode, Spawn Escape, ...) one under the other,
 * below the HUD boxes that live in the top-right corner, so nothing is drawn on top of anything else.
 * ClientHud calls begin() first every frame (it draws before the other HUD elements); each box then asks take() for its y.
 */
public final class StatusStack {

    private static final int GAP = 5;

    /** First free y below the top-right HUD column, and below the top-left one (used by the abuse alert). */
    public static int rightTop = 6, leftTop = 6;
    private static int cursor = -1;

    private StatusStack() {}

    static void begin(int edge) {
        rightTop = edge;
        leftTop = edge;
        cursor = -1;
    }

    /** Reserves a box of the given height in the right-hand stack and returns the y it should be drawn at. */
    public static int take(int height) {
        int y = cursor < 0 ? rightTop : cursor;
        cursor = y + height + GAP;
        return y;
    }
}
