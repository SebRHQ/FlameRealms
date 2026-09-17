package com.flamerealms.domain;

/**
 * A world-qualified chunk coordinate: identifies one 16x16 chunk within one
 * world, used for contiguity math (claim adjacency) and as a cache/lookup
 * key. Plain data only — no Bukkit types, so this can be built from either a
 * live {@code Chunk}/{@code Location} or a persisted {@link RealmClaim}
 * without a Bukkit world instance in hand.
 *
 * <p>{@code world} is a Bukkit world name (as returned by
 * {@code World#getName()}). Records get {@code equals}/{@code hashCode} for
 * free from their component values, and {@code String#equals} is
 * case-sensitive, so two coordinates in worlds differing only by case are
 * treated as different worlds here — matching Bukkit itself, which resolves
 * world names case-sensitively everywhere else in this project.
 *
 * @param world  Bukkit world name
 * @param chunkX chunk X coordinate
 * @param chunkZ chunk Z coordinate
 */
public record ChunkCoordinate(String world, int chunkX, int chunkZ) {

    /**
     * Whether {@code other} is one of this coordinate's 4 orthogonal
     * neighbors (dx=&plusmn;1,dz=0 or dx=0,dz=&plusmn;1) in the same world.
     * Diagonal neighbors and the same chunk both return {@code false}.
     */
    public boolean isAdjacentTo(ChunkCoordinate other) {
        if (other == null || !world.equals(other.world)) {
            return false;
        }
        int dx = Math.abs(chunkX - other.chunkX);
        int dz = Math.abs(chunkZ - other.chunkZ);
        return (dx == 1 && dz == 0) || (dx == 0 && dz == 1);
    }
}
