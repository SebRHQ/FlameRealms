package com.flamerealms.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Creates/updates/removes the one WorldGuard region backing a single claimed
 * chunk's actual in-world protection — the {@code BUILD} flag denied for
 * everyone except the owning realm's current members.
 *
 * <p>Deliberately NOT a Bukkit event listener: it has no events of its own,
 * it is a one-shot action class called by {@code RealmActions} exactly at
 * the points a realm's claims or membership change (claim purchase, unclaim,
 * join, leave, disband). See {@code RealmActions}'s own wiring for each call
 * site.
 *
 * <p><b>Main thread only.</b> Every method here touches the Bukkit {@link
 * World} API and WorldGuard's region manager, both of which — same as every
 * other Bukkit-touching call in this project (see {@code
 * ClaimVisualizationService}'s own Javadoc) — must never be called from off
 * the main thread. Callers are responsible for hopping back via their own
 * {@code runSync(...)}-equivalent before calling in here.
 *
 * <p><b>Best-effort, never throws.</b> By the time either method here runs,
 * the actual claim purchase/membership change has already committed to the
 * database successfully — this is enforcement layered on top of that, not
 * part of the transactional guarantee. A missing/unloaded world or a world
 * with region management disabled is logged at {@code WARNING} and skipped
 * rather than thrown, so a WorldGuard hiccup degrades to "this one chunk
 * didn't get (re)protected, logged" instead of breaking the calling
 * command/GUI flow.
 *
 * <p><b>No startup resync.</b> This class never re-syncs existing claims'
 * regions on plugin enable — WorldGuard persists its own region data to disk
 * independently, so as long as regions are created/updated/removed exactly
 * when claims and realm membership change (which is what every call site
 * does), they stay in sync without a startup pass. A deliberate
 * simplification, not a gap.
 */
public final class ClaimProtectionService {

    /** Width/depth of a Minecraft chunk, in blocks. */
    private static final int CHUNK_SIZE = 16;

    private final Plugin plugin;

    /**
     * @param plugin used only for logging skipped (best-effort) failures via
     *               {@link Plugin#getLogger()} — WorldGuard's own API needs
     *               no {@link Plugin} instance to look up its region
     *               container
     */
    public ClaimProtectionService(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates (or, if one already exists at this chunk, entirely replaces)
     * the WorldGuard region protecting {@code (world, chunkX, chunkZ)}:
     * {@code BUILD} denied for everyone, {@code memberUuids} added as the
     * region's members so they remain exempt from that denial. WorldGuard's
     * own {@code RegionManager#addRegion} contract is to overwrite an
     * existing region sharing the same id, which is exactly what re-syncing
     * an existing claim's membership after a realm roster change needs — no
     * separate create-vs-update path.
     *
     * <p>A no-op (logged at {@code WARNING}) if {@code world} does not
     * resolve to a currently-loaded {@link World}, or if that world has
     * region management disabled in WorldGuard (a {@code null}
     * {@link RegionManager}).
     */
    public void protectClaim(String world, int chunkX, int chunkZ, Set<UUID> memberUuids) {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            plugin.getLogger().log(Level.WARNING,
                    "Cannot protect claim at {0} ({1}, {2}) — world is not loaded.",
                    new Object[] {world, chunkX, chunkZ});
            return;
        }

        RegionManager regionManager = regionManagerFor(bukkitWorld);
        if (regionManager == null) {
            plugin.getLogger().log(Level.WARNING,
                    "Cannot protect claim at {0} ({1}, {2}) — WorldGuard region management is "
                            + "disabled for this world.",
                    new Object[] {world, chunkX, chunkZ});
            return;
        }

        int minX = chunkX * CHUNK_SIZE;
        int minZ = chunkZ * CHUNK_SIZE;
        int maxX = minX + CHUNK_SIZE - 1;
        int maxZ = minZ + CHUNK_SIZE - 1;
        BlockVector3 min = BlockVector3.at(minX, bukkitWorld.getMinHeight(), minZ);
        BlockVector3 max = BlockVector3.at(maxX, bukkitWorld.getMaxHeight() - 1, maxZ);

        ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionId(world, chunkX, chunkZ), min, max);
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);

        DefaultDomain members = new DefaultDomain();
        for (UUID memberUuid : memberUuids) {
            members.addPlayer(memberUuid);
        }
        region.setMembers(members);

        regionManager.addRegion(region);
    }

    /**
     * Removes the WorldGuard region protecting {@code (world, chunkX,
     * chunkZ)}, if any. A no-op (logged at {@code WARNING}) if {@code world}
     * does not resolve to a currently-loaded {@link World}, or if that
     * world's {@link RegionManager} is {@code null} — nothing to remove
     * either way.
     */
    public void unprotectClaim(String world, int chunkX, int chunkZ) {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            plugin.getLogger().log(Level.WARNING,
                    "Cannot unprotect claim at {0} ({1}, {2}) — world is not loaded.",
                    new Object[] {world, chunkX, chunkZ});
            return;
        }

        RegionManager regionManager = regionManagerFor(bukkitWorld);
        if (regionManager == null) {
            plugin.getLogger().log(Level.WARNING,
                    "Cannot unprotect claim at {0} ({1}, {2}) — WorldGuard region management is "
                            + "disabled for this world.",
                    new Object[] {world, chunkX, chunkZ});
            return;
        }

        regionManager.removeRegion(regionId(world, chunkX, chunkZ));
    }

    private static RegionManager regionManagerFor(World bukkitWorld) {
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
    }

    /**
     * Deterministic WorldGuard region id for one chunk, shared by {@link
     * #protectClaim} and {@link #unprotectClaim} so both always agree on the
     * same id for the same chunk. WorldGuard region ids are conventionally
     * lowercase alphanumeric/underscore, so {@code world} is lowercased and
     * every character outside {@code [a-z0-9_]} replaced with {@code _};
     * the numeric chunk coordinates are appended as-is (a leading {@code -}
     * for a negative coordinate is fine in a WorldGuard region id).
     */
    private static String regionId(String world, int chunkX, int chunkZ) {
        String sanitizedWorld = world.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return "flamerealms_" + sanitizedWorld + "_" + chunkX + "_" + chunkZ;
    }
}
