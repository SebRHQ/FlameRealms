package com.flamerealms.economy;

import com.flamerealms.domain.Money;
import com.flamerealms.service.EconomyService;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Bridges FlameRealms's own {@link EconomyService} onto Vault's
 * {@link Economy} API, purely so other plugins that only know Vault's
 * interface can interoperate with a FlameRealms player's wallet balance.
 *
 * <p>This bridge is entirely OPTIONAL — FlameRealms's own economy works
 * fully standalone without it. Registration only happens from
 * {@code FlameRealmsPlugin#onEnable} if the Vault plugin is actually
 * installed; see that method. FlameRealms's own code never goes through
 * this class or through Vault at all — it always talks to
 * {@link EconomyService}/{@link Money} directly.
 *
 * <p><b>Player wallets only.</b> Vault's {@link Economy} interface has no
 * concept of a realm treasury — it is a flat "player has a balance" model —
 * so this bridges {@link EconomyService} only. {@code TreasuryService} (realm
 * treasuries) is simply not reachable through Vault and is not referenced
 * here at all.
 *
 * <p><b>The one and only place in this project a {@code double} is allowed
 * to represent money.</b> Every other class represents an amount as
 * {@link Money} (a whole number of cents) — see that class's Javadoc, which
 * calls this class out by name as its sole exception. Vault's
 * {@link Economy} contract is fixed at {@code double} and cannot be changed
 * here, so every method below converts a Vault {@code double} to
 * {@link Money} (or back) immediately at this boundary, via {@link #toMoney}
 * / {@link #toDouble}, and touches a raw {@code double} nowhere else. If
 * money needs representing anywhere else in the project, that call site
 * should be reaching for {@link Money}, not copying the pattern here.
 *
 * <p><b>Blocking is deliberate, and confined to this class.</b>
 * {@link EconomyService} is asynchronous end-to-end: every method returns a
 * {@link CompletableFuture}, and the rest of the project is under strict
 * instructions never to block on one of those futures from the Paper main
 * thread (see that interface's Javadoc). Vault's {@link Economy} methods,
 * however, are synchronous by contract — they return {@code boolean} /
 * {@code double} directly, with no async variant, since Vault predates
 * {@link CompletableFuture} entirely. There is no way to implement this
 * interface without blocking somewhere. That is acceptable ONLY here,
 * because Vault's own documented contract is that a plugin looking up
 * another plugin's economy calls these methods off the main thread in
 * practice for most callers — the same expectation any synchronous
 * cross-plugin DB-backed economy call would carry, bridge or not. Every
 * method below is kept as thin as possible: convert the arguments, dispatch
 * to {@link EconomyService}, block on the single resulting future, convert
 * the result back — nothing else happens in between.
 */
public final class VaultEconomyBridge implements Economy {

    private static final String CURRENCY_SINGULAR = "Dollar";
    private static final String CURRENCY_PLURAL = "Dollars";
    private static final int FRACTIONAL_DIGITS = 2;
    private static final String VAULT_REASON = "Vault economy bridge";

    private final EconomyService economyService;

    public VaultEconomyBridge(EconomyService economyService) {
        this.economyService = economyService;
    }

    // ----- Plugin / currency metadata -----

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "FlameRealms";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return FRACTIONAL_DIGITS;
    }

    @Override
    public String format(double amount) {
        return toMoney(amount).toString();
    }

    @Override
    public String currencyNamePlural() {
        return CURRENCY_PLURAL;
    }

    @Override
    public String currencyNameSingular() {
        return CURRENCY_SINGULAR;
    }

    // ----- Accounts -----
    // A FlameRealms wallet needs no explicit "create account" step — it
    // auto-vivifies to a zero balance on a player's first deposit/withdraw
    // (PlayerWalletDao#ensureExists) and reads as Money.ZERO before that
    // (EconomyService#balanceOf). Every player is therefore always
    // considered to already have an account, and "creating" one is a no-op.

    @Override
    public boolean hasAccount(String playerName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return true;
    }

    // ----- Balance reads -----
    // FlameRealms has one global balance per player — the world-qualified
    // overloads below ignore worldName and delegate to the global one.

    @Override
    public double getBalance(String playerName) {
        return getBalance(legacyOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return toDouble(economyService.balanceOf(player.getUniqueId()).join());
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    // ----- Balance mutation -----

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(legacyOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return applyDelta(player.getUniqueId(), amount, true);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(legacyOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return applyDelta(player.getUniqueId(), amount, false);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // ----- Banks — unsupported; see hasBankSupport() -----

    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    // ----- The double <-> Money conversion boundary (see class Javadoc) -----

    private static Money toMoney(double amount) {
        return Money.ofCents(Math.round(amount * 100.0d));
    }

    private static double toDouble(Money amount) {
        return amount.cents() / 100.0d;
    }

    /**
     * Resolves a legacy, pre-UUID Vault caller's player name to an
     * {@link OfflinePlayer} purely to recover a {@link UUID} — FlameRealms
     * itself never calls these {@code String}-based overloads, only the
     * {@link OfflinePlayer}-based ones.
     */
    private static OfflinePlayer legacyOfflinePlayer(String playerName) {
        return Bukkit.getOfflinePlayer(playerName);
    }

    /**
     * The one shared body behind every {@code withdrawPlayer}/
     * {@code depositPlayer} overload above: converts the Vault
     * {@code double} to {@link Money}, dispatches a single
     * {@link EconomyService#deposit}/{@link EconomyService#withdraw} call,
     * blocks on the resulting future (see class Javadoc), and translates the
     * outcome back into an {@link EconomyResponse}.
     */
    private EconomyResponse applyDelta(UUID player, double amount, boolean withdraw) {
        if (amount < 0.0d) {
            return failure(amount, "Amount cannot be negative");
        }

        Money delta = toMoney(amount);
        if (!delta.isPositive()) {
            // Either amount was exactly 0.0d, or it was a small-but-positive
            // value (e.g. 0.001, from another plugin's own percentage/fee
            // calculation) that rounds down to zero cents in toMoney().
            // Either way this is effectively a no-op: EconomyService rejects
            // a non-positive Money outright via requirePositive(), and that
            // check runs synchronously before any future is even created, so
            // it would otherwise escape this method as an uncaught
            // IllegalArgumentException instead of a graceful
            // EconomyResponse. Short-circuit here instead.
            double balance = toDouble(economyService.balanceOf(player).join());
            return new EconomyResponse(0.0d, balance, ResponseType.SUCCESS, null);
        }

        CompletableFuture<Boolean> future = withdraw
                ? economyService.withdraw(player, delta, VAULT_REASON)
                : economyService.deposit(player, delta, VAULT_REASON);

        boolean success;
        try {
            success = future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return failure(amount, cause.getMessage());
        }

        if (!success) {
            return failure(amount, withdraw ? "Insufficient funds" : "Deposit failed");
        }

        double newBalance = toDouble(economyService.balanceOf(player).join());
        return new EconomyResponse(amount, newBalance, ResponseType.SUCCESS, null);
    }

    private static EconomyResponse failure(double amount, String message) {
        return new EconomyResponse(amount, 0.0d, ResponseType.FAILURE, message);
    }

    private static EconomyResponse notImplemented() {
        return new EconomyResponse(0.0d, 0.0d, ResponseType.NOT_IMPLEMENTED, "FlameRealms has no bank support");
    }
}
