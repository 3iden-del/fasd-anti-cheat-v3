package com.example.cheatwatch;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * All the detection logic.
 *
 * Accuracy ideas used everywhere:
 *  - potion effects (speed, slowness, jump boost, levitation, slow falling, resistance) change the limits
 *  - every check needs evidence to pile up in a buffer before it adds a VL
 *  - risky exemptions (webs, water columns, boats, wind charges) are only looked up at the moment a check
 *    is about to flag, so honest players cost almost nothing
 *  - a player that stops dead mid-run (server lag) is ignored for a while, because the catch-up looks like speed
 *
 * Efficiency ideas:
 *  - no per-tick allocations on the hot path, no String.format unless a flag is really raised
 *  - players standing still on the ground take a fast exit
 *  - combat/reach work only runs when somebody is actually hurt, and only against players who just swung
 *  - VL / buffer decay is computed lazily from timestamps instead of looping over everybody every tick
 */
final class Checks {
    static final int WARMUP = 40;       // ignore a player for 2 s after they appear / teleport
    static final int MAX_PING = 400;    // above this we do not trust the data

    private final CheatWatchClient mod;
    private final Fx fx = new Fx();
    private final BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

    int now;
    double len = 1.0;          // leniency from the settings
    double reportVl = 3.0;     // VL needed before a cheat counts as flagged

    /** players that started a swing within the last 3 ticks (rebuilt every tick) */
    final ArrayList<AbstractClientPlayer> swingP = new ArrayList<>();
    final ArrayList<Track> swingT = new ArrayList<>();

    // places where someone was holding a wind charge / mace recently (they can launch other players)
    private static final int SP = 64;
    private final double[] spX = new double[SP], spY = new double[SP], spZ = new double[SP];
    private final int[] spT = new int[SP];
    private int spN, spI;

    Checks(CheatWatchClient mod) {
        this.mod = mod;
    }

    // =====================================================================================
    // violation bookkeeping
    // =====================================================================================

    /**
     * Adds evidence. When enough evidence has piled up, one VL is added.
     * weight 1.0 with thr 2.0 means "two separate bad moments in a short time = 1 VL".
     */
    void violate(Track t, Cheat c, double weight, String detail) {
        int i = c.ordinal();
        double b = Math.max(0.0, t.buf[i] - c.decay * (now - t.bufStamp[i])) + weight;
        t.bufStamp[i] = now;
        if (b >= c.thr) {
            t.buf[i] = 0;
            addVl(t, c, 1.0, detail);
        } else {
            t.buf[i] = b;
        }
    }

    private void addVl(Track t, Cheat c, double amount, String detail) {
        int i = c.ordinal();
        double cur = Math.min(Track.VL_CAP, t.vl(i, now) + amount);
        t.vl[i] = cur;
        t.vlStamp[i] = now;
        t.lastFlag[i] = now;
        t.count[i]++;
        t.detail[i] = detail;
        long wall = System.currentTimeMillis();
        if (t.firstWall[i] == 0) t.firstWall[i] = wall;
        t.lastWall[i] = wall;
        mod.markDirty();
        if (!t.confirmed[i] && cur >= reportVl) {
            t.confirmed[i] = true;
            t.vl[i] = cur;
            mod.onConfirmed(t, c);
        }
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    private static String itemPath(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    /** Real melee swings only (not bows, thrown items, spears with extra range...). */
    private static boolean meleeItem(ItemStack s) {
        if (s.isEmpty()) return true;
        String n = itemPath(s);
        return n.endsWith("_sword") || n.endsWith("_axe") || n.endsWith("_shovel") || n.endsWith("_hoe")
                || n.equals("mace");
    }

    /** Items that can legitimately launch / boost the holder: wind charge, mace, trident, firework, pearl, spears. */
    private static boolean holdingSpecial(AbstractClientPlayer p) {
        ItemStack a = p.getMainHandItem(), b = p.getOffhandItem();
        return special(a) || special(b);
    }

    private static boolean special(ItemStack s) {
        if (s.isEmpty()) return false;
        return s.is(Items.WIND_CHARGE) || s.is(Items.MACE) || s.is(Items.TRIDENT)
                || s.is(Items.FIREWORK_ROCKET) || s.is(Items.ENDER_PEARL) || itemPath(s).contains("spear");
    }

    private static boolean launcher(AbstractClientPlayer p) {
        ItemStack a = p.getMainHandItem(), b = p.getOffhandItem();
        return a.is(Items.WIND_CHARGE) || a.is(Items.MACE) || b.is(Items.WIND_CHARGE) || b.is(Items.MACE);
    }

    /** Using these does not slow you down, or you may legitimately swing while holding them. */
    private static boolean freeUse(AbstractClientPlayer p) {
        ItemStack s = p.getUseItem();
        if (s.isEmpty()) return true;
        String n = itemPath(s);
        return n.contains("spear") || n.equals("trident") || n.equals("brush") || n.equals("spyglass")
                || n.endsWith("_horn") || n.contains("bundle");
    }

    private void noteLauncher(double x, double y, double z) {
        spX[spI] = x; spY[spI] = y; spZ[spI] = z; spT[spI] = now;
        spI = (spI + 1) % SP;
        if (spN < SP) spN++;
    }

    private boolean nearLauncher(double x, double y, double z) {
        for (int i = 0; i < spN; i++) {
            if (now - spT[i] >= 60) continue;
            double dx = spX[i] - x, dy = spY[i] - y, dz = spZ[i] - z;
            if (dx * dx + dy * dy + dz * dz < 144.0) return true;
        }
        return false;
    }

    /** Webs, vines, ladders, scaffolding, water, honey, bubbles, powder snow around the body? */
    private boolean slowVolume(ClientLevel level, AbstractClientPlayer p) {
        AABB bb = p.getBoundingBox();
        int x0 = Mth.floor(bb.minX - 0.15), x1 = Mth.floor(bb.maxX + 0.15);
        int y0 = Mth.floor(bb.minY - 0.5), y1 = Mth.floor(bb.maxY + 0.2);
        int z0 = Mth.floor(bb.minZ - 0.15), z1 = Mth.floor(bb.maxZ + 0.15);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockState s = level.getBlockState(mp.set(x, y, z));
                    if (s.isAir()) continue;
                    if (!s.getFluidState().isEmpty() || s.is(BlockTags.CLIMBABLE) || s.is(Blocks.COBWEB)
                            || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SCAFFOLDING) || s.is(Blocks.HONEY_BLOCK)
                            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.BUBBLE_COLUMN)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Standing on a boat / minecart / shulker / mob that carries them? */
    private boolean entityBelow(ClientLevel level, AbstractClientPlayer p) {
        List<Entity> list = level.getEntities(p, p.getBoundingBox().inflate(0.3, 0.7, 0.3));
        for (Entity e : list) {
            if (e.isPickable() && !(e instanceof Player)) return true;
        }
        return false;
    }

    /** Checked only right before a flag is raised. */
    private boolean excused(ClientLevel level, AbstractClientPlayer p) {
        return slowVolume(level, p) || entityBelow(level, p);
    }

    private static Vec3 lookVec(AbstractClientPlayer p) {
        double yaw = Math.toRadians(p.getYHeadRot());
        double pitch = Math.toRadians(p.getXRot());
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    // =====================================================================================
    // swing bookkeeping + item-use checks (runs for every player, every tick)
    // =====================================================================================

    void swing(AbstractClientPlayer p, Track t) {
        boolean start = p.swinging && (!t.prevSwinging || p.swingTime < t.prevSwingTime);
        t.prevSwinging = p.swinging;
        t.prevSwingTime = p.swingTime;
        t.swingStart = start;

        boolean using = p.isUsingItem();
        if (using) {
            if (t.useStart < 0) t.useStart = now;
        } else {
            t.useStart = -1;
        }

        if (start) {
            int gap = now - t.lastSwingStart;
            t.lastSwingStart = now;
            // AutoClicker: the game can only show a new swing every 3 ticks. Humans and bots look identical at
            // that cap, so the only tell is keeping it up for a very long time without a single pause.
            if (gap > 0 && gap <= 3) {
                if (t.satRun == 0) t.satStart = now;
                t.satRun++;
                if (t.satRun >= 40 && now - t.satStart >= 240 && !p.isSpectator()) {
                    violate(t, Cheat.AUTOCLICK, 1.0, String.format("max-rate swinging for %d s without a pause", (now - t.satStart) / 20));
                    t.satRun = 0;
                }
            } else {
                t.satRun = 0;
            }
            // Vanilla ignores attack clicks while you are using an item, so swinging mid-use is impossible.
            if (using && now - t.useStart >= 8 && !freeUse(p) && !p.isSpectator() && !p.isCreative()) {
                t.pendingUseSwing = now;
            }
        }
        // confirm 2 ticks later that they are STILL using the item (rules out lag between the two packets)
        if (t.pendingUseSwing > 0 && now - t.pendingUseSwing >= 2) {
            if (using && now - t.useStart >= 10 && !freeUse(p) && t.ping <= MAX_PING) {
                violate(t, Cheat.AUTOBLOCK, 1.0, "swung while using " + itemPath(p.getUseItem()));
            }
            t.pendingUseSwing = 0;
        }

        if (now - t.lastSwingStart <= 3 && !p.isSpectator()) {
            swingP.add(p);
            swingT.add(t);
        }
    }

    // =====================================================================================
    // movement
    // =====================================================================================

    void movement(ClientLevel level, AbstractClientPlayer p, Track t) {
        final double x = p.getX(), y = p.getY(), z = p.getZ();
        if (!t.has) {
            t.has = true;
            t.born = now;
            t.px = x; t.py = y; t.pz = z;
            t.lastHeadYaw = p.getYHeadRot();
            t.fallPeak = y;
            t.pushHist(x, y, z, y + p.getEyeHeight());
            return;
        }
        final double dx = x - t.px, dy = y - t.py, dz = z - t.pz;
        final double h = Math.sqrt(dx * dx + dz * dz);
        t.px = x; t.py = y; t.pz = z;
        t.pushHist(x, y, z, y + p.getEyeHeight());
        final boolean onGround = p.onGround();

        // teleport / pearl / respawn: ignore and reset
        if (dx * dx + dy * dy + dz * dz > 25) {
            t.resetMovement();
            t.lastTeleport = now;
            t.airborne = false;
            t.nfDue = 0;
            t.kbDue = 0;
            t.stillRun = 0;
            return;
        }

        // A player that freezes dead in the middle of a run is lagging (or hit a wall). When they unfreeze the
        // catch-up looks like speed, so we ignore them for a moment.
        boolean still = h < 1e-4 && Math.abs(dy) < 1e-4;
        if (still) {
            if (t.stillRun++ == 0) t.moveBeforeStill = t.lastH + Math.abs(t.lastDy);
        } else {
            if (t.stillRun >= 3 && t.moveBeforeStill > 0.12) t.lastStall = now;
            t.stillRun = 0;
        }
        t.lastH = h;
        t.lastDy = dy;

        // fast exit: standing still on the ground has nothing to check
        if (still && onGround && !t.airborne) {
            t.resetWindow();
            t.wasGround = true;
            t.airTicks = 0;
            if (p.hurtTime > 0) t.lastHurt = now;
            return;
        }

        fx.read(p);

        // ---- bookkeeping for exemptions ----
        if (p.hurtTime > 0) t.lastHurt = now;
        if (p.isInWater() || p.isInLava()) t.lastWater = now;
        if (holdingSpecial(p)) t.lastSpecial = now;
        if (launcher(p) && now - t.lastLauncherNote >= 10) {
            t.lastLauncherNote = now;
            noteLauncher(x, y, z);
        }
        if (p.isPassenger()) t.lastRide = now;
        if (p.isFallFlying() || p.isAutoSpinAttack()) t.lastGlide = now;
        BlockState under = level.getBlockState(mp.set(x, y - 0.2, z));
        Block ub = under.getBlock();
        if (ub.getFriction() > 0.62f) t.lastSlip = now;
        if (under.is(Blocks.SLIME_BLOCK) || under.is(Blocks.HONEY_BLOCK) || ub instanceof BedBlock) t.lastBounce = now;
        if (under.is(Blocks.SOUL_SAND) || under.is(Blocks.SOUL_SOIL)) t.lastSoul = now;
        final boolean feetBlocked = !level.getBlockState(p.blockPosition()).isAir(); // webs, water, vines, bushes...

        fallTracking(level, p, t, x, y, z, onGround, feetBlocked);

        boolean exempt = p.isSpectator() || p.isCreative() || p.isPassenger() || p.isFallFlying()
                || p.isAutoSpinAttack() || p.isSleeping() || p.isDeadOrDying();
        boolean recent = now - t.lastHurt < 40 || now - t.lastSpecial < 100 || now - t.lastWater < 20
                || now - t.lastBounce < 60 || now - t.lastRide < 40 || now - t.lastGlide < 50
                || now - t.lastStall < 30 || (spN > 0 && nearLauncher(x, y, z));
        boolean warm = now - t.born < WARMUP || now - t.lastTeleport < 20;
        if (exempt || recent || warm || t.ping > MAX_PING) {
            t.resetMovement();
            t.wasGround = onGround;
            return;
        }

        final boolean slip = now - t.lastSlip < 20;
        final boolean soul = now - t.lastSoul < 40;
        final boolean climb = p.onClimbable();
        final boolean sneak = p.isShiftKeyDown();

        // ---------------- SPEED (+ TOWER from the same 20 tick window) ----------------
        if (climb || slip || soul || feetBlocked) {
            t.resetWindow();
        } else {
            t.pushWindow(h, y, !onGround, sneak);
            if (t.ringN == 20) windowChecks(level, p, t);
        }

        // ---------------- NOSLOW ----------------
        boolean slowing = p.isUsingItem() && !freeUse(p);
        if (slowing && now - t.useStart >= 8 && onGround && !slip && !feetBlocked) {
            t.useTicks++;
            t.useSum += h;
            if (t.useTicks >= 12) {
                double avg = t.useSum / t.useTicks;
                double limit = 0.085 * fx.speedMul() * len;
                if (avg > limit) {
                    violate(t, Cheat.NOSLOW, 1.0, String.format("%.1f b/s while using an item (limit %.1f)", avg * 20, limit * 20));
                }
                t.useTicks = 0;
                t.useSum = 0;
            }
        } else {
            t.useTicks = 0;
            t.useSum = 0;
        }

        // ---------------- AIR: FLY / HOVER / GLIDE / AIR JUMP / HIGH JUMP ----------------
        final boolean airOk = !climb && !feetBlocked;
        if (!onGround) {
            if (t.wasGround) { // just left the ground: work out what this jump is allowed to do
                t.airStartY = y - dy;
                t.airStartValid = true;
                t.flaggedJump = false;
                t.descended = false;
                t.airUp = 0;
                t.airTicks = 0;
                double base = Mth.clamp(p.getAttributeValue(Attributes.JUMP_STRENGTH), 0.42, 3.0);
                if (fx.jump == 0 && base < 0.4201) {
                    t.curPeak = Physics.PEAK0; t.curAsc = Physics.ASC0; t.curAir = Physics.AIR0;
                } else {
                    double v = Physics.jumpVelocity(base, fx.jump);
                    t.curPeak = Physics.jumpPeak(v); t.curAsc = Physics.ascendTicks(v); t.curAir = Physics.airTime(v);
                }
            }
            t.airTicks++;
            t.pushDy(dy);

            if (airOk && !fx.levitation) {
                // hover / level flight / endless rising
                if (Math.abs(dy) < 0.006) {
                    t.hoverTicks++;
                    if (h > 0.03) t.hoverMove++;
                } else {
                    t.hoverTicks = 0;
                    t.hoverMove = 0;
                }
                if (dy > 0.02) t.ascendTicks++; else t.ascendTicks = 0;

                // 14 ticks of level flight while moving, or 40 ticks frozen (a short freeze is just lag)
                if (t.hoverMove >= 14 || t.hoverTicks >= 40) {
                    boolean moving = t.hoverMove >= 14;
                    t.hoverTicks = 0;
                    t.hoverMove = 0;
                    if (!excused(level, p)) {
                        violate(t, Cheat.FLY, 1.0, moving ? "flying level with nothing under them" : "hovering in mid-air");
                    }
                }
                int ascLimit = (t.airStartValid ? t.curAsc : Physics.ascendTicks(Physics.jumpVelocity(0.42, fx.jump))) + 6;
                if (t.ascendTicks >= ascLimit) {
                    int n = t.ascendTicks;
                    t.ascendTicks = 0;
                    if (!excused(level, p)) {
                        violate(t, Cheat.FLY, 1.0, "kept rising for " + n + " ticks (max for this jump " + (ascLimit - 6) + ")");
                    }
                }

                // glide: after ~1 second in the air gravity has pulled you down several blocks, always
                int glideAfter = Math.max(22, (t.airStartValid ? t.curAir : Physics.AIR0) + 10);
                if (!fx.slowFall && t.airTicks >= glideAfter && t.dyN >= 10 && t.sumDy > -2.5 && now >= t.glideCool) {
                    t.glideCool = now + 20;
                    if (!excused(level, p)) {
                        violate(t, Cheat.FLY, 1.0, String.format("fell only %.1f blocks in 0.5 s after %d ticks in the air", -t.sumDy, t.airTicks));
                    }
                }

                // air jump: falling clearly, then rising again with nothing to push them
                if (dy < -0.12) t.descended = true;
                if (t.descended && dy > 0.15) {
                    if (++t.airUp >= 2) {
                        t.descended = false;
                        t.airUp = 0;
                        if (!excused(level, p)) violate(t, Cheat.AIR_JUMP, 1.0, String.format("started rising again in mid-air (%.2f)", dy));
                    }
                } else if (dy < 0.05) {
                    t.airUp = 0;
                }

                // high jump (jump boost counted)
                if (t.airStartValid && !t.flaggedJump) {
                    double rise = y - t.airStartY;
                    double max = (t.curPeak + 0.4) * len;
                    if (rise > max) {
                        t.flaggedJump = true;
                        if (!excused(level, p)) {
                            violate(t, Cheat.HIGH_JUMP, 1.0, String.format("rose %.2f blocks (max %.2f%s)", rise, t.curPeak,
                                    fx.jump > 0 ? ", jump boost " + fx.jump : ""));
                        }
                    }
                }
            } else {
                t.hoverTicks = 0; t.hoverMove = 0; t.ascendTicks = 0; t.descended = false; t.airUp = 0;
            }
        } else {
            t.resetAir();
        }

        // ---------------- STEP ----------------
        if (onGround) {
            t.groundRun++;
            t.stepY[t.stepI] = y;
            t.stepH[t.stepI] = h;
            t.stepI = (t.stepI + 1) & 3;
            if (t.groundRun >= 4) {
                double oldest = t.stepY[t.stepI];     // after advancing, this slot holds the oldest sample
                double path = t.stepH[0] + t.stepH[1] + t.stepH[2] + t.stepH[3];
                double rise = y - oldest;
                // stairs climb about 1 block of height per block walked, so allow that plus one step
                double allowed = Math.max(0.9, path + 0.6) * len;
                if (rise > allowed && now - t.lastStepFlag >= 20) {
                    t.lastStepFlag = now;
                    violate(t, Cheat.STEP, 1.0, String.format("stepped up %.2f blocks while walking %.2f", rise, path));
                }
            }
        } else {
            t.groundRun = 0;
        }

        // ---------------- JESUS ----------------
        boolean onWaterSurface = under.is(Blocks.WATER) && !p.isInWater() && !feetBlocked;
        if (onWaterSurface && h > 0.08 && Math.abs(dy) < 0.01) t.jesusTicks++; else t.jesusTicks = 0;
        if (t.jesusTicks >= 10 && t.jesusTicks % 10 == 0) {
            if (!entityBelow(level, p)) violate(t, Cheat.JESUS, 1.0, "moving across the water surface");
        }

        // ---------------- PHASE (moving through solid blocks) ----------------
        if (h > 0.05 && p.getBbHeight() > 1.4f) {
            AABB bb = p.getBoundingBox();
            // shrunk on the sides and with the bottom 0.62 cut off, so stepping onto stairs never counts
            AABB core = new AABB(bb.minX + 0.22, bb.minY + 0.62, bb.minZ + 0.22,
                    bb.maxX - 0.22, bb.maxY - 0.12, bb.maxZ - 0.22);
            boolean inside = false;
            for (VoxelShape s : level.getBlockCollisions(p, core)) {
                if (!s.isEmpty()) { inside = true; break; }
            }
            if (inside) t.phaseTicks++; else t.phaseTicks = 0;
            if (t.phaseTicks >= 3) {
                t.phaseTicks = 0;
                violate(t, Cheat.PHASE, 1.0, "body inside solid blocks while moving");
            }
        } else {
            t.phaseTicks = 0;
        }

        // ---------------- OMNI-SPRINT ----------------
        if (p.isSprinting() && onGround && h > 0.15) {
            double yaw = Math.toRadians(p.getYHeadRot());
            double dot = (dx / h) * -Math.sin(yaw) + (dz / h) * Math.cos(yaw);
            if (dot < -0.2) t.sprintBack++; else t.sprintBack = Math.max(0, t.sprintBack - 2);
            if (t.sprintBack >= 12) {
                t.sprintBack = 0;
                violate(t, Cheat.OMNI_SPRINT, 1.0, "sprinting backwards / sideways");
            }
        } else {
            t.sprintBack = Math.max(0, t.sprintBack - 1);
        }

        t.wasGround = onGround;
    }

    /** Runs once the 20 tick window is full. */
    private void windowChecks(ClientLevel level, AbstractClientPlayer p, Track t) {
        final double avg = t.sumH / 20.0;
        final double mul = fx.speedMul();

        // ---- speed ----
        double limit;
        String mode;
        if (t.sumSneak == 20 && t.sumAir == 0) { limit = Physics.SNEAK * 1.4; mode = "sneaking"; }
        else if (t.sumSneak > 0) { limit = -1; mode = ""; }
        else if (t.sumAir == 0) { limit = Physics.SPRINT * 1.14; mode = "on the ground"; }
        else { limit = 0.40; mode = "sprint-jumping"; }

        if (limit > 0) {
            limit *= mul * len;
            if (avg > limit) {
                // last chance for an innocent explanation (boat / minecart under them)
                if (!entityBelow(level, p)) {
                    String pot = (fx.speed > 0 || fx.slow > 0) ? String.format(" [speed %d, slowness %d]", fx.speed, fx.slow) : "";
                    violate(t, Cheat.SPEED, 1.0, String.format("%.1f b/s %s (limit %.1f)%s", avg * 20, mode, limit * 20, pot));
                }
                t.resetWindow();
                return;
            }
        }

        // ---- tower: going straight up faster than pillar-jumping allows ----
        if (fx.jump == 0 && !fx.levitation && t.sumH < 1.5) {
            double gain = p.getY() - t.ringY[t.ringI];
            if (gain > 3.2 * len) {
                if (!excused(level, p)) {
                    violate(t, Cheat.TOWER, 1.0, String.format("gained %.1f blocks of height in 1 s while barely moving sideways", gain));
                }
                t.resetWindow();
            }
        }
    }

    // ---------------- NOFALL ----------------
    private void fallTracking(ClientLevel level, AbstractClientPlayer p, Track t,
                              double x, double y, double z, boolean onGround, boolean feetBlocked) {
        // Resistance V (and up) can cancel fall damage entirely
        boolean noDamage = p.isInWater() || p.isInLava() || p.onClimbable() || p.isFallFlying() || p.isPassenger()
                || feetBlocked || fx.slowFall || fx.levitation || fx.resist >= 5
                || p.isCreative() || p.isSpectator() || now - t.lastSpecial < 100;

        if (!onGround) {
            if (!t.airborne) {
                t.airborne = true;
                t.fallPeak = y;
                t.fallInvalid = false;
            }
            t.fallPeak = Math.max(t.fallPeak, y);
            if (noDamage) t.fallInvalid = true;
        } else if (t.airborne) {
            t.airborne = false;
            double drop = t.fallPeak - y;
            BlockState under = level.getBlockState(mp.set(x, y - 0.2, z));
            boolean soft = under.is(Blocks.SLIME_BLOCK) || under.is(Blocks.HAY_BLOCK)
                    || under.is(Blocks.HONEY_BLOCK) || under.getBlock() instanceof BedBlock;
            // fall damage starts after 3 blocks, and Jump Boost raises that by 1 block per level
            double safe = Math.max(3.0, p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)) + fx.jump;
            if (drop > safe + 1.5 && !t.fallInvalid && !noDamage && !soft
                    && now - t.born >= WARMUP && now - t.lastTeleport >= 20 && t.ping <= MAX_PING
                    && !entityBelow(level, p)) {
                t.nfDue = now + 8;
                t.nfDrop = drop;
            }
        }

        if (t.nfDue > 0 && now >= t.nfDue) {
            t.nfDue = 0;
            // a hurt animation around the landing would have refreshed lastHurt
            if (now - t.lastHurt > 10) {
                violate(t, Cheat.NOFALL, 1.0, String.format("fell %.1f blocks and took no damage", t.nfDrop));
            }
        }
    }

    // =====================================================================================
    // combat
    // =====================================================================================

    /** Called once per tick for every player after movement: velocity result + hurt detection. */
    void combat(ClientLevel level, AbstractClientPlayer p, Track t) {
        float yaw = p.getYHeadRot();
        float yawDelta = Math.abs(Mth.wrapDegrees(yaw - t.lastHeadYaw));
        t.lastHeadYaw = yaw;

        // ---- VELOCITY: result of the knockback test set up 8 ticks ago ----
        if (t.kbDue > 0 && now >= t.kbDue) {
            t.kbDue = 0;
            double moved = (p.getX() - t.kbSx) * t.kbDx + (p.getZ() - t.kbSz) * t.kbDz;
            if (moved < t.kbNeed && !p.isDeadOrDying() && t.ping <= MAX_PING) {
                violate(t, Cheat.VELOCITY, 1.0, String.format("moved only %.2f blocks after a hit (needed %.2f)", moved, t.kbNeed));
            }
        }

        if (p.isSpectator() || p.isCreative() || t.ping > MAX_PING) {
            t.prevHurtTime = p.hurtTime;
            return;
        }

        // ---- KILLAURA: aim snap on the swing ----
        if (t.swingStart && yawDelta > 75 && now - t.born >= WARMUP) {
            AbstractClientPlayer near = nearest(p);
            if (near != null && near.getBoundingBox().distanceToSqr(p.getEyePosition()) < 36.0) {
                violate(t, Cheat.KILLAURA, 0.4, String.format("aim snapped %.0f degrees in 1 tick", yawDelta));
            }
        }

        // victim just got hurt this tick -> find who hit them
        if (p.hurtTime >= 9 && t.prevHurtTime < 9) hurt(level, p, t);
        t.prevHurtTime = p.hurtTime;
    }

    private List<AbstractClientPlayer> all = java.util.Collections.emptyList();

    /** The list of players for this tick (shared, not copied). */
    void setPlayers(List<AbstractClientPlayer> players) {
        all = players;
    }

    /** Only called for the rare tick that someone swings with a snapped aim. */
    private AbstractClientPlayer nearest(AbstractClientPlayer p) {
        AbstractClientPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            AbstractClientPlayer q = all.get(i);
            if (q == p) continue;
            double d = q.getBoundingBox().distanceToSqr(p.getEyePosition());
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }

    /**
     * Smallest eye-to-hitbox distance over the last 3 ticks of both players. Remote players are drawn a few
     * ticks behind the truth, so taking the minimum can only ever make us more forgiving.
     */
    private static double minReach(Track a, Track v, double hw, double ht) {
        int na = Math.min(a.histN, 3), nv = Math.min(v.histN, 3);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < na; i++) {
            int ai = a.back(i);
            for (int j = 0; j < nv; j++) {
                int vj = v.back(j);
                double dx = Math.max(Math.max(v.hx[vj] - hw - a.hx[ai], 0), a.hx[ai] - (v.hx[vj] + hw));
                double dy = Math.max(Math.max(v.hy[vj] - a.he[ai], 0), a.he[ai] - (v.hy[vj] + ht));
                double dz = Math.max(Math.max(v.hz[vj] - hw - a.hz[ai], 0), a.hz[ai] - (v.hz[vj] + hw));
                double d = dx * dx + dy * dy + dz * dz;
                if (d < best) best = d;
            }
        }
        return Math.sqrt(best);
    }

    private void hurt(ClientLevel level, AbstractClientPlayer v, Track vt) {
        if (swingP.isEmpty()) return;
        final double hw = v.getBbWidth() / 2.0, ht = v.getBbHeight();
        final Vec3 center = v.getBoundingBox().getCenter();

        AbstractClientPlayer best = null;
        Track bt = null;
        double bestDot = -2, bestDist = 0;
        int facing = 0, within45 = 0;
        for (int i = 0; i < swingP.size(); i++) {
            AbstractClientPlayer a = swingP.get(i);
            if (a == v) continue;
            Track at = swingT.get(i);
            if (at.ping > MAX_PING || a.isCreative() || a.isPassenger() || now - at.born < WARMUP) continue;
            double d = minReach(at, vt, hw, ht);
            if (d > 7.5) continue;
            if (d < 4.5) within45++;
            double dot = lookVec(a).dot(center.subtract(a.getEyePosition()).normalize());
            if (dot > 0.6) facing++;
            if (dot > bestDot) { bestDot = dot; best = a; bt = at; bestDist = d; }
        }
        if (best == null || !meleeItem(best.getMainHandItem())) return;
        if (facing >= 2) return; // a group fight: cannot tell who hit whom, so judge nobody

        if (facing == 1) {
            // ---------------- REACH ----------------
            bt.lastHitUuid = v.getUUID();
            bt.lastHitTick = now;
            bt.reachHits++;
            bt.reachSum += bestDist;
            bt.lastReach = bestDist;
            if (bestDist > bt.maxReach) bt.maxReach = bestDist;

            double base = Mth.clamp(best.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), 3.0, 6.0);
            double speeds = Math.min(bt.lastH, 0.6) + Math.min(vt.lastH, 0.6);
            double pingMax = Math.max(bt.ping, vt.ping);
            // positions we see lag behind the server, so allow for how fast both players move and how laggy they are
            double tol = (0.25 + 0.6 * speeds * (0.5 + pingMax / 100.0)) * len;
            double limit = base + tol;
            if (bestDist > limit) {
                double over = bestDist - limit;
                if (now - bt.lastReachStrike > 400) bt.reachStrikes = 0;
                bt.reachStrikes++;
                bt.lastReachStrike = now;
                if (over > 1.2 || bt.reachStrikes >= 2) {
                    violate(bt, Cheat.REACH, 1.0 + Math.min(1.0, over / 2.0),
                            String.format("hit from %.2f blocks (limit %.2f, vanilla %.1f)", bestDist, limit, base));
                }
            }
            setupKnockbackTest(level, v, vt, best);
        } else if (facing == 0 && within45 == 1 && bestDist < 4.5 && bestDot < -0.3) {
            // not looking anywhere near the victim: decided at the end of the tick (see finishCombat)
            bt.behindTick = now;
            bt.behindDot = bestDot;
            bt.behindVictim = v.getUUID();
        }
    }

    /** Called after every hurt event of this tick has been processed. */
    void finishCombat() {
        for (int i = 0; i < swingT.size(); i++) {
            Track bt = swingT.get(i);
            if (bt.behindTick != now) continue;
            boolean multi = bt.lastHitUuid != null && !bt.lastHitUuid.equals(bt.behindVictim) && now - bt.lastHitTick <= 3;
            if (multi) {
                violate(bt, Cheat.KILLAURA, 3.0, "hit two different players at once, one of them not in their crosshair");
            } else {
                violate(bt, Cheat.KILLAURA, 0.5, String.format("hit a player who was behind them (%.0f degrees off)",
                        Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, bt.behindDot))))));
            }
            bt.behindTick = -1;
        }
    }

    /** Remember where the victim is now; 8 ticks later we check they were actually pushed away from the attacker. */
    private void setupKnockbackTest(ClientLevel level, AbstractClientPlayer victim, Track vt, AbstractClientPlayer attacker) {
        if (victim.isInWater() || victim.isInLava() || victim.isPassenger() || victim.isBlocking()
                || victim.isFallFlying() || victim.isSleeping() || victim.isCreative()
                || victim.isSpectator() || victim.isDeadOrDying()) return;
        if (vt.ping > MAX_PING || now - vt.born < WARMUP || now - vt.lastTeleport < 20 || now - vt.lastStall < 30) return;
        if (now - vt.lastKbTest < 12 || vt.kbDue > 0) return;     // combo: only the first hit is clean

        double hx = victim.getX() - attacker.getX(), hz = victim.getZ() - attacker.getZ();
        double l = Math.sqrt(hx * hx + hz * hz);
        if (l < 0.3) return;
        hx /= l;
        hz /= l;

        double vx = victim.getX(), vy = victim.getY(), vz = victim.getZ();
        if (!level.getBlockState(victim.blockPosition()).isAir()) return; // webs, water, bushes...
        // a wall behind the victim would absorb the knockback
        for (double step = 0.6; step <= 1.6; step += 0.5) {
            if (!level.getBlockState(mp.set(vx + hx * step, vy + 0.1, vz + hz * step)).isAir()
                    || !level.getBlockState(mp.set(vx + hx * step, vy + 1.1, vz + hz * step)).isAir()) return;
        }
        // armour with knockback resistance (netherite) legitimately cuts the push
        double res = Mth.clamp(victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0, 1.0);
        if (res >= 0.99) return;
        vt.lastKbTest = now;
        vt.kbDue = now + 8;
        vt.kbSx = vx;
        vt.kbSz = vz;
        vt.kbDx = hx;
        vt.kbDz = hz;
        vt.kbNeed = Math.max(0.08, 0.5 * (1.0 - res)) / Math.max(0.5, len);
    }
}
