// FlameRealms — M2 implementation workflow (Economy foundation)
//
// Invoke from a fresh chat (no prior conversation context required) with:
//   Workflow({ scriptPath: "workflows/flamerealms-m2-economy.js" })
//
// Prerequisite: the M0+M1 workflow (workflows/flamerealms-m0-m1.js) must already have
// run successfully — this workflow builds directly on FlameRealmsPlugin, DatabaseManager,
// AsyncDatabaseExecutor, RealmCache, RealmService/RealmServiceImpl, and the V1 migration.
// It does NOT re-create AsyncDatabaseExecutor (that already exists from M0) even though an
// earlier planning note suggested it belonged at M2 — it was moved to M0 because M1's
// RealmService already needed it; M2 just builds new services on top of the same executor.
//
// Scope: TECHNICAL_SPEC.md v0.4, milestone M2 (Economy foundation) ONLY — player wallets,
// realm treasury balance, the shared audit-ledger helper, deposit/withdraw between a player
// and their realm's treasury, and an optional Vault bridge. It does NOT implement claims,
// shields, wars, contracts, projects, or realm-to-realm transfers (war stake etc.) — those
// arrive with their own later milestones and need context (claims, an opposing realm, a war)
// that doesn't exist yet.
//
// Every stage prompt is self-contained: no agent is asked to read PROJECT.md or
// TECHNICAL_SPEC.md (thousands of lines combined) — the exact schema, interfaces, and
// constraints each stage needs are embedded directly, to keep token usage down.

export const meta = {
  name: 'flamerealms-m2-economy',
  description: 'Implement FlameRealms M2 — player wallets, realm treasury, the shared audit-ledger transaction helper, and an optional Vault bridge',
  whenToUse: 'Run this after the M0+M1 workflow has completed successfully, to add the economy layer (player_wallets, realms.balance_cents, transactions ledger, EconomyService, TreasuryService, /realm balance|deposit|withdraw) per TECHNICAL_SPEC.md v0.4 milestone M2.',
  phases: [
    { title: 'Schema & domain', detail: 'V2 migration, Money/PlayerWallet/transaction domain types' },
    { title: 'DAO & ledger helper', detail: 'PlayerWalletDao, RealmDao balance methods, LedgerDao.recordAndApply' },
    { title: 'Services', detail: 'EconomyService (player wallet), TreasuryService (realm treasury)' },
    { title: 'Bridge & commands', detail: 'Optional VaultEconomyBridge + /realm balance|deposit|withdraw' },
    { title: 'Tests', detail: 'Unit tests + Testcontainers concurrency/ledger-invariant tests' },
    { title: 'Verify', detail: 'Build, fix compile errors, produce final report' },
  ],
}

const PACKAGE = 'com.flamerealms'

// ---------------------------------------------------------------------------
// Stage 1 — Schema & domain types
// ---------------------------------------------------------------------------
phase('Schema & domain')
const schemaDomain = await agent(`
Continuing work on the existing FlameRealms plugin (package ${PACKAGE}) in the current
directory. M0+M1 already exist: FlameRealmsPlugin, DatabaseManager, AsyncDatabaseExecutor,
RealmCache, RealmService/RealmServiceImpl, and Flyway migration V1__realm_core.sql (realms,
realm_ranks, realm_members). Do not modify those — only add to them.

Money in this project is ALWAYS represented as a long integer number of cents. Never use
double or float for money anywhere in the code you write, except later, at one single
narrow boundary (a future Vault bridge) which is NOT this stage's job.

1. Flyway migration src/main/resources/db/migration/V2__economy.sql, exactly:

ALTER TABLE realms
  ADD COLUMN balance_cents BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN upkeep_debt_cents BIGINT NOT NULL DEFAULT 0;

CREATE TABLE player_wallets (
  player_uuid   BINARY(16) NOT NULL PRIMARY KEY,
  balance_cents BIGINT NOT NULL DEFAULT 0,
  updated_at    DATETIME NOT NULL
);

CREATE TABLE transactions (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ts            DATETIME NOT NULL,
  category      ENUM('FAUCET','SINK','TRANSFER') NOT NULL,
  reason        VARCHAR(32) NOT NULL,
  source_type   ENUM('PLAYER','REALM','SERVER') NOT NULL,
  source_id     VARCHAR(36) NULL,
  target_type   ENUM('PLAYER','REALM','SERVER') NULL,
  target_id     VARCHAR(36) NULL,
  amount_cents  BIGINT NOT NULL,
  metadata      JSON NULL,
  INDEX idx_ts (ts),
  INDEX idx_reason (reason)
);

(transactions is an append-only AUDIT LOG, never the source of truth for a live balance —
balances always live on realms.balance_cents / player_wallets.balance_cents and are read
directly, never derived by summing this table.)

2. Domain records (${PACKAGE}.domain package), plain immutable Java records/enums, no
   Bukkit or JDBC types:
   - Money — a small immutable wrapper around a single 'long cents' field. Static factory
     Money.ofCents(long), a ZERO constant, add(Money)/subtract(Money) returning new Money
     instances, isNegative()/isPositive(), compareTo-style comparison, and a toString() that
     formats as a dollar amount (e.g. cents=1234 -> "$12.34"). This is the ONLY type any
     other class in the project should use to represent an amount of money going forward —
     say so in a Javadoc comment on the class.
   - PlayerWallet(UUID playerUuid, long balanceCents, Instant updatedAt)
   - TransactionCategory — enum FAUCET, SINK, TRANSFER (matches the SQL ENUM above exactly).
   - LedgerEntity — enum PLAYER, REALM, SERVER (matches source_type/target_type above).
   - TransactionRecord — a record capturing one row of the transactions table: id (long),
     ts (Instant), category (TransactionCategory), reason (String), sourceType
     (LedgerEntity), sourceId (String, nullable), targetType (LedgerEntity, nullable),
     targetId (String, nullable), amountCents (long), metadata (String, nullable — store
     as a raw JSON string for now, no JSON library dependency needed yet).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
confirm the exact Money API you settled on (method names/signatures) since every later
stage in this workflow depends on it being stable.
`, { label: 'schema-domain', phase: 'Schema & domain' })

// ---------------------------------------------------------------------------
// Stage 2 — DAO layer + the shared ledger transaction helper
// ---------------------------------------------------------------------------
phase('DAO & ledger helper')
const daoLedger = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Stage 1 added V2__economy.sql and the Money/PlayerWallet/TransactionCategory/LedgerEntity/
TransactionRecord domain types. Its report:

"""
${typeof schemaDomain === 'string' ? schemaDomain : JSON.stringify(schemaDomain)}
"""

This stage builds the single most important piece of the economy layer: the shared helper
that makes "balance mutation + audit ledger entry commit together, in the same database
transaction, or neither happens" structurally impossible to get wrong. Every later economy
operation in this and every future milestone must go through it — do not let any future
stage bypass it by writing its own ad-hoc balance UPDATE + separate ledger INSERT.

1. ${PACKAGE}.persistence.dao.PlayerWalletDao (interface) + JdbcPlayerWalletDao (impl,
   ${PACKAGE}.persistence.jdbc package), every method taking a java.sql.Connection as its
   first parameter (same pattern as M1's DAOs):
   - findByPlayer(Connection, UUID) -> Optional<PlayerWallet>
   - ensureExists(Connection, UUID) -> creates a zero-balance row if none exists yet
     (an upsert / INSERT ... ON DUPLICATE KEY UPDATE that changes nothing if already
     present is fine), so a player's very first economy interaction doesn't need special
     handling anywhere else.
   - tryAdjustBalance(Connection, UUID player, long deltaCents) -> boolean — a CONDITIONAL
     update: 'UPDATE player_wallets SET balance_cents = balance_cents + ?, updated_at = ?
     WHERE player_uuid = ? AND balance_cents + ? >= 0', returning true only if a row was
     actually affected. This is how "can't go negative" is enforced — never read-then-write.

2. Add to the EXISTING RealmDao interface/JdbcRealmDao implementation from M1 (do not
   create a separate RealmTreasuryDao — balance_cents is just a column on the realms table
   these two new methods belong on):
   - findBalance(Connection, long realmId) -> long (balance_cents)
   - tryAdjustBalance(Connection, long realmId, long deltaCents) -> boolean — same
     conditional-update pattern as PlayerWalletDao's version above, against
     realms.balance_cents.

3. ${PACKAGE}.persistence.dao.LedgerDao (interface) + JdbcLedgerDao (impl) — this IS the
   shared helper. At minimum:
   - insertEntry(Connection, TransactionRecord) -> long (generated id) — a plain INSERT
     into transactions.
   - recordAndApplyToPlayer(Connection conn, UUID player, long deltaCents,
     TransactionCategory category, String reason, LedgerEntity counterpartyType,
     String counterpartyId) -> boolean:
       ensures the player's wallet row exists (PlayerWalletDao.ensureExists), attempts
       tryAdjustBalance(conn, player, deltaCents); if it returns false (would go negative),
       return false immediately WITHOUT inserting any transactions row — the caller is
       expected to roll back the whole surrounding transaction in that case. If it
       succeeds, insert exactly one TransactionRecord (source/target populated correctly
       depending on whether deltaCents is positive or negative relative to the player —
       document your convention clearly) in the SAME connection, then return true.
   - recordAndApplyToRealm(Connection conn, long realmId, long deltaCents,
     TransactionCategory category, String reason, LedgerEntity counterpartyType,
     String counterpartyId) -> boolean — identical shape, against realms.balance_cents via
     RealmDao.tryAdjustBalance.
   Add a class-level Javadoc/comment stating explicitly: "No code anywhere in this project
   may update player_wallets.balance_cents or realms.balance_cents without going through
   one of this class's recordAndApply* methods, in the same transaction as the ledger
   insert. A balance change with no corresponding transactions row is a bug, not an
   acceptable shortcut."

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
the exact method signatures for PlayerWalletDao, the two new RealmDao methods, and
LedgerDao (the next stage's EconomyService/TreasuryService depend on these being stable).
`, { label: 'dao-ledger', phase: 'DAO & ledger helper' })

// ---------------------------------------------------------------------------
// Stage 3 — EconomyService (player wallet) + TreasuryService (realm treasury)
// ---------------------------------------------------------------------------
phase('Services')
const services = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
PlayerWalletDao, the two new RealmDao methods, and LedgerDao (with recordAndApplyToPlayer /
recordAndApplyToRealm) now exist. Their report:

"""
${typeof daoLedger === 'string' ? daoLedger : JSON.stringify(daoLedger)}
"""

Implement, both dispatching every DB-touching call through the EXISTING
AsyncDatabaseExecutor.submit(...) from M0 (never on the calling/main thread — this rule is
unchanged from M0/M1 and applies here just as strictly):

1. ${PACKAGE}.service.EconomyService (interface) + EconomyServiceImpl — player wallet only:
   - CompletableFuture<Money> balanceOf(UUID player)
   - CompletableFuture<Boolean> deposit(UUID player, Money amount, String reason) — a
     FAUCET-category credit (source=SERVER, target=PLAYER:player) via
     LedgerDao.recordAndApplyToPlayer with a positive delta. Should essentially always
     succeed (crediting money can't fail on insufficient funds) — return false only on a
     genuine unexpected DB failure.
   - CompletableFuture<Boolean> withdraw(UUID player, Money amount, String reason) — a
     SINK-category debit (source=PLAYER:player, target=SERVER) with a negative delta;
     returns false (not an exception) if the player doesn't have enough money — this is an
     ordinary, expected outcome the caller should handle gracefully, not a failure mode.
   Reject a non-positive 'amount' (zero or negative) up front with an IllegalArgumentException
   before ever dispatching to the executor.

2. ${PACKAGE}.service.TreasuryService (interface) + TreasuryServiceImpl — realm treasury,
   including the two cross-entity transfers PROJECT.md's economy explicitly needs between a
   player and their own realm's treasury:
   - CompletableFuture<Money> balanceOf(long realmId)
   - CompletableFuture<Boolean> deposit(long realmId, UUID actor, Money amount) — a
     PLAYER -> REALM transfer. Inside ONE AsyncDatabaseExecutor.submit call (one
     transaction, one Connection): first LedgerDao.recordAndApplyToPlayer(actor,
     -amount.cents(), TRANSFER, "REALM_DEPOSIT", REALM, String.valueOf(realmId)) — if that
     returns false (actor doesn't have the funds), abort/rollback and complete the future
     with false; otherwise LedgerDao.recordAndApplyToRealm(realmId, +amount.cents(),
     TRANSFER, "REALM_DEPOSIT", PLAYER, actor.toString()). Lock/adjust the player's wallet
     BEFORE the realm's balance, consistently, in every method in this class, to match the
     project's fixed-lock-ordering rule for cross-entity operations.
   - CompletableFuture<Boolean> withdraw(long realmId, UUID actor, Money amount) — the
     reverse, REALM -> PLAYER transfer, and ALSO a permission check: look up the actor's
     rank for this realm (via whatever RealmCache/RealmService exposes from M1) and require
     the WITHDRAW bit from RealmPermission (M1) — if the actor lacks it, fail the future
     immediately with a clear domain exception, without touching the database at all. If
     permitted: recordAndApplyToRealm(realmId, -amount.cents(), ...) first (abort with
     false if the realm treasury can't cover it), then recordAndApplyToPlayer(actor,
     +amount.cents(), ...).
   Both deposit and withdraw reject a non-positive 'amount' up front, same as EconomyService.

Realm-to-realm transfers (war stake, trade) and any other economy operation that needs a
second realm, a claim, or a war are explicitly OUT OF SCOPE for this milestone — do not add
them; they need context (an opposing realm, an active war) that doesn't exist in the
project yet.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
the exact CompletableFuture-based signatures you implemented for both services (the next
stage's Vault bridge and commands, and the stage after that's tests, both depend on these).
`, { label: 'services', phase: 'Services' })

// ---------------------------------------------------------------------------
// Stage 4 — Vault bridge + commands (independent of each other, both depend only on stage 3)
// ---------------------------------------------------------------------------
phase('Bridge & commands')
const [vaultBridge, commands] = await parallel([
  () => agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
EconomyService/TreasuryService now exist. Their report:

"""
${typeof services === 'string' ? services : JSON.stringify(services)}
"""

Add an OPTIONAL Vault economy bridge — FlameRealms's own economy already works fully
standalone without this; it exists purely so other plugins that only know Vault's API can
interoperate with FlameRealms player balances.

1. Add compileOnly dependency on the Vault API to build.gradle.kts if it isn't already
   present (search Maven Central / JitPack for a 'net.milkbowl.vault:VaultAPI' artifact,
   or vault's own published API artifact — use whichever you can confirm actually resolves;
   note in your summary exactly which coordinate/version you used).

2. ${PACKAGE}.economy.VaultEconomyBridge implementing net.milkbowl.vault.economy.Economy,
   backed by EconomyService (player wallets ONLY — Vault's Economy interface has no concept
   of a realm treasury, so this bridges personal wallets, not realm balances). This is the
   ONE and ONLY place in the entire project where a 'double' is allowed to represent money
   — convert to/from Money/long-cents exactly at this boundary and nowhere else. Say so in
   a class-level comment. Since Vault's Economy interface methods are synchronous (they
   return boolean/double directly, not a CompletableFuture), you will need to block on the
   CompletableFuture from EconomyService inside these bridge methods — that is acceptable
   ONLY here, because Vault itself calls these methods off the main thread in practice for
   most callers; note this tradeoff explicitly in a comment rather than silently hiding it,
   and keep the blocking bridge methods as thin as possible.

3. In FlameRealmsPlugin.onEnable(), AFTER Vault/EconomyService are both ready, detect
   whether the Vault plugin is actually installed (getServer().getPluginManager()
   .getPlugin("Vault") != null) and only then register VaultEconomyBridge via Bukkit's
   ServicesManager (getServer().getServicesManager().register(Economy.class, bridge, this,
   ServicePriority.Normal)). If Vault isn't present, skip this entirely and log at info
   level that the Vault bridge is disabled — do not throw, do not require Vault to be
   present for the plugin to start.

4. Update plugin.yml's softdepend list to include Vault if it isn't already there from the
   scaffold stage of the M0+M1 workflow.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, the
exact Vault API dependency coordinate you used, and confirm the plugin still starts
correctly with Vault absent (i.e. the registration code path is properly conditional).
`, { label: 'vault-bridge', phase: 'Bridge & commands' }),

  () => agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
EconomyService/TreasuryService now exist. Their report:

"""
${typeof services === 'string' ? services : JSON.stringify(services)}
"""

Add three subcommands to the EXISTING ${PACKAGE}.command.realm.RealmCommand from the M0+M1
workflow (do not create a second command class or re-register /realm — extend the same
Brigadier command tree that already has create/info/invite/accept/leave/disband):

  - balance   — shows the executing player's personal wallet balance
                (EconomyService.balanceOf(player)) AND, if they're in a realm
                (RealmService.getByPlayer / RealmCache), that realm's treasury balance
                (TreasuryService.balanceOf(realmId)) — both in one message, matching
                PROJECT.md's convention of showing "Personal Balance" and "Realm Treasury"
                together.
  - deposit <amount> — parses <amount> as a decimal dollar string (e.g. "500" or "12.50")
                using java.math.BigDecimal (NEVER Double.parseDouble/float — this project
                never uses floating point for money anywhere), rejects non-positive or
                unparseable input with a clear message, converts to cents
                (amount.multiply(BigDecimal.valueOf(100)) rounded/scaled to an exact long,
                reject if the input has more than 2 decimal places rather than silently
                rounding), requires the player be in a realm, calls
                TreasuryService.deposit(realmId, player, Money.ofCents(...)).
  - withdraw <amount> — same parsing rules, calls TreasuryService.withdraw(realmId, player,
                amount) — if it fails, the message should distinguish "you don't have
                permission" from "the treasury doesn't have enough funds" as clearly as the
                service layer's exceptions/return value let you.

Every one of these calls returns a CompletableFuture (except balance's two calls, which
race no shared mutable state, so you may combine them with CompletableFuture.allOf or
similar) — the command handler must NOT block the main thread on any of them. Use
.thenAccept(...)/.exceptionally(...) and hop back to the main thread with
Bukkit.getScheduler().runTask(plugin, () -> ...) before sending any message to the player,
exactly like every other subcommand in this project. Use Adventure/MiniMessage for all
player-facing text, matching the existing subcommands' style.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created/edited,
and confirm all three subcommands correctly defer Bukkit API access to the main thread.
`, { label: 'economy-commands', phase: 'Bridge & commands' }),
])

// ---------------------------------------------------------------------------
// Stage 5 — Tests
// ---------------------------------------------------------------------------
phase('Tests')
const tests = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
EconomyService/TreasuryService, the Vault bridge, and the balance/deposit/withdraw
commands all now exist. Reports:

Services: """${typeof services === 'string' ? services : JSON.stringify(services)}"""
DAO/ledger: """${typeof daoLedger === 'string' ? daoLedger : JSON.stringify(daoLedger)}"""
Vault bridge: """${typeof vaultBridge === 'string' ? vaultBridge : JSON.stringify(vaultBridge)}"""
Commands: """${typeof commands === 'string' ? commands : JSON.stringify(commands)}"""

Add the M2 test suite, following the same pattern M1 established (in-memory fake DAOs for
fast unit tests, Testcontainers for anything that genuinely needs a real database):

1. Unit tests for EconomyServiceImpl/TreasuryServiceImpl against hand-written in-memory
   fake implementations of PlayerWalletDao/RealmDao/LedgerDao (simple, deterministic fakes
   — NOT Mockito mocks, NOT a real database). Cover at least:
   - EconomyService.deposit credits correctly; withdraw debits correctly; withdraw returns
     false (not an exception) when funds are insufficient.
   - TreasuryService.deposit debits the player and credits the realm atomically (in your
     fakes, assert both changes happened together, and that a failed player-side debit
     never leaves the realm side credited).
   - TreasuryService.withdraw rejects an actor whose rank lacks the WITHDRAW permission bit
     without touching either balance.
   - TreasuryService.withdraw returns false when the realm treasury has insufficient funds.
   - Every successful path above results in exactly one recorded ledger entry in your fake
     LedgerDao (assert this explicitly — it's the whole point of the shared helper).
   - Money rejects/handles negative amounts sensibly wherever it's validated (constructors,
     service method entry points).

2. A Testcontainers-based integration test (tag it @Tag("integration"), excluded from the
   default 'test' task exactly like M1's JdbcRealmDaoIT — follow that same pattern/task
   name) that spins up a real MariaDB container, runs V1 and V2 migrations, and:
   - Verifies PlayerWalletDao/RealmDao's tryAdjustBalance conditional updates actually
     prevent a balance from going negative under concurrent load: spawn multiple threads
     each attempting to withdraw more than the current balance can cover in total, and
     assert the final balance is never negative and exactly the expected number of
     withdrawals succeeded.
   - Verifies the ledger invariant end-to-end: after a mix of deposits and withdrawals
     through the real services, the number of rows in 'transactions' matches the number of
     successful (not attempted) balance-changing calls, and the sum of amount_cents for
     TRANSFER-category rows nets to zero when you account for both sides of each transfer.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
explicitly confirm whether you were able to run the fake-DAO unit tests in this environment
and whether they passed.
`, { label: 'economy-tests', phase: 'Tests' })

// ---------------------------------------------------------------------------
// Stage 6 — Build verification and fix-up
// ---------------------------------------------------------------------------
phase('Verify')
const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    buildSucceeded: { type: 'boolean' },
    unitTestsSucceeded: { type: 'boolean' },
    summary: { type: 'string' },
    remainingIssues: { type: 'array', items: { type: 'string' } },
  },
  required: ['buildSucceeded', 'unitTestsSucceeded', 'summary'],
}

let verify = await agent(`
Final verification pass on the FlameRealms plugin project (package ${PACKAGE}) in the
current directory, covering everything built across the M2 economy workflow, on top of the
already-existing M0+M1 code:

Schema/domain report: """${typeof schemaDomain === 'string' ? schemaDomain : JSON.stringify(schemaDomain)}"""
DAO/ledger report: """${typeof daoLedger === 'string' ? daoLedger : JSON.stringify(daoLedger)}"""
Services report: """${typeof services === 'string' ? services : JSON.stringify(services)}"""
Vault bridge report: """${typeof vaultBridge === 'string' ? vaultBridge : JSON.stringify(vaultBridge)}"""
Commands report: """${typeof commands === 'string' ? commands : JSON.stringify(commands)}"""
Tests report: """${typeof tests === 'string' ? tests : JSON.stringify(tests)}"""

Do the following, in order:
1. Run the build (./gradlew build if the wrapper exists, otherwise gradle build). Read the
   full error output if it fails.
2. If there are compile errors, fix them directly — you have full Read/Edit/Bash access to
   the whole project. Common cross-stage seams to check first: LedgerDao/PlayerWalletDao/
   RealmDao method signatures actually matching what EconomyServiceImpl/TreasuryServiceImpl
   call, Money's API being used consistently everywhere (no stray double/float creeping
   in), CompletableFuture usage being consistent end to end into the command layer, and the
   Vault bridge only referencing Vault API classes behind the plugin-presence check (it
   must not crash plugin startup if Vault's jar isn't even on the classpath at runtime —
   compileOnly is correct for this).
3. Once it compiles, run the unit test task (not the Docker-dependent integration test —
   exclude/skip it explicitly if it would hang or fail for lack of Docker in this sandbox;
   note whether Docker was actually available).
4. Report exactly what still doesn't work, if anything — do not claim something works if
   you didn't actually verify it.

Do not read PROJECT.md or TECHNICAL_SPEC.md unless you hit something genuinely ambiguous
that the reports above don't resolve.
`, { schema: VERIFY_SCHEMA, label: 'verify', phase: 'Verify' })

let fixAttempts = 0
while (!verify.buildSucceeded && fixAttempts < 2) {
  fixAttempts++
  log(`Build still failing, fix attempt ${fixAttempts}/2...`)
  verify = await agent(`
The FlameRealms build is still failing after a previous attempt. Its report:
"""${JSON.stringify(verify)}"""

Fix the remaining compile/build errors directly (Read/Edit/Bash access to the whole
project), then re-run the build and the unit test task, and report the same structured
result again (buildSucceeded, unitTestsSucceeded, summary, remainingIssues).
`, { schema: VERIFY_SCHEMA, label: `verify-fix-${fixAttempts}`, phase: 'Verify' })
}

log(verify.buildSucceeded
  ? `Build succeeded after ${fixAttempts} fix attempt(s). Unit tests ${verify.unitTestsSucceeded ? 'passed' : 'did NOT pass'}.`
  : `Build still failing after ${fixAttempts} fix attempt(s) — see remainingIssues.`)

return { ...verify, fixAttempts }
