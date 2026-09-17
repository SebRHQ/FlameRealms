# FlameRealms — Implemented So Far

Status snapshot of what actually exists and works in this repo, as of milestone **M2 (Economy foundation)**. See [PROJECT.md](PROJECT.md) / [TECHNICAL_SPEC.md](TECHNICAL_SPEC.md) for the full long-term design; this file only tracks what's built.

## M0 — Bootstrap

- Gradle project (Kotlin DSL), Java 25 toolchain, Gradle 8.10 wrapper.
- Shadow plugin (`com.gradleup.shadow` 8.3.11) shades HikariCP, Flyway, and the MariaDB JDBC driver into `com.flamerealms.libs.*` to avoid classpath collisions with other plugins.
- `plugin.yml`: hard `depend: [WorldGuard]`, `softdepend: [Vault, PlaceholderAPI, ItemsAdder, CustomNamePlates, WorldEdit]`.
- `config.yml`: `database:` (host/port/db/user/pass/pool-size) and `async:` (executor thread-pool size) blocks.
- CI: `.github/workflows/build.yml` (Temurin 25, `./gradlew build`).
- [`DatabaseManager`](src/main/java/com/flamerealms/persistence/DatabaseManager.java) — owns the HikariCP pool, runs Flyway migrations synchronously once at startup.
- [`AsyncDatabaseExecutor`](src/main/java/com/flamerealms/persistence/AsyncDatabaseExecutor.java) — the **only** gateway to the database from anywhere in the plugin. Every DAO/service call dispatches through it; nothing ever touches JDBC on the Paper main thread.

## M1 — Realm core

**Schema** ([`V1__realm_core.sql`](src/main/resources/db/migration/V1__realm_core.sql)): `realms`, `realm_ranks`, `realm_members` (with `uq_player_one_realm` enforcing "one realm per player" at the DB level).

**Domain** ([`domain/`](src/main/java/com/flamerealms/domain/)): `Realm`, `RealmRank`, `RealmMember`, `RealmPermission` (bitmask enum: INVITE, KICK, CLAIM, UNCLAIM, DEPOSIT, WITHDRAW, MANAGE_RANKS, DIPLOMACY, DECLARE_WAR, MANAGE_NEXUS).

**Persistence**: `RealmDao`/`RealmRankDao`/`RealmMemberDao` interfaces + JDBC implementations (plain `PreparedStatement`, no ORM). `UuidCodec` for `BINARY(16)` UUID storage.

**Service** ([`RealmServiceImpl`](src/main/java/com/flamerealms/service/RealmServiceImpl.java)) + [`RealmCache`](src/main/java/com/flamerealms/cache/RealmCache.java) (write-through, authoritative-for-reads, populated after commit — never optimistically):
- `createRealm` — seeds 3 default ranks (Leader/Officer/Member) + leader membership, one transaction.
- `disbandRealm` — leader-only; deletes the realm row (cascades to ranks/members).
- `invite`/`join` — invites are **in-memory only** (no `realm_invites` table yet), a deliberate M1 simplification.
- `leave` — rejects the realm's leader (must disband instead).
- `setRank` — requires `MANAGE_RANKS` on the actor.

**Commands** (`/realm ...`, Paper native Brigadier, no third-party command framework): `create <name>`, `info [name]`, `invite <player>`, `join <name>`, `leave`, `disband`.

## M2 — Economy foundation

**Schema** ([`V2__economy.sql`](src/main/resources/db/migration/V2__economy.sql)): `realms.balance_cents`/`upkeep_debt_cents` columns, `player_wallets` table, `transactions` append-only audit log.

**Domain**: [`Money`](src/main/java/com/flamerealms/domain/Money.java) (long cents, never double/float — the *only* money type used anywhere except one sanctioned Vault-bridge boundary), `PlayerWallet`, `TransactionCategory` (FAUCET/SINK/TRANSFER), `LedgerEntity` (PLAYER/REALM/SERVER), `TransactionRecord`.

**The ledger helper** ([`LedgerDao`](src/main/java/com/flamerealms/persistence/dao/LedgerDao.java)/`JdbcLedgerDao`) — the single mechanism through which *any* balance mutation must go: `recordAndApplyToPlayer`/`recordAndApplyToRealm` atomically combine a conditional balance update (`tryAdjustBalance`, DB-level "can't go negative" check) with an audit-log insert, in one transaction. No code anywhere else is allowed to touch `balance_cents` directly.

**Services**:
- [`EconomyService`](src/main/java/com/flamerealms/service/EconomyService.java) — personal wallet: `balanceOf`, `deposit` (FAUCET credit), `withdraw` (SINK debit, returns `false` on insufficient funds rather than throwing).
- [`TreasuryService`](src/main/java/com/flamerealms/service/TreasuryService.java) — realm treasury: `balanceOf`, `deposit`/`withdraw` (player ↔ realm TRANSFER, fixed lock ordering: player wallet before realm balance). `withdraw` additionally requires the actor's rank to hold `WITHDRAW`.

**Vault bridge** ([`VaultEconomyBridge`](src/main/java/com/flamerealms/economy/VaultEconomyBridge.java)) — implements `net.milkbowl.vault.economy.Economy` over `EconomyService` (personal wallets only, no realm treasury concept in Vault's API). Registered with Bukkit's `ServicesManager` **only if** the Vault plugin is actually present at runtime — never required. `VaultAPI` pulled via JitPack (`com.github.MilkBowl:VaultAPI:1.7.1`) since it isn't on Maven Central. This is the *one* place a `double` is allowed to represent money.

**Commands**: `/realm balance` (personal + realm treasury, fetched concurrently), `/realm deposit <amount>`, `/realm withdraw <amount>` (amounts parsed with `BigDecimal`, never `Double.parseDouble`; rejects non-positive amounts or more than 2 decimal places rather than silently rounding).

## Player-facing messages

[`messages.yml`](src/main/resources/messages.yml) + [`Messages`](src/main/java/com/flamerealms/config/Messages.java) — every player-facing string used by `/realm` (usage, all success/error messages, `info`/`balance` layouts) lives here as a full MiniMessage template (own colors/formatting included), copied to `plugins/FlameRealms/messages.yml` on first run and never overwritten afterward. No message text is hardcoded in `RealmCommand.java` anymore. **No reload command exists yet** — editing `messages.yml` requires a server restart to take effect.

## Permission nodes

Every `/realm` subcommand is gated by a Bukkit permission node via Brigadier's `.requires(...)`, defined in `plugin.yml`'s `permissions:` block — all default `true` (everyone can use them) except `flamerealms.admin` (`default: op`, reserved for future admin-only commands, not used by anything yet):

`flamerealms.command.create`, `.info`, `.invite`, `.join`, `.leave`, `.disband`, `.balance`, `.deposit`, `.withdraw`, plus the umbrella `flamerealms.command.*`.

A sender lacking a node just gets Brigadier's normal "unknown command" behavior for that subcommand — no custom message. This is independent of, and unrelated to, `RealmPermission` (the per-realm-rank bitmask system above) — a server admin can now restrict *who may use FlameRealms commands at all* (e.g. via LuckPerms), separately from what a player can do *within a realm they've already joined*.

## Tests

32 tests total across 6 files:
- Unit (fake in-memory DAOs, no DB/Docker needed, run via `./gradlew test`): `RealmServiceImplTest` (8), `TreasuryServiceImplTest` (6), `EconomyServiceImplTest` (5), `MoneyTest` (6).
- Integration (`@Tag("integration")`, Testcontainers/MariaDB, excluded from `test`, run via `./gradlew integrationTest`): `JdbcRealmDaoIT` (3 — DB-level unique-constraint checks), `EconomyLedgerIT` (4 — concurrent-withdrawal non-negative-balance guarantee, ledger-invariant checks).

## Bugs found and fixed via live-server testing (not caught by the test suite)

These only surfaced running the actual shaded jar on a real Paper/Purpur server — all three are classloader-isolation issues that a single-classloader test/sandbox JVM can't reproduce:

1. **Shaded JDBC driver invisible to `DriverManager`** — `META-INF/services/java.sql.Driver` still listed the pre-relocation class name. Fixed with `mergeServiceFiles()` in `build.gradle.kts`'s `shadowJar` block.
2. **HikariCP couldn't find the driver even after fix #1** — Bukkit/Paper's per-plugin classloader isolation means `DriverManager`'s ServiceLoader lookup (keyed off the calling thread's context classloader) still can't see it. Fixed by explicitly setting `hikariConfig.setDriverClassName("com.flamerealms.libs.mariadb.Driver")` in `DatabaseManager`, so HikariCP loads the class directly instead of going through `DriverManager`.
3. **Flyway found "0 migrations"** — same root cause, different library: `Flyway.configure()` with no argument scans the *calling thread's* context classloader, not the plugin's. Fixed by passing `DatabaseManager.class.getClassLoader()` explicitly to `Flyway.configure(...)`.
4. **`com.gradleup.shadow:8.3.5`'s bundled ASM couldn't read Java 25 class files** (`Unsupported class file major version 69`), breaking the `shadowJar` task outright. Bumped to `8.3.11`.

## Known gaps (deliberate, not oversights)

- No `/realm setrank` command yet (the service method exists; no command surface).
- No admin "give money" command — `EconomyService.deposit()` has no command wired to it, so the deposit/withdraw happy path can only be exercised via the unit tests, not live in-game, until one is added.
- Invites don't survive a plugin restart (in-memory only — see M1 above).
- No `/reload`-equivalent for `messages.yml`/`config.yml` — changes need a server restart.
- Claims, shields, wars, contracts, projects, realm-to-realm transfers, diplomacy: out of scope until their own milestones (M3+).
