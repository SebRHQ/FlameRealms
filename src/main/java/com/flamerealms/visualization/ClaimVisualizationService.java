package com.flamerealms.visualization;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.VisualizationConfig;
import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.Realm;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a player a temporary particle outline of one chunk's boundary — the
 * "here's the chunk you're about to claim/unclaim" preview behind the claim
 * commands. Built entirely on vanilla Paper/Bukkit API: {@link
 * World#spawnParticle} plus the Bukkit scheduler, no packet library, no
 * ProtocolLib.
 *
 * <p>The particles are real, server-side effects — visible to anyone near
 * the chunk, not just {@code player}, exactly like any other vanilla
 * particle. Everything this class does happens on the main thread:
 * {@link #previewChunk} schedules a repeating {@link BukkitRunnable} via
 * {@code runTaskTimer(...)} rather than touching Bukkit off-thread, because
 * particle spawning is Bukkit API and must never run from an async context.
 */
public final class ClaimVisualizationService {

    /** Width/depth of a Minecraft chunk, in blocks. */
    private static final int CHUNK_SIZE = 16;

    /**
     * Blocks between sampled points along one edge. 16 / 4 + 1 = 5 points
     * per edge per Y-level — a handful, not hundreds, per the class's
     * "keep it readable, not a firehose" brief.
     */
    private static final int EDGE_POINT_SPACING = 4;

    /** How many ticks apart each redraw ("pulse") of the outline fires. */
    private static final long PULSE_PERIOD_TICKS = 5L;

    private static final long TICKS_PER_SECOND = 20L;

    /** How far below the player's feet the lowest traced Y-level sits. */
    private static final int Y_LEVEL_BELOW = 1;

    /** How far above the player's feet the highest traced Y-level sits. */
    private static final int Y_LEVEL_ABOVE = 2;

    /** Particles spawned per sampled point, per pulse. */
    private static final int PARTICLES_PER_POINT = 1;

    private final Plugin plugin;
    private final VisualizationConfig config;
    private final RealmCache realmCache;

    /** Player -&gt; their running persistent border-outline task, while {@code /realm borders} is toggled on for them. */
    private final Map<UUID, BukkitTask> borderTasks = new ConcurrentHashMap<>();

    /**
     * @param plugin    used only to own the repeating tasks ({@code
     *                  BukkitRunnable#runTaskTimer(Plugin, long, long)}) —
     *                  this class never calls any other plugin lifecycle method
     * @param config    supplies the particle type and preview duration (config.yml's
     *                  {@code visualization:} block)
     * @param realmCache read only, to resolve a player's current realm and its
     *                   claimed chunks live on every pulse of a persistent border
     *                   display (see {@link #toggleBorders})
     */
    public ClaimVisualizationService(Plugin plugin, VisualizationConfig config, RealmCache realmCache) {
        this.plugin = plugin;
        this.config = config;
        this.realmCache = realmCache;
    }

    /**
     * Traces the four edges of the 16x16 chunk at {@code (chunkX, chunkZ)}
     * in {@code world} with particles, at a few Y-levels bracketing {@code
     * player}'s current Y (from {@code player.getY() - 1} to {@code
     * player.getY() + 2}), redrawing every {@link #PULSE_PERIOD_TICKS} ticks
     * for {@code visualization.preview-duration-seconds} (config.yml), then
     * self-cancels.
     *
     * <p>A no-op if {@code world} does not resolve to a currently-loaded
     * {@link World} (an unknown or mistyped name) — there is nothing to draw
     * a preview in.
     *
     * <p>Must be called from the main thread: it reads {@code player}'s live
     * position and drives Bukkit's scheduler/particle APIs directly.
     */
    public void previewChunk(Player player, String world, int chunkX, int chunkZ) {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return;
        }

        double baseY = player.getY();
        long totalPulses = Math.max(
                1L, (config.previewDurationSeconds() * TICKS_PER_SECOND) / PULSE_PERIOD_TICKS);

        new BukkitRunnable() {
            private long pulsesFired = 0L;

            @Override
            public void run() {
                if (pulsesFired >= totalPulses) {
                    cancel();
                    return;
                }
                pulsesFired++;
                int lowestY = (int) Math.floor(baseY) - Y_LEVEL_BELOW;
                int highestY = (int) Math.floor(baseY) + Y_LEVEL_ABOVE;
                drawEdges(bukkitWorld, chunkX, chunkZ, lowestY, highestY, true, true, true, true);
            }
        }.runTaskTimer(plugin, 0L, PULSE_PERIOD_TICKS);
    }

    /**
     * Toggles a persistent, self-refreshing outline of {@code player}'s
     * entire realm territory: every pulse, only the OUTER edges of the
     * realm's claimed chunks are drawn — an edge shared with another claim of
     * the SAME realm is skipped, so contiguous territory reads as one clean
     * border instead of a grid of every individual chunk's four edges.
     *
     * <p>Re-resolves the player's realm and claim set fresh on every pulse
     * (not just once at toggle time), so it stays correct as claims are
     * bought/released while the display is on, and auto-stops (returning as
     * if the player had toggled it off) the moment the player goes offline or
     * is no longer in a realm at all — there is nothing to outline anymore.
     * Only claims within {@link VisualizationConfig#mapRadiusChunks()} of the
     * player's current chunk, in the player's current world, are drawn each
     * pulse — a realm's far-away claims are skipped rather than spawning
     * particles nobody is near enough to see. Because of that windowing, a
     * claim right at the edge of the window can show a border against a
     * same-realm neighbor that is just outside it — an accepted rendering
     * simplification, not a contiguity bug (contiguity itself is computed
     * from the full claim set, not this display).
     *
     * @return {@code true} if the display is now ON for this player, {@code false} if it was just turned OFF
     */
    public boolean toggleBorders(Player player) {
        UUID playerId = player.getUniqueId();
        BukkitTask existing = borderTasks.remove(playerId);
        if (existing != null) {
            existing.cancel();
            return false;
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin, () -> pulseBorders(player), 0L, PULSE_PERIOD_TICKS);
        borderTasks.put(playerId, task);
        return true;
    }

    /** Cancels {@code playerId}'s persistent border display, if any. Safe to call whether or not one is running. */
    public void stopBorders(UUID playerId) {
        BukkitTask task = borderTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void pulseBorders(Player player) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline()) {
            stopBorders(playerId);
            return;
        }

        Optional<Realm> realm = realmCache.getByPlayer(playerId);
        if (realm.isEmpty()) {
            stopBorders(playerId);
            return;
        }

        Set<ChunkCoordinate> claims = realmCache.claimsOf(realm.get().id());
        if (claims.isEmpty()) {
            return;
        }

        World world = player.getWorld();
        String worldName = world.getName();
        int centerChunkX = player.getLocation().getChunk().getX();
        int centerChunkZ = player.getLocation().getChunk().getZ();
        int radius = config.mapRadiusChunks();
        int lowestY = (int) Math.floor(player.getY()) - Y_LEVEL_BELOW;
        int highestY = (int) Math.floor(player.getY()) + Y_LEVEL_ABOVE;

        for (ChunkCoordinate claim : claims) {
            if (!claim.world().equals(worldName)) {
                continue;
            }
            if (Math.abs(claim.chunkX() - centerChunkX) > radius || Math.abs(claim.chunkZ() - centerChunkZ) > radius) {
                continue;
            }

            boolean north = !claims.contains(new ChunkCoordinate(worldName, claim.chunkX(), claim.chunkZ() - 1));
            boolean south = !claims.contains(new ChunkCoordinate(worldName, claim.chunkX(), claim.chunkZ() + 1));
            boolean west = !claims.contains(new ChunkCoordinate(worldName, claim.chunkX() - 1, claim.chunkZ()));
            boolean east = !claims.contains(new ChunkCoordinate(worldName, claim.chunkX() + 1, claim.chunkZ()));
            drawEdges(world, claim.chunkX(), claim.chunkZ(), lowestY, highestY, north, south, west, east);
        }
    }

    /** One pulse's worth of sampled points along whichever of the four edges are requested, at every traced Y-level. */
    private void drawEdges(
            World world, int chunkX, int chunkZ, int lowestY, int highestY,
            boolean north, boolean south, boolean west, boolean east
    ) {
        int minX = chunkX * CHUNK_SIZE;
        int minZ = chunkZ * CHUNK_SIZE;
        int maxX = minX + CHUNK_SIZE;
        int maxZ = minZ + CHUNK_SIZE;

        for (int y = lowestY; y <= highestY; y++) {
            for (int offset = 0; offset <= CHUNK_SIZE; offset += EDGE_POINT_SPACING) {
                if (north) {
                    spawnPoint(world, minX + offset, y, minZ);
                }
                if (south) {
                    spawnPoint(world, minX + offset, y, maxZ);
                }
                if (west) {
                    spawnPoint(world, minX, y, minZ + offset);
                }
                if (east) {
                    spawnPoint(world, maxX, y, minZ + offset);
                }
            }
        }
    }

    private void spawnPoint(World world, int x, int y, int z) {
        // Centered on the block column (x+0.5 / z+0.5) rather than its
        // corner, purely so the outline reads as sitting on the boundary
        // line instead of looking offset by half a block. offsetX/Y/Z and
        // "extra" (speed) are all explicitly 0 here — the simplified
        // World#spawnParticle(Particle, x, y, z, count) overload this used to
        // call defaults "extra" to 1.0, which gives many particle types
        // (FLAME included) a random per-particle velocity, making the
        // outline visibly drift/scatter instead of sitting still on the
        // boundary line.
        world.spawnParticle(config.particle(), x + 0.5, y, z + 0.5, PARTICLES_PER_POINT, 0.0, 0.0, 0.0, 0.0);
    }
}
