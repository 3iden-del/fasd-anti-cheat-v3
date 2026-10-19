package com.example.cheatwatch;

/**
 * Every cheat type the mod looks for.
 * thr   = how much "evidence" (buffer) must pile up before one violation level (VL) is added.
 * decay = how fast that evidence fades per tick if the player behaves normally.
 */
public enum Cheat {
    SPEED("Speed", "moving faster than vanilla allows (speed / slowness potions counted)", 2.0, 0.010),
    FLY("Fly / Hover / Glide / Spider", "staying up, gliding or climbing in the air with no support", 1.0, 0.0),
    AIR_JUMP("Air Jump", "jumping again while already in the air", 2.0, 0.020),
    HIGH_JUMP("High Jump", "jumping higher than vanilla allows (jump boost counted)", 1.0, 0.0),
    STEP("Step", "walking up blocks that are too tall", 1.0, 0.0),
    TOWER("Tower / Scaffold", "climbing straight up faster than pillar-jumping allows", 2.0, 0.010),
    JESUS("Jesus / Water Walk", "walking on top of water", 1.0, 0.0),
    PHASE("Phase / NoClip", "moving through solid blocks", 2.0, 0.020),
    NOSLOW("NoSlow", "moving too fast while eating / blocking / drawing a bow", 2.0, 0.010),
    OMNI_SPRINT("Omni-Sprint", "sprinting sideways or backwards", 2.0, 0.010),
    NOFALL("NoFall", "landing from a big fall without taking damage", 1.0, 0.0),
    VELOCITY("Velocity / Anti-KB", "taking little or no knockback", 2.0, 0.002),
    REACH("Reach", "hitting players from too far away", 2.0, 0.003),
    KILLAURA("KillAura / Aimbot", "hitting players they are not looking at, or snapping aim", 3.0, 0.010),
    AUTOBLOCK("Hit while using item", "swinging while eating / blocking / drawing a bow (impossible in vanilla)", 3.0, 0.010),
    AUTOCLICK("AutoClicker", "swinging at the max visible rate for a very long time", 1.0, 0.0);

    public static final int COUNT = values().length;

    public final String label;
    public final String desc;
    public final double thr;
    public final double decay;

    Cheat(String label, String desc, double thr, double decay) {
        this.label = label;
        this.desc = desc;
        this.thr = thr;
        this.decay = decay;
    }
}
