# fasd's Anti Cheat v3.0 (Fabric, Minecraft 1.21.11)

Client-side heuristic cheat spotter. Needs Fabric Loader 0.18+, Fabric API, Java 21.

**Keys (rebind in Controls):** `K` flag menu, `J` chat report. Unbound by default: toggle detection, toggle tab-list colours, clear all flags.

## What's new in 3.0
- **Accuracy:** every check collects evidence in a buffer before adding a VL, risky situations (webs, water, boats, wind charges, lag freezes) are excused, and a Sensitivity setting (Low / Normal / High) scales every limit.
- **Potions:** Speed, Slowness, Jump Boost, Levitation, Slow Falling and Resistance change the limits for Speed, NoSlow, High Jump, Fly, Air Jump, NoFall and Tower.
- **Flags stay:** once a cheat is confirmed it is kept until you clear it, and saved per server so it survives relogging and restarting.
- **Reach:** only evaluated when someone is actually hit, against players who just swung; uses the closest distance over the last 3 ticks, the attacker's real reach attribute, lag and speed tolerance, and skips group fights it can't attribute.
- **More cheats:** Air Jump, Phase/NoClip, Tower/Scaffold, Omni-Sprint, Hit-while-using-item (AutoBlock), multi-target KillAura, glide/level-flight Fly, sneak-speed.
- **Tracks every player** the server sends to your client, at any distance. The server decides how far that is (its entity tracking range).
- **Menu tabs:** Flagged, Watching, Reach, All players, Settings.
- **Tab list:** flagged players' names turn yellow (suspicious) or red (likely cheating). Switch it off in Settings and the original names come back.

## Build the jar on GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab -> "Build fasd's Anti Cheat jar" runs automatically (or click "Run workflow").
3. When it finishes, open the run and download the **fasds-anti-cheat-jar** artifact. Unzip it; the jar inside goes in your `mods` folder.
