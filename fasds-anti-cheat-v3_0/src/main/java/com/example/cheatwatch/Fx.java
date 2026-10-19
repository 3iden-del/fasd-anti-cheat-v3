package com.example.cheatwatch;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The potion effects that change what a player can legitimately do.
 * Effects are looked up by registry name once and cached, so there is no per-tick string work.
 */
final class Fx {
    private static final Map<MobEffect, Integer> CODES = new IdentityHashMap<>();

    /** Effect levels (amplifier + 1). 0 = not active. */
    int speed, slow, jump, resist;
    boolean levitation, slowFall;

    void read(AbstractClientPlayer p) {
        speed = slow = jump = resist = 0;
        levitation = slowFall = false;
        Collection<MobEffectInstance> list = p.getActiveEffects();
        if (list.isEmpty()) return;
        for (MobEffectInstance e : list) {
            int lvl = e.getAmplifier() + 1;
            switch (code(e.getEffect().value())) {
                case 1 -> speed = Math.max(speed, lvl);
                case 2 -> slow = Math.max(slow, lvl);
                case 3 -> jump = Math.max(jump, lvl);
                case 4 -> resist = Math.max(resist, lvl);
                case 5 -> levitation = true;
                case 6 -> slowFall = true;
                default -> { }
            }
        }
    }

    /** Combined walking-speed multiplier: Speed gives +20% per level, Slowness -15% per level. */
    double speedMul() {
        double m = (1.0 + 0.2 * speed) * Math.max(0.0, 1.0 - 0.15 * slow);
        return Math.max(0.1, m);
    }

    private static int code(MobEffect m) {
        Integer c = CODES.get(m);
        if (c == null) {
            Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(m);
            String n = id == null ? "" : id.getPath();
            c = switch (n) {
                case "speed" -> 1;
                case "slowness" -> 2;
                case "jump_boost" -> 3;
                case "resistance" -> 4;
                case "levitation" -> 5;
                case "slow_falling" -> 6;
                default -> 0;
            };
            CODES.put(m, c);
        }
        return c;
    }
}
