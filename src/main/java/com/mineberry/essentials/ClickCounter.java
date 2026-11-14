package com.mineberry.essentials;

import java.util.ArrayDeque;

/** Counts presses of one button and reports how many happened in the last second (CPS). */
final class ClickCounter {

    private final ArrayDeque<Long> presses = new ArrayDeque<>();
    private boolean down;

    /** Call once per frame with the button's current state. A press is counted when it goes from up to down. */
    void sample(boolean nowDown, long nowMs) {
        if (nowDown && !down) presses.addLast(nowMs);
        down = nowDown;
        prune(nowMs);
    }

    int cps(long nowMs) {
        prune(nowMs);
        return presses.size();
    }

    private void prune(long nowMs) {
        while (!presses.isEmpty() && nowMs - presses.peekFirst() >= 1000) presses.pollFirst();
    }
}
