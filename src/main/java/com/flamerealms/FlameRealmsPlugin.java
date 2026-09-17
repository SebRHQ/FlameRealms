package com.flamerealms;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.command.realm.RealmActions;
import com.flamerealms.command.realm.RealmCommand;
import com.flamerealms.config.DatabaseConfig;
import com.flamerealms.config.Messages;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.config.VisualizationConfig;
import com.flamerealms.economy.VaultEconomyBridge;
import com.flamerealms.gui.ChatInputService;
import com.flamerealms.gui.GuiConfig;
import com.flamerealms.gui.GuiManager;
import com.flamerealms.gui.MainMenu;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.persistence.DatabaseManager;
import com.flamerealms.persistence.dao.LedgerDao;
import com.flamerealms.persistence.dao.PlayerWalletDao;
import com.flamerealms.persistence.dao.RealmClaimDao;
import com.flamerealms.persistence.dao.RealmDao;
import com.flamerealms.persistence.dao.RealmInviteDao;
import com.flamerealms.persistence.dao.RealmMemberActivityDao;
import com.flamerealms.persistence.dao.RealmMemberDao;
import com.flamerealms.persistence.dao.RealmRankDao;
import com.flamerealms.persistence.jdbc.JdbcLedgerDao;
import com.flamerealms.persistence.jdbc.JdbcPlayerWalletDao;
import com.flamerealms.persistence.jdbc.JdbcRealmClaimDao;
import com.flamerealms.persistence.jdbc.JdbcRealmDao;
import com.flamerealms.persistence.jdbc.JdbcRealmInviteDao;
import com.flamerealms.persistence.jdbc.JdbcRealmMemberActivityDao;
import com.flamerealms.persistence.jdbc.JdbcRealmMemberDao;
import com.flamerealms.persistence.jdbc.JdbcRealmRankDao;
import com.flamerealms.protection.ClaimProtectionService;
import com.flamerealms.protection.NexusProtectionListener;
import com.flamerealms.service.ActivityTrackingService;
import com.flamerealms.service.ClaimService;
import com.flamerealms.service.ClaimServiceImpl;
import com.flamerealms.service.EconomyService;
import com.flamerealms.service.EconomyServiceImpl;
import com.flamerealms.service.RealmService;
import com.flamerealms.service.RealmServiceImpl;
import com.flamerealms.service.TreasuryService;
import com.flamerealms.service.TreasuryServiceImpl;
import com.flamerealms.service.UpkeepService;
import com.flamerealms.visualization.ClaimVisualizationService;
import com.flamerealms.visualization.TerritoryMapService;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Main entry point for the FlameRealms plugin.
 *
 * <p>This stage wires in the persistence infrastructure ({@link DatabaseManager},
 * {@link AsyncDatabaseExecutor}), the realm cache/service layer
 * ({@link RealmCache}, {@link RealmServiceImpl}) and the {@code /realm}
 * command ({@link RealmCommand}), registered through Paper's native
 * Brigadier lifecycle event — no third-party command framework is used.
 */
public final class FlameRealmsPlugin extends JavaPlugin {

    private static final Duration ASYNC_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private DatabaseManager databaseManager;
    private AsyncDatabaseExecutor asyncDatabaseExecutor;
    private RealmCache realmCache;
    private RealmService realmService;
    private EconomyService economyService;
    private TreasuryService treasuryService;
    private ClaimService claimService;
    private ClaimVisualizationService claimVisualizationService;
    private TerritoryMapService territoryMapService;
    private ClaimProtectionService claimProtectionService;
    private ActivityTrackingService activityTrackingService;
    private UpkeepService upkeepService;
    private RealmMemberDao realmMemberDao;
    private RealmRankDao realmRankDao;
    private Messages messages;
    private PricingConfig pricingConfig;
    private RealmActions realmActions;
    private GuiConfig guiConfig;
    private ChatInputService chatInputService;
    private GuiManager guiManager;
    private Consumer<Player> openMainMenu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = Messages.load(this);
        pricingConfig = PricingConfig.load(this);

        DatabaseConfig databaseConfig = DatabaseConfig.fromConfig(getConfig(), getLogger());

        // DatabaseManager runs Flyway migrations synchronously here, during
        // startup — acceptable once, before the server is serving players.
        // It must never be constructed on the main thread again after enable.
        //
        // Construction can throw (HikariCP's PoolInitializationException if
        // it can't connect, or Flyway's FlywayException if migrations fail)
        // — both are unchecked. The plugin must never fail to enable just
        // because the database is unreachable: every command still needs to
        // register so non-database commands keep working, and
        // database-dependent ones should fail with one clear in-game message
        // instead of the whole plugin going dark. So this is the one place
        // that catches RuntimeException and degrades instead of propagating.
        try {
            databaseManager = new DatabaseManager(databaseConfig);

            // AsyncDatabaseExecutor is the sole gateway to the database from
            // here on: every DAO/service call dispatches through it, never
            // directly.
            asyncDatabaseExecutor = new AsyncDatabaseExecutor(databaseManager, databaseConfig.asyncPoolSize());
        } catch (RuntimeException e) {
            getLogger().severe("Could not connect to the database or run migrations — FlameRealms is "
                    + "starting in a degraded mode. Every database-dependent command will show a clear "
                    + "in-game error until this is fixed and the server is restarted. Cause: " + e.getMessage());
            databaseManager = null;
            asyncDatabaseExecutor = AsyncDatabaseExecutor.unavailable();
        }

        RealmDao realmDao = new JdbcRealmDao();
        realmRankDao = new JdbcRealmRankDao();
        realmMemberDao = new JdbcRealmMemberDao();
        RealmClaimDao realmClaimDao = new JdbcRealmClaimDao();
        RealmInviteDao realmInviteDao = new JdbcRealmInviteDao();

        PlayerWalletDao playerWalletDao = new JdbcPlayerWalletDao();
        LedgerDao ledgerDao = new JdbcLedgerDao(playerWalletDao, realmDao);

        realmCache = new RealmCache();
        realmService = new RealmServiceImpl(
                asyncDatabaseExecutor, realmCache, realmDao, realmRankDao, realmMemberDao,
                realmClaimDao, realmInviteDao, ledgerDao, pricingConfig);

        economyService = new EconomyServiceImpl(asyncDatabaseExecutor, playerWalletDao, ledgerDao);
        treasuryService = new TreasuryServiceImpl(
                asyncDatabaseExecutor, realmCache, realmDao, realmMemberDao, realmRankDao, ledgerDao);
        claimService = new ClaimServiceImpl(
                asyncDatabaseExecutor, realmCache, realmClaimDao, realmMemberDao, realmRankDao, ledgerDao, pricingConfig);

        VisualizationConfig visualizationConfig = VisualizationConfig.fromConfig(getConfig(), getLogger());
        claimVisualizationService = new ClaimVisualizationService(this, visualizationConfig, realmCache);
        territoryMapService = new TerritoryMapService(realmCache, visualizationConfig);

        // Real, in-world claim protection: one WorldGuard region per claimed
        // chunk, BUILD denied for everyone except the owning realm's current
        // members. See ClaimProtectionService's own class Javadoc for why
        // this is a plain, non-listener class called from RealmActions at
        // every point a realm's claims or membership change, never from the
        // com.flamerealms.service layer.
        claimProtectionService = new ClaimProtectionService(this);

        // Nexus indestructibility: cancels BlockBreakEvent on any active
        // realm's Nexus block unless the breaker holds flamerealms.admin. An
        // ordinary Bukkit Listener with no other collaborators than the
        // cache/messages it already needs, registered the same way
        // GuiManager/ChatInputService are further below.
        getServer().getPluginManager().registerEvents(
                new NexusProtectionListener(realmCache, messages), this);

        // Presence tracking and the daily territory-upkeep charge — both
        // started right away. Neither depends on the cache warm-up below
        // having completed yet: ActivityTrackingService only ever reads
        // RealmCache at tick/flush time (an empty cache just means "nothing
        // to track yet"), and UpkeepService's first cycle is a full day away.
        RealmMemberActivityDao realmMemberActivityDao = new JdbcRealmMemberActivityDao();
        activityTrackingService = new ActivityTrackingService(
                this, realmCache, realmMemberActivityDao, asyncDatabaseExecutor);
        activityTrackingService.start();
        // The callback UpkeepService invokes once a claim is released for
        // unpaid upkeep debt (see its own class Javadoc's "onClaimReleased
        // callback" section). UpkeepService already hops back onto the main
        // thread itself (via Bukkit.getScheduler().runTask(...)) before
        // invoking this callback, so the callback body here calls
        // ClaimProtectionService directly rather than re-hopping.
        upkeepService = new UpkeepService(
                this, realmCache, realmClaimDao, realmMemberActivityDao, realmDao, ledgerDao,
                asyncDatabaseExecutor, pricingConfig,
                (realmId, coordinate) -> claimProtectionService.unprotectClaim(
                        coordinate.world(), coordinate.chunkX(), coordinate.chunkZ()));
        upkeepService.start();

        // Business logic behind every /realm action, UI-framework-agnostic
        // (no Brigadier types anywhere in it) so the same instance backs both
        // the /realm command and the chest-GUI front end below — see
        // RealmActions's own class Javadoc.
        realmActions = new RealmActions(
                this, realmService, economyService, treasuryService,
                claimService, claimVisualizationService, territoryMapService, claimProtectionService,
                asyncDatabaseExecutor, realmMemberDao, realmRankDao,
                realmCache, pricingConfig, messages);

        // Chest-GUI front end for /realm menu — built on top of the same
        // RealmActions instance the command uses, so both front ends behave
        // identically. GuiConfig only needs the plugin (to save/load
        // gui.yml); ChatInputService and GuiManager are both ordinary Bukkit
        // Listeners and must be registered with the plugin manager the same
        // way any other listener in this plugin is. MainMenu itself has no
        // instance — a fresh one is built on every open — so what's kept
        // here is just a closure capturing the fixed collaborators it needs.
        guiConfig = GuiConfig.load(this);
        chatInputService = new ChatInputService(this);
        getServer().getPluginManager().registerEvents(chatInputService, this);
        guiManager = new GuiManager(this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        openMainMenu = player -> MainMenu.open(
                player, guiConfig, realmActions, realmService, realmCache, chatInputService, this, guiManager);

        // Vault bridge — OPTIONAL. FlameRealms's own economy (EconomyService)
        // works fully standalone; this exists purely so other plugins that
        // only know Vault's API can read/adjust a FlameRealms player's
        // wallet. Only register with Bukkit's ServicesManager if the Vault
        // plugin itself is actually installed — never require it to be
        // present for FlameRealms to start (see plugin.yml's softdepend).
        registerVaultEconomyBridge();

        // Warm the cache asynchronously — never block the main thread on it.
        // Any realm/claim read or write that lands before this completes
        // just sees an empty cache momentarily; this is a one-time startup
        // race the project accepts rather than blocking onEnable() to close
        // it. loadClaims(...) is included alongside loadAll(...) so
        // RealmCache's claim tracking (consumed by ClaimServiceImpl's
        // contiguity check, /realm claim's price preview and
        // TerritoryMapService) isn't left empty after every restart.
        asyncDatabaseExecutor.<Void>submit(connection -> {
            try {
                realmCache.loadAll(connection);
                realmCache.loadClaims(connection);
                realmCache.loadNexusLocations(connection);
                return null;
            } catch (SQLException e) {
                throw new RuntimeException("Failed to warm the realm cache", e);
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                getLogger().severe("Failed to warm the realm cache: " + error.getMessage());
            } else {
                getLogger().info("Realm cache warmed.");
            }
        });

        this.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                event -> event.registrar().register(buildRealmCommand(), "Realm management"));

        getLogger().info("FlameRealms has been enabled.");
    }

    /**
     * Registers {@link VaultEconomyBridge} as the server's Vault
     * {@link Economy} provider, but only if the Vault plugin itself is
     * actually installed on this server — Vault is a soft dependency
     * ({@code plugin.yml}'s {@code softdepend}), never a hard requirement,
     * so FlameRealms must start cleanly with it entirely absent. If Vault
     * isn't present, this just logs at info level and returns; it never
     * throws.
     */
    private void registerVaultEconomyBridge() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault not found — Vault economy bridge disabled.");
            return;
        }

        getServer().getServicesManager().register(
                Economy.class, new VaultEconomyBridge(economyService), this, ServicePriority.Normal);
        getLogger().info("Vault found — registered FlameRealms as the Vault economy provider.");
    }

    private LiteralCommandNode<CommandSourceStack> buildRealmCommand() {
        return new RealmCommand(this, realmService, messages, realmActions, openMainMenu).build();
    }

    @Override
    public void onDisable() {
        // Stop the repeating tasks first, then flush ActivityTrackingService's
        // in-memory accumulator synchronously — this is the one sanctioned
        // place that's allowed to block the main thread on a database future
        // (see its own class Javadoc) — strictly BEFORE the executor
        // shutdown below closes the pool that flush depends on.
        if (upkeepService != null) {
            upkeepService.stop();
        }
        if (activityTrackingService != null) {
            activityTrackingService.stop();
            activityTrackingService.flushNow();
        }

        // Stop accepting new DB work and drain in-flight work BEFORE closing
        // the pool, so nothing is left reaching for a connection that no
        // longer exists.
        if (asyncDatabaseExecutor != null) {
            asyncDatabaseExecutor.shutdown(ASYNC_SHUTDOWN_TIMEOUT);
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("FlameRealms has been disabled.");
    }
}
