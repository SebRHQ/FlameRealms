package com.flamerealms.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Immutable binding of {@code pricing.yml}'s {@code claims:} and {@code
 * upkeep:} blocks.
 *
 * <p>Like {@link DatabaseConfig}, this record only carries plain data — no
 * live computation state — and is read once during startup. Unlike {@code
 * config.yml} (loaded via {@code plugin.getConfig()}), {@code pricing.yml}
 * is its own file: {@link #load(JavaPlugin)} copies the bundled default into
 * the plugin's data folder on first run via {@code saveResource(...)} (an
 * existing, possibly hand-edited, copy is never overwritten — same pattern
 * as {@link Messages#load(JavaPlugin)}), then loads and parses it from
 * there, never from the jar, so a server admin can edit it in place.
 *
 * @param purchaseTiers              claim-purchase price curve (config: {@code claims.purchase-tiers}),
 *                                    in list order; the first tier whose {@code max-claims} is
 *                                    &gt;= the target claim count wins
 * @param territoryMultiplierTiers   territory-size upkeep multiplier curve (config:
 *                                    {@code upkeep.territory-multiplier-tiers}), same
 *                                    first-match-wins lookup as {@code purchaseTiers}
 * @param costPerChunkCents          base upkeep cost per claimed chunk, in cents
 *                                    (config: {@code upkeep.cost-per-chunk-cents})
 * @param presenceThresholdMinutes   online-minutes-per-day threshold for a member to
 *                                    count as "present" that day (config:
 *                                    {@code upkeep.active-population.presence-threshold-minutes})
 * @param rollingWindowDays          width, in days, of the rolling window the active-population
 *                                    signal is computed over (config:
 *                                    {@code upkeep.active-population.rolling-window-days})
 * @param saturationConstant         tuning constant for whatever saturating active-population
 *                                    curve the service layer implements (config:
 *                                    {@code upkeep.active-population.saturation-constant})
 * @param maxMultiplierBonus         upper bound on the active-population multiplier bonus
 *                                    (config: {@code upkeep.active-population.max-multiplier-bonus})
 */
public record PricingConfig(
        List<PriceTier> purchaseTiers,
        List<MultiplierTier> territoryMultiplierTiers,
        long costPerChunkCents,
        int presenceThresholdMinutes,
        int rollingWindowDays,
        double saturationConstant,
        double maxMultiplierBonus
) {

    /** One band of {@code claims.purchase-tiers}: price for claim counts up to (and including) {@code maxClaims}. */
    public record PriceTier(int maxClaims, long priceCents) {
    }

    /** One band of {@code upkeep.territory-multiplier-tiers}: multiplier for claim counts up to (and including) {@code maxClaims}. */
    public record MultiplierTier(int maxClaims, double multiplier) {
    }

    /**
     * Copies the bundled default {@code pricing.yml} into the plugin's data
     * folder if it isn't there yet, then reads and parses it. Call once from
     * {@code onEnable()}.
     */
    public static PricingConfig load(JavaPlugin plugin) {
        plugin.saveResource("pricing.yml", false);
        File file = new File(plugin.getDataFolder(), "pricing.yml");
        return fromConfig(YamlConfiguration.loadConfiguration(file), plugin.getLogger());
    }

    /**
     * Parses an already-loaded {@code pricing.yml}. Split out from
     * {@link #load(JavaPlugin)} the same way {@link
     * DatabaseConfig#fromConfig} is split from its own file I/O, so tests
     * can feed in a {@link FileConfiguration} directly.
     *
     * @throws IllegalStateException if either tier list is empty or missing
     *                                — a pricing curve with no bands is a
     *                                misconfiguration, not a silent default
     */
    public static PricingConfig fromConfig(FileConfiguration config, Logger logger) {
        List<PriceTier> purchaseTiers = parsePriceTiers(config, "claims.purchase-tiers", logger);
        List<MultiplierTier> territoryMultiplierTiers =
                parseMultiplierTiers(config, "upkeep.territory-multiplier-tiers", logger);

        long costPerChunkCents = config.getLong("upkeep.cost-per-chunk-cents", 2000L);
        int presenceThresholdMinutes = config.getInt("upkeep.active-population.presence-threshold-minutes", 60);
        int rollingWindowDays = config.getInt("upkeep.active-population.rolling-window-days", 7);
        double saturationConstant = config.getDouble("upkeep.active-population.saturation-constant", 8.0);
        double maxMultiplierBonus = config.getDouble("upkeep.active-population.max-multiplier-bonus", 0.5);

        return new PricingConfig(
                purchaseTiers, territoryMultiplierTiers, costPerChunkCents,
                presenceThresholdMinutes, rollingWindowDays, saturationConstant, maxMultiplierBonus);
    }

    private static List<PriceTier> parsePriceTiers(FileConfiguration config, String path, Logger logger) {
        List<PriceTier> tiers = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList(path)) {
            int maxClaims = asInt(raw.get("max-claims"));
            long priceCents = asLong(raw.get("price-cents"));
            tiers.add(new PriceTier(maxClaims, priceCents));
        }
        if (tiers.isEmpty()) {
            throw new IllegalStateException("pricing.yml: '" + path + "' has no tiers configured");
        }
        return List.copyOf(tiers);
    }

    private static List<MultiplierTier> parseMultiplierTiers(FileConfiguration config, String path, Logger logger) {
        List<MultiplierTier> tiers = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList(path)) {
            int maxClaims = asInt(raw.get("max-claims"));
            double multiplier = asDouble(raw.get("multiplier"));
            tiers.add(new MultiplierTier(maxClaims, multiplier));
        }
        if (tiers.isEmpty()) {
            throw new IllegalStateException("pricing.yml: '" + path + "' has no tiers configured");
        }
        return List.copyOf(tiers);
    }

    private static int asInt(Object raw) {
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static long asLong(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    private static double asDouble(Object raw) {
        return raw instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * The price, in cents, to purchase a realm's {@code claimCountAfterPurchase}-th
     * claim (i.e. {@code existingClaimCount + 1}) — the first tier in
     * {@link #purchaseTiers()} whose {@code maxClaims} is &gt;= that count.
     *
     * @throws IllegalStateException if {@code claimCountAfterPurchase} exceeds every
     *                                configured tier's {@code maxClaims} (the bundled
     *                                default's last tier caps at {@code Integer.MAX_VALUE},
     *                                so this only fires on a hand-edited pricing.yml)
     */
    public long purchasePriceCents(int claimCountAfterPurchase) {
        for (PriceTier tier : purchaseTiers) {
            if (claimCountAfterPurchase <= tier.maxClaims()) {
                return tier.priceCents();
            }
        }
        throw new IllegalStateException(
                "pricing.yml: no claims.purchase-tiers band covers a claim count of " + claimCountAfterPurchase);
    }

    /**
     * The territory-size upkeep multiplier for a realm currently owning
     * {@code claimCount} claims — the first tier in
     * {@link #territoryMultiplierTiers()} whose {@code maxClaims} is &gt;=
     * that count.
     *
     * @throws IllegalStateException if {@code claimCount} exceeds every configured
     *                                tier's {@code maxClaims}
     */
    public double territoryMultiplier(int claimCount) {
        for (MultiplierTier tier : territoryMultiplierTiers) {
            if (claimCount <= tier.maxClaims()) {
                return tier.multiplier();
            }
        }
        throw new IllegalStateException(
                "pricing.yml: no upkeep.territory-multiplier-tiers band covers a claim count of " + claimCount);
    }
}
