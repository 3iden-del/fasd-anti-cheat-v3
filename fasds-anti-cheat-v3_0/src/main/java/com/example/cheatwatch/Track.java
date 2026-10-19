package com.example.cheatwatch;

import java.util.UUID;

/** Everything we remember about one other player. Flags in here are kept until you clear them. */
public final class Track {
    static final double VL_DECAY = 0.0015;   // unconfirmed VL fades slowly; confirmed flags never fade
    static final double VL_CAP = 40.0;

    UUID uuid;
    String name = "?";

    // ---- presence ----
    boolean has;
    int born, lastSeen = -100000;
    long lastSeenWall;
    double dist;
    int ping;

    // ---- position / motion ----
    double px, py, pz;
    double lastH, lastDy;
    int lastTeleport = -1000, lastStall = -1000;
    int stillRun;
    double moveBeforeStill;
    int slot;                                   // spreads heavy work over several ticks

    final double[] hx = new double[4], hy = new double[4], hz = new double[4], he = new double[4];
    int histI, histN;

    // ---- exemption timers (tick numbers) ----
    int lastHurt = -1000, lastSpecial = -1000, lastWater = -1000, lastSlip = -1000, lastBounce = -1000,
            lastRide = -1000, lastGlide = -1000, lastSoul = -1000, lastLauncherNote = -1000;

    // ---- 20 tick window (speed, tower) ----
    final double[] ringH = new double[20], ringY = new double[20];
    final byte[] ringAir = new byte[20], ringSneak = new byte[20];
    int ringI, ringN, sumAir, sumSneak;
    double sumH;

    // ---- air state ----
    boolean wasGround = true, airStartValid, flaggedJump, descended, airborne, fallInvalid;
    double airStartY, curPeak = Physics.PEAK0, fallPeak, nfDrop;
    int curAsc = Physics.ASC0, curAir = Physics.AIR0;
    int airTicks, hoverTicks, hoverMove, ascendTicks, airUp, glideCool, nfDue;
    final double[] dyRing = new double[10];
    int dyI, dyN;
    double sumDy;

    // ---- step / jesus / phase / sprint ----
    final double[] stepY = new double[4], stepH = new double[4];
    int stepI, groundRun, lastStepFlag = -1000;
    int jesusTicks, phaseTicks, sprintBack;

    // ---- item use / combat ----
    int useStart = -1, useTicks, pendingUseSwing;
    double useSum;
    boolean prevSwinging, swingStart;
    int prevSwingTime, prevHurtTime, lastSwingStart = -1000, satRun, satStart;
    float lastHeadYaw;
    double maxReach, lastReach, reachSum;
    int reachHits, reachStrikes, lastReachStrike = -100000;
    UUID lastHitUuid, behindVictim;
    int lastHitTick = -1000, behindTick = -1000;
    double behindDot;
    int lastKbTest = -1000;

    // pending knockback test (when this player is the victim)
    int kbDue;
    double kbSx, kbSz, kbDx, kbDz, kbNeed;

    // ---- violation levels ----
    final double[] vl = new double[Cheat.COUNT];
    final int[] vlStamp = new int[Cheat.COUNT];
    final double[] buf = new double[Cheat.COUNT];
    final int[] bufStamp = new int[Cheat.COUNT];
    final boolean[] confirmed = new boolean[Cheat.COUNT];
    final int[] count = new int[Cheat.COUNT];
    final int[] lastFlag = new int[Cheat.COUNT];
    final String[] detail = new String[Cheat.COUNT];
    final long[] firstWall = new long[Cheat.COUNT], lastWall = new long[Cheat.COUNT];

    Track() {
        java.util.Arrays.fill(lastFlag, -100000);
    }

    // ------------------------------------------------------------------ history
    void pushHist(double x, double feetY, double z, double eyeY) {
        histI = (histI + 1) & 3;
        hx[histI] = x; hy[histI] = feetY; hz[histI] = z; he[histI] = eyeY;
        if (histN < 4) histN++;
    }

    /** index of the sample k ticks ago (0 = newest) */
    int back(int k) { return (histI - k) & 3; }

    // ------------------------------------------------------------------ 20 tick window
    void pushWindow(double h, double y, boolean air, boolean sneak) {
        if (ringN == 20) {
            sumH -= ringH[ringI];
            sumAir -= ringAir[ringI];
            sumSneak -= ringSneak[ringI];
        }
        ringH[ringI] = h;
        ringY[ringI] = y;
        ringAir[ringI] = (byte) (air ? 1 : 0);
        ringSneak[ringI] = (byte) (sneak ? 1 : 0);
        sumH += h;
        sumAir += ringAir[ringI];
        sumSneak += ringSneak[ringI];
        ringI = (ringI + 1) % 20;
        if (ringN < 20) ringN++;
    }

    void resetWindow() {
        ringN = 0; ringI = 0; sumH = 0; sumAir = 0; sumSneak = 0;
    }

    void pushDy(double dy) {
        if (dyN == 10) sumDy -= dyRing[dyI];
        dyRing[dyI] = dy;
        sumDy += dy;
        dyI = (dyI + 1) % 10;
        if (dyN < 10) dyN++;
    }

    void resetAir() {
        airTicks = 0; hoverTicks = 0; hoverMove = 0; ascendTicks = 0; airUp = 0;
        dyN = 0; dyI = 0; sumDy = 0;
        descended = false; airStartValid = false; flaggedJump = false;
    }

    void resetMovement() {
        resetWindow();
        resetAir();
        groundRun = 0; stepI = 0;
        jesusTicks = 0; phaseTicks = 0; sprintBack = 0;
        useTicks = 0; useSum = 0;
        wasGround = true;
    }

    // ------------------------------------------------------------------ violation levels
    /** Current VL of one cheat. Unconfirmed VL fades; confirmed VL is permanent. */
    double vl(int i, int now) {
        if (confirmed[i]) return vl[i];
        return Math.max(0.0, vl[i] - VL_DECAY * (now - vlStamp[i]));
    }

    double score(int now) {
        double s = 0;
        for (int i = 0; i < Cheat.COUNT; i++) s += vl(i, now);
        return s;
    }

    int confirmedKinds() {
        int n = 0;
        for (boolean b : confirmed) if (b) n++;
        return n;
    }

    double maxConfirmedVl() {
        double m = 0;
        for (int i = 0; i < Cheat.COUNT; i++) if (confirmed[i]) m = Math.max(m, vl[i]);
        return m;
    }

    boolean isFlagged() { return confirmedKinds() > 0; }

    /** 0 = clean, 1 = a few minor flags, 2 = suspicious (yellow), 3 = likely cheating (red) */
    int status(double reportVl, int now) {
        int k = confirmedKinds();
        if (k >= 2 || maxConfirmedVl() >= reportVl * 2.5) return 3;
        if (k == 1) return 2;
        return score(now) >= 1.0 ? 1 : 0;
    }

    String verdict(double reportVl, int now) {
        int s = status(reportVl, now);
        int k = confirmedKinds();
        if (k >= 3) return "VERY LIKELY hacking";
        if (s == 3) return "LIKELY hacking";
        if (s == 2) return "suspicious";
        return "minor flags";
    }

    boolean hasReachData() { return reachHits > 0; }
    double avgReach() { return reachHits == 0 ? 0 : reachSum / reachHits; }
}
