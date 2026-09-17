package com.flamerealms.domain;

/**
 * A world-qualified exact block coordinate: identifies one block within one
 * world. Used to track Nexus locations, which are exact blocks — not whole
 * chunks like {@link ChunkCoordinate} — since two different realms' Nexuses
 * could in principle sit inside the same chunk (claim contiguity only
 * prevents chunk-level overlap between realms' claimed territory; it says
 * nothing about exact block coordinates within different chunks/realms).
 *
 * <p>Plain data only — no Bukkit types, matching {@link ChunkCoordinate}'s
 * own style exactly, so this can be built from either a live {@code Block}/
 * {@code Location} or a persisted {@code Realm}'s nexus columns without a
 * Bukkit world instance in hand.
 *
 * <p>{@code world} is a Bukkit world name (as returned by
 * {@code World#getName()}). Records get {@code equals}/{@code hashCode} for
 * free from their component values, and {@code String#equals} is
 * case-sensitive, so two coordinates in worlds differing only by case are
 * treated as different worlds here — matching {@link ChunkCoordinate} and the
 * rest of this project's convention of resolving world names case-sensitively.
 *
 * @param world Bukkit world name
 * @param x     block X coordinate
 * @param y     block Y coordinate
 * @param z     block Z coordinate
 */
public record BlockCoordinate(String world, int x, int y, int z) {
}
