package com.example.cheatwatch;

/** Vanilla movement numbers and tiny simulations. No Minecraft classes here, so it is easy to test. */
final class Physics {
    private Physics() {}

    static final double GRAVITY = 0.08, DRAG = 0.98, BASE_JUMP = 0.42;
    /** blocks per tick */
    static final double SPRINT = 0.2806, SNEAK = 0.0647, USING = 0.0432;

    static final double PEAK0 = jumpPeak(BASE_JUMP);
    static final int ASC0 = ascendTicks(BASE_JUMP);
    static final int AIR0 = airTime(BASE_JUMP);

    /** Jump Boost adds 0.1 of upward speed per level. */
    static double jumpVelocity(double base, int jumpLevel) {
        return base + 0.1 * jumpLevel;
    }

    /** Highest point (blocks above take-off) of a jump with initial upward speed v. */
    static double jumpPeak(double v) {
        double y = 0, vy = v, max = 0;
        for (int i = 0; i < 4000 && (i == 0 || vy > 0); i++) {
            y += vy;
            if (y > max) max = y;
            vy = (vy - GRAVITY) * DRAG;
        }
        return max;
    }

    /** How many ticks the player keeps moving upward after jumping with speed v. */
    static int ascendTicks(double v) {
        double vy = v;
        int n = 0;
        while (vy > 0 && n < 4000) {
            n++;
            vy = (vy - GRAVITY) * DRAG;
        }
        return n;
    }

    /** Ticks until a jump with speed v comes back down to take-off height. */
    static int airTime(double v) {
        double y = 0, vy = v;
        for (int t = 1; t < 4000; t++) {
            y += vy;
            vy = (vy - GRAVITY) * DRAG;
            if (y <= 0) return t;
        }
        return 4000;
    }
}
