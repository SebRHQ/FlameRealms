// FlameRealms — M3 implementation workflow (Claims + Territory Visualization + base upkeep)
//
// Invoke from a fresh chat with:
//   Workflow({ scriptPath: "workflows/flamerealms-m3-claims.js" })
//
// Prerequisite: M0+M1 (workflows/flamerealms-m0-m1.js) and M2 (workflows/flamerealms-m2-economy.js)
// must already have run successfully — this workflow builds directly on FlameRealmsPlugin,
// DatabaseManager, AsyncDatabaseExecutor, RealmCache, RealmService/RealmServiceImpl,
// EconomyService/TreasuryService, LedgerDao, RealmPermission (CLAIM/UNCLAIM bits already
// exist from M1), and migrations V1/V2.
//
// Scope: TECHNICAL_SPEC.md v0.4 + TODO.md, milestone M3 (Claims + Territory Visualization +
// base upkeep) ONLY:
//   - realm_claims schema, with contiguity validation
//   - realms.friendly_fire_enabled column (D-required column that never landed in V1; cheapest
//     correct place for it is here, alongside the next realm-touching migration — no PvP logic
//     wired to it yet, that is M7's job)
//   - realm_member_activity, presence-only (the contribution-signal half of the dual-signal
//     model arrives with M5's contribution_events; until then "presence ∪ contribution"
//     degrades to just presence, which is what this workflow implements)
//   - Claim purchase: ONE DB transaction for permission-check + withdraw (from realm treasury,
//     via LedgerDao) + claim-insert + ledger-insert
//   - Tiered/dynamic claim pricing (pricing.yml)
//   - Base daily upkeep: territoryCost(claims) x territoryMultiplier(claims) x
//     activePopulationMultiplier(activePopulation), applied as a realm-treasury SINK, with
//     shortfall accumulating in the EXISTING realms.upkeep_debt_cents column (no auto-release
//     of claims on debt yet — not required by TODO.md/spec at this milestone, left as an open
//     follow-up)
//   - ClaimVisualizationService (particle chunk-boundary preview) + TerritoryMapService
//     (/realm map) + the chat-driven /realm claim preview -> confirm flow
//
// Explicitly OUT OF SCOPE (do not build): Nexus (M4, placement/registration only, nothing to
// anchor contiguity to yet — so M3 contiguity is claim-to-claim only, revisited once M4 adds a
// Nexus chunk), realm creation fee (D5, not part of M3's stated scope even though it's a gap),
// auto-unclaim on prolonged upkeep debt, contribution_events/contribution_totals (M5), any
// PvP/friendly-fire enforcement logic (M7).
//
// Every stage prompt is self-contained: no agent is asked to read PROJECT.md or
// TECHNICAL_SPEC.md — the exact schema, interfaces, and constraints each stage needs are
// embedded directly.

export const meta = {
  name: 'flamerealms-m3-claims',
  description: 'Implement FlameRealms M3 — realm_claims with contiguity validation, tiered claim pricing, base territory upkeep, presence-based active-population tracking, and territory visualization/map',
  whenToUse: 'Run this after the M0+M1 and M2 workflows have completed successfully, to add claims/territory per TECHNICAL_SPEC.md v0.4 + TODO.md milestone M3.',
  phases: [
    { title: 'Schema & domain', detail: 'V3 migration, RealmClaim/ChunkCoordinate domain types, pricing.yml' },
    { title: 'DAO layer', detail: 'RealmClaimDao, RealmMemberActivityDao, RealmCache claim tracking' },
    { title: 'Services', detail: 'ClaimService (purchase/unclaim/contiguity) + UpkeepService/ActivityTrackingService' },
    { title: 'Visualization', detail: 'ClaimVisualizationService (particles) + TerritoryMapService (/realm map)' },
    { title: 'Commands', detail: '/realm claim, claim confirm, unclaim, map' },
    { title: 'Tests', detail: 'Unit tests for pricing/contiguity/upkeep + Testcontainers concurrency test' },
    { title: 'Verify', detail: 'Build, fix compile errors, produce final report' },
  ],
}

const PACKAGE = 'com.flamerealms'

// ---------------------------------------------------------------------------
// Stage 1 — Schema, domain types, pricing config
// ---------------------------------------------------------------------------
phase('Schema & domain')
const schemaDomain = await agent(`
Continuing work on the existing FlameRealms plugin (package ${PACKAGE}) in the current
directory. M0-M2 already exist: FlameRealmsPlugin, DatabaseManager, AsyncDatabaseExecutor,
RealmCache, RealmService/RealmServiceImpl, EconomyService/TreasuryService, LedgerDao,
RealmPermission (a bitmask enum that ALREADY has CLAIM and UNCLAIM bits defined, unused until
now), and Flyway migrations V1__realm_core.sql / V2__economy.sql. Do not modify those files
except where explicitly told to below — only add to them.

1. Flyway migration src/main/resources/db/migration/V3__claims.sql. Match V1's ACTUAL foreign
   key style exactly: a separate "FOREIGN KEY (col) REFERENCES table(col) ON DELETE CASCADE"
   clause, NOT an inline column-level REFERENCES shorthand (MariaDB/MySQL does not enforce
   that form as a real constraint).

ALTER TABLE realms
  ADD COLUMN friendly_fire_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE realm_claims (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id          BIGINT UNSIGNED NOT NULL,
  world             VARCHAR(64)  NOT NULL,
  chunk_x           INT          NOT NULL,
  chunk_z           INT          NOT NULL,
  claimed_at        DATETIME     NOT NULL,
  price_paid_cents  BIGINT       NOT NULL,
  UNIQUE KEY uq_chunk (world, chunk_x, chunk_z),
  INDEX idx_realm (realm_id),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE
);

CREATE TABLE realm_member_activity (
  realm_id          BIGINT UNSIGNED NOT NULL,
  player_uuid       BINARY(16)   NOT NULL,
  activity_date     DATE         NOT NULL,
  online_minutes    INT UNSIGNED NOT NULL DEFAULT 0,
  contributed       BOOLEAN      NOT NULL DEFAULT FALSE,
  PRIMARY KEY (realm_id, player_uuid, activity_date)
);

(friendly_fire_enabled has NO enforcement logic yet — that is M7's PvP listener rewrite, not
this milestone. realm_member_activity.contributed is unused/always-false in this milestone —
contribution_events doesn't exist until M5, so the "presence UNION contribution" active-
population signal degrades to presence-only for now; say so in a comment where the column is
written.)

2. Domain records (${PACKAGE}.domain package), plain immutable Java records, no Bukkit/JDBC
   types:
   - RealmClaim(long id, long realmId, String world, int chunkX, int chunkZ, Instant
     claimedAt, long pricePaidCents)
   - ChunkCoordinate(String world, int chunkX, int chunkZ) — a small value type used for
     contiguity math and cache keys. Add isAdjacentTo(ChunkCoordinate other): true only for
     the 4 orthogonal neighbors in the SAME world (dx=+-1,dz=0 or dx=0,dz=+-1) — NOT diagonal,
     NOT the same chunk. Implement equals/hashCode correctly (records get these for free, but
     confirm World name comparison is case-sensitive/exact, matching how Bukkit world names
     already work elsewhere in this project).

3. src/main/resources/pricing.yml — claim purchase price curve (tiered by how many claims the
   realm already owns, matching PROJECT.md's documented example bands: cheap up to 10, medium
   11-25, expensive 26-50, very expensive beyond) and territory-upkeep constants:

claims:
  purchase-tiers:
    - max-claims: 10
      price-cents: 25000
    - max-claims: 25
      price-cents: 40000
    - max-claims: 50
      price-cents: 60000
    - max-claims: 2147483647
      price-cents: 100000

upkeep:
  cost-per-chunk-cents: 2000
  territory-multiplier-tiers:
    - max-claims: 10
      multiplier: 1.0
    - max-claims: 25
      multiplier: 1.15
    - max-claims: 50
      multiplier: 1.35
    - max-claims: 2147483647
      multiplier: 1.6
  active-population:
    presence-threshold-minutes: 60
    rolling-window-days: 7
    saturation-constant: 8.0
    max-multiplier-bonus: 0.5

(A tier's "max-claims" is inclusive of that tier — e.g. the Nth claim purchased, where N is
the count AFTER this purchase, i.e. existing-count+1, falls into the first tier whose
max-claims >= N. The active-population multiplier's exact formula is chosen in the Services
stage, not here — this file only supplies the tuning constants for whatever saturating curve
that stage implements.)

4. A small config loader, ${PACKAGE}.config.PricingConfig, loaded the same way DatabaseConfig
   is loaded (read from a YAML file via Bukkit's YamlConfiguration, NOT a new YAML library
   dependency) — read src/main/java/com/flamerealms/config/DatabaseConfig.java first to match
   its exact loading pattern/style. Expose typed accessors: a method that returns the purchase
   price (in cents) for owning N claims after this purchase, a method that returns the
   territory multiplier for a given claim count, and typed accessors for
   costPerChunkCents/presenceThresholdMinutes/rollingWindowDays/saturationConstant/
   maxMultiplierBonus. pricing.yml must be copied to the plugin's data folder on first run via
   saveResource(...)/similar, matching how config.yml already works, and loaded from there
   (not from the jar) so a server admin can edit it.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and the
exact RealmClaim/ChunkCoordinate/PricingConfig API you settled on (method names/signatures) —
every later stage in this workflow depends on these being stable.
`, { label: 'schema-domain', phase: 'Schema & domain' })

// ---------------------------------------------------------------------------
// Stage 2 — DAO layer + RealmCache claim tracking
// ---------------------------------------------------------------------------
phase('DAO layer')
const dao = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Stage 1 added V3__claims.sql, RealmClaim/ChunkCoordinate domain types, and pricing.yml/
PricingConfig. Its report:

"""
${typeof schemaDomain === 'string' ? schemaDomain : JSON.stringify(schemaDomain)}
"""

1. ${PACKAGE}.persistence.dao.RealmClaimDao (interface) + JdbcRealmClaimDao (impl,
   ${PACKAGE}.persistence.jdbc package), every method taking a java.sql.Connection as its
   first parameter (same pattern as every existing DAO in this project):
   - insert(Connection, RealmClaim claim) -> long (generated id) — plain INSERT. The caller
     is responsible for having already confirmed the chunk is free and for running this
     inside a transaction; this method itself does no locking/validation beyond letting the
     uq_chunk unique constraint reject a genuine race as a last-resort guarantee (catch and
     rethrow java.sql.SQLIntegrityConstraintViolationException distinctly if you can, so the
     Services stage can tell "someone else claimed this chunk a moment ago" apart from a
     generic persistence failure).
   - findByRealm(Connection, long realmId) -> List<RealmClaim>
   - countByRealm(Connection, long realmId) -> int
   - findAll(Connection) -> List<RealmClaim> — for cache/warm-up, same "no bulk find is
     normally exposed, but startup warm-up is the sanctioned exception" reasoning RealmCache's
     own Javadoc already documents for its own loadAll(); mirror that reasoning here.
   - delete(Connection, long realmId, String world, int chunkX, int chunkZ) -> boolean (true
     if a row was actually removed)

2. ${PACKAGE}.persistence.dao.RealmMemberActivityDao (interface) + Jdbc impl:
   - recordPresence(Connection, long realmId, UUID playerUuid, java.time.LocalDate date, int
     minutesDelta) -> void — upsert (INSERT ... ON DUPLICATE KEY UPDATE online_minutes =
     online_minutes + ?) against realm_member_activity's (realm_id, player_uuid,
     activity_date) primary key.
   - countActiveMembers(Connection, long realmId, java.time.LocalDate since, int
     presenceThresholdMinutes) -> int — one SQL query: count DISTINCT player_uuid within
     this realm whose SUM(online_minutes) across activity_date >= since is >= the threshold
     (GROUP BY player_uuid HAVING SUM(...) >= ?, then count the groups — or an equivalent
     single query). This is the "activePopulation" count the daily upkeep formula needs.

3. Extend the EXISTING RealmCache (src/main/java/com/flamerealms/cache/RealmCache.java) with
   claim tracking, following its exact existing conventions (ConcurrentHashMap-backed,
   write-through-after-commit only, a loadAll-style warm-up method, Javadoc explaining the
   contract):
   - A Map<Long, Set<ChunkCoordinate>> of realmId -> that realm's claimed chunks, and a
     Map<ChunkCoordinate, Long> reverse index for O(1) "is this chunk claimed, and by whom"
     lookups (useful for later milestones' PvP territory gate too, not just this one).
   - loadClaims(Connection) throws SQLException — warm-up, called alongside the existing
     loadAll() at startup (do NOT merge them into one method; keep loadAll() for
     realms/members as-is and add this as a sibling method the plugin bootstrap calls
     separately, since RealmClaimDao is a new dependency loadAll() doesn't have).
   - claimsOf(long realmId) -> Set<ChunkCoordinate> (empty set if none, never null)
   - ownerOf(ChunkCoordinate) -> Optional<Long> realmId
   - addClaim(long realmId, ChunkCoordinate) -> called post-commit only, same contract as
     every other mutator on this class.
   - removeClaim(ChunkCoordinate) -> called post-commit only.
   - removeAllClaimsOf(long realmId) -> called when a realm is disbanded (realm_claims cascades
     via the FK, so the cache needs the same cleanup RealmCache.removeRealm(...) already does
     for members — wire this into that existing method's call sites, or note clearly in your
     report if RealmServiceImpl.disbandRealm needs a follow-up call added to invoke it, since
     you should NOT modify RealmServiceImpl in this stage).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created/edited,
the exact method signatures for RealmClaimDao, RealmMemberActivityDao, and the new RealmCache
methods (the Services stage depends on every one of these being stable), and explicitly flag
whether RealmServiceImpl.disbandRealm needs a small follow-up edit to call
removeAllClaimsOf(...) (you may make that one-line edit yourself if it's obviously safe and
you've read the existing disbandRealm method to confirm where the post-commit cache cleanup
already happens).
`, { label: 'dao-layer', phase: 'DAO layer' })

// ---------------------------------------------------------------------------
// Stage 3 — Services: ClaimService, UpkeepService + ActivityTrackingService (parallel)
// ---------------------------------------------------------------------------
phase('Services')
const [claimService, upkeepActivity] = await parallel([
  () => agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmClaimDao, RealmMemberActivityDao, and RealmCache's new claim-tracking methods now exist.
Their report:

"""
${typeof dao === 'string' ? dao : JSON.stringify(dao)}
"""

Implement ${PACKAGE}.service.ClaimService (interface) + ClaimServiceImpl, dispatching every
DB-touching call through the EXISTING AsyncDatabaseExecutor.submit(...) — never on the calling
thread. Follow TreasuryServiceImpl's EXACT existing shape (read it first:
src/main/java/com/flamerealms/service/TreasuryServiceImpl.java) — its private inTransaction()
helper (setAutoCommit(false)/commit/rollback, restoring original autocommit in a finally), its
InsufficientFundsSignal-style internal control-flow exception used ONLY to force a rollback
and get translated to a specific outcome right outside the submit(...) lambda, its pattern of
fast-failing against RealmCache before ever dispatching to the executor, and its Javadoc style
explaining non-obvious choices.

Add new exceptions to ${PACKAGE}.service.exception (matching the existing
...ServiceException-per-failure-mode style, e.g. RealmNotFoundException/
RealmNameTakenException already there) — REUSE the existing MissingPermissionException,
RealmNotFoundException and PlayerNotInRealmException where their semantics already fit; only
add genuinely new ones:
  - ChunkAlreadyClaimedException
  - ClaimNotContiguousException
  - InsufficientTreasuryFundsException

1. CompletableFuture<RealmClaim> purchaseClaim(long realmId, UUID actor, String world, int
   chunkX, int chunkZ):
   Inside ONE AsyncDatabaseExecutor.submit + inTransaction call (one connection, one
   transaction — this is the "claim purchase: one DB transaction for withdraw + claim-insert
   + ledger-insert" requirement):
   a. Resolve actor's realm_members/realm_ranks row the same way
      TreasuryServiceImpl.requireWithdrawPermission does, but requiring
      RealmPermission.CLAIM instead of WITHDRAW; throw PlayerNotInRealmException /
      MissingPermissionException as appropriate. If actor's realm_members.realm_id != realmId,
      treat as PlayerNotInRealmException too.
   b. Load this realm's existing claims (RealmClaimDao.findByRealm, inside this same
      transaction/connection) and compute contiguity: if the realm has zero existing claims,
      the new chunk is always allowed (it "seeds" the realm's territory — there is no Nexus to
      anchor to yet, that arrives in M4). Otherwise the requested (world, chunkX, chunkZ) must
      be ChunkCoordinate#isAdjacentTo at least one EXISTING claim of this realm, same world;
      if not, throw ClaimNotContiguousException. (Contiguity is checked against existing
      claims only, not against a Nexus location — restate this as a comment so a future M4
      reader understands why, per TODO.md/spec's D4.)
   c. Compute price via PricingConfig, based on existingCount+1 (the tier the NEW claim falls
      into).
   d. LedgerDao.recordAndApplyToRealm(conn, realmId, -priceCents, TransactionCategory.SINK,
      "CLAIM_PURCHASE", LedgerEntity.SERVER, null) — if it returns false (treasury can't
      cover it), throw the InsufficientFundsSignal-equivalent to force rollback, translate to
      InsufficientTreasuryFundsException right outside inTransaction.
   e. RealmClaimDao.insert(conn, new RealmClaim(...)) — if it signals a unique-constraint
      violation (someone else claimed this exact chunk inside a concurrent transaction),
      translate to ChunkAlreadyClaimedException and let the rollback undo the ledger debit
      too (this is exactly why steps d and e must be in the same transaction).
   f. Return the persisted RealmClaim (with its generated id) as the transaction's result.
   AFTER the future completes successfully (outside the transaction, in the .thenApply/
   .thenAccept-style continuation the CALLER will add — or, if you prefer, have
   purchaseClaim's own CompletableFuture chain call realmCache.addClaim(...) itself once the
   submit(...) future completes, which is simpler and keeps the post-commit cache write
   colocated with the write it corresponds to; pick whichever matches this project's existing
   convention more closely after checking how RealmServiceImpl.createRealm applies its own
   post-commit cache write, and follow that exact pattern).

2. CompletableFuture<Boolean> unclaimChunk(long realmId, UUID actor, String world, int
   chunkX, int chunkZ): requires RealmPermission.UNCLAIM (same permission-check pattern as
   above). Deletes the claim row (RealmClaimDao.delete) inside one transaction. Per D6 (0%
   unclaim refund, already decided/out of scope to revisit), this does NOT create any ledger
   entry and does NOT refund any money — the price paid at claim time is simply gone. Does
   NOT attempt to preserve territory connectivity (unclaiming a chunk that would fragment the
   realm's territory into disconnected islands is explicitly allowed — contiguity is only
   enforced when ADDING a claim, never when removing one; say so in a comment). Update
   RealmCache (removeClaim) after commit, same pattern as purchaseClaim.
   Return false if the chunk wasn't claimed by this realm to begin with (not an exception —
   an ordinary "nothing to unclaim" outcome), true on success.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, the
exact ClaimService method signatures and every new exception type (the Commands stage depends
on these), and confirm which post-commit cache-write pattern you followed and why.
`, { label: 'claim-service', phase: 'Services' }),

  () => agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmClaimDao, RealmMemberActivityDao, and RealmCache's new claim-tracking methods now exist.
Their report:

"""
${typeof dao === 'string' ? dao : JSON.stringify(dao)}
"""

Implement two independent pieces:

1. ${PACKAGE}.service.ActivityTrackingService — presence-only tracking of realm_member_activity,
   following the "write-behind / eventually consistent" pattern this project's async model
   documents for non-financial state: the main thread updates an in-memory value immediately
   for cheap bookkeeping, and the DB write happens on a throttled interval via
   AsyncDatabaseExecutor, PLUS a full synchronous flush on graceful shutdown (never on a
   crash — that's the accepted bounded-loss window).
   - An in-memory Map<UUID, Integer> of today's accumulated online minutes per player (only
     for players currently in a realm — use RealmCache.getByPlayer/isPlayerInRealm to check).
   - A Bukkit repeating task (Bukkit.getScheduler().runTaskTimer, main thread, once per
     simulated minute i.e. every 1200 ticks) that increments each currently-online,
     realm-member player's counter in that in-memory map by 1 — O(online player count), cheap,
     no DB touch here.
   - A second, less frequent repeating task (every 5 real minutes) that flushes the current
     snapshot of that map to the database via RealmMemberActivityDao.recordPresence for each
     entry (dispatched through AsyncDatabaseExecutor, batched into one transaction per flush if
     reasonable, or one submit per player if simpler — note which you chose and why), then
     resets each flushed player's in-memory counter to 0 (so the next flush only adds the
     delta since the last one, not double-counting).
   - A flushNow() method that does the same DB write SYNCHRONOUSLY (blocks until the
     AsyncDatabaseExecutor future completes) — called from FlameRealmsPlugin.onDisable() ONLY
     (document clearly that blocking is acceptable there and ONLY there, since the plugin is
     already shutting down and nothing else needs the main thread free). You do NOT need to
     wire this into FlameRealmsPlugin yourself — just expose the method and document the
     intended call site.
   - Correctly roll over "today" at local-date boundaries — if the accumulator is still
     holding yesterday's date's counts when a flush happens, flush them against the correct
     (past) activity_date, not today's.

2. ${PACKAGE}.service.UpkeepService — the daily territory-upkeep charge. Implement the exact
   formula documented in TECHNICAL_SPEC.md (restated here in full, you do not need to read the
   spec):

     Daily Upkeep = territoryCost(claims) x territoryMultiplier(claims) x
                    activePopulationMultiplier(activePopulation)

   - territoryCost(claimCount) = claimCount x PricingConfig.costPerChunkCents()
   - territoryMultiplier(claimCount) = PricingConfig's territory-multiplier-tiers lookup
     (same tiered-lookup shape as the claim purchase price tiers Stage 1 defined).
   - activePopulationMultiplier(activePopulation): MUST be a genuinely moderate, SATURATING
     curve (small marginal cost per additional active member, never a runaway/linear cost for
     an ordinary small group) — this is an explicit design goal, not a suggestion. A
     reasonable concrete formula using PricingConfig's saturationConstant (k) and
     maxMultiplierBonus (m): multiplier = 1.0 + m * (1 - exp(-activePopulation / k)). Document
     in a comment why this shape satisfies "moderate, saturating, small marginal cost."
   - activePopulation = RealmMemberActivityDao.countActiveMembers(realmId, today minus
     PricingConfig.rollingWindowDays(), PricingConfig.presenceThresholdMinutes()) — this is
     presence-only in this milestone (no contribution_events yet, see Stage 1's note).
   - A daily repeating task (Bukkit.getScheduler().runTaskTimer, interval = 24h in ticks —
     exact wall-clock scheduling/timezone handling is not the point here, a fixed
     20*60*60*24-tick period is fine) that, for every currently-cached active realm
     (RealmCache doesn't expose "all realms" today — add a values()-returning accessor if
     needed, following its existing read-method conventions, OR iterate differently if you
     find a cleaner existing hook; note which you did), computes claimCount via
     RealmClaimDao.countByRealm and dispatches ONE AsyncDatabaseExecutor.submit per realm that:
       a. Reads any existing realms.upkeep_debt_cents for this realm.
       b. totalCharge = existing debt + this cycle's computed upkeep.
       c. Attempts LedgerDao.recordAndApplyToRealm(conn, realmId, -totalCharge, SINK,
          "TERRITORY_UPKEEP", LedgerEntity.SERVER, null) for the FULL totalCharge in one shot
          (recordAndApplyToRealm is all-or-nothing by design — there is no partial-payment
          primitive in this codebase, and this milestone does not add one).
       d. If it succeeds: set realms.upkeep_debt_cents back to 0 (a plain UPDATE, same
          connection/transaction) — the whole debt-plus-new-charge was just paid off.
       e. If it fails (insufficient treasury funds): do NOT touch the ledger/balance at all;
          instead UPDATE realms SET upkeep_debt_cents = upkeep_debt_cents + <this cycle's
          computed upkeep only, NOT the old debt which is already counted> — i.e. debt
          accumulates by exactly one cycle's worth each time it can't be paid. Add a RealmDao
          method for this if one doesn't already exist suited to it (check RealmDao first —
          you may add a narrowly-scoped incrementUpkeepDebt(Connection, long realmId, long
          deltaCents) method there if needed, matching its existing method style).
       This whole per-realm operation is its own transaction, independent of every other
       realm's — one realm's upkeep failure must never affect another's.
   - Do NOT implement automatic claim release / auto-unclaim when debt accumulates — out of
     scope for this milestone, note it as an explicit open follow-up in your report (matching
     TODO.md, which lists it as still-pending elsewhere).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md beyond what's restated above. Report
back: files created, the exact public method signatures for both services (note especially
whether you added anything to RealmDao or RealmCache), and confirm neither service's repeating
tasks do any JDBC work on the calling (main) thread.
`, { label: 'upkeep-activity', phase: 'Services' }),
])

// ---------------------------------------------------------------------------
// Stage 4 — Visualization
// ---------------------------------------------------------------------------
phase('Visualization')
const visualization = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
ClaimService (purchaseClaim/unclaimChunk) and RealmCache's claim-tracking methods now exist.
Their report:

"""
${typeof claimService === 'string' ? claimService : JSON.stringify(claimService)}
"""

Implement two independent, vanilla-Paper-API-only services (no packet library, no
ProtocolLib) under ${PACKAGE}.visualization:

1. ClaimVisualizationService — shows a chunk-boundary particle preview.
   - void previewChunk(Player player, String world, int chunkX, int chunkZ) — spawns
     particles (Particle.FLAME by default, but read the value from config.yml — add a new
     top-level "visualization:" section there with "particle: FLAME" and
     "preview-duration-seconds: 5", following config.yml's existing YAML style/comment
     conventions) tracing the 16x16 chunk's four edges at a few Y-levels around the player's
     current Y (e.g. player.getY()-1 to +2), using World#spawnParticle, via a repeating
     Bukkit task (runTaskTimer on the MAIN thread — particle spawning is Bukkit API and must
     never run off-thread) that fires every few ticks for the configured duration, then
     self-cancels. Keep the point count reasonable (do not spawn hundreds of particles per
     tick) — a handful of points per edge, refreshed each pulse, is enough to read as an
     outline.

2. TerritoryMapService — renders a compact ASCII/text-grid map of chunks around a player in
   chat, matching this project's PROJECT.md style of small text-grid diagrams (you do not
   need to read PROJECT.md — a "N chunks claimed by realm X show as a colored square,
   unclaimed chunks as a neutral square, the player's own current chunk marked distinctly"
   layout is the right shape).
   - Component renderMap(Player player) — reads a square radius (config.yml's new
     "visualization: map-radius-chunks: 8", add it alongside the section above) of chunks
     centered on the player's current chunk, looks each one up via RealmCache.ownerOf(...)
     (from Stage 2), and builds a multi-line Adventure Component: claimed chunks belonging to
     the player's own realm in one color, claimed chunks belonging to any other realm in
     another color, unclaimed chunks in a neutral color/symbol, the player's own position
     highlighted distinctly. Use Adventure's Component/TextComponent APIs directly (this
     project uses MiniMessage for messages.yml-driven strings elsewhere, but a dynamically
     generated grid like this can be built with plain Component.text(...) calls — do not
     invent a MiniMessage template with 100+ placeholders for a generated grid).
   This method does no DB I/O itself — it only reads RealmCache, which is safe to call
   directly from the main thread (same as every other RealmCache read in this project).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created/edited
(including the config.yml diff), and the exact public method signatures for both services
(the Commands stage depends on these).
`, { label: 'visualization', phase: 'Visualization' })

// ---------------------------------------------------------------------------
// Stage 5 — Commands
// ---------------------------------------------------------------------------
phase('Commands')
const commands = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
ClaimService, UpkeepService/ActivityTrackingService, ClaimVisualizationService, and
TerritoryMapService all now exist. Reports:

ClaimService: """${typeof claimService === 'string' ? claimService : JSON.stringify(claimService)}"""
Upkeep/Activity: """${typeof upkeepActivity === 'string' ? upkeepActivity : JSON.stringify(upkeepActivity)}"""
Visualization: """${typeof visualization === 'string' ? visualization : JSON.stringify(visualization)}"""

Add subcommands to the EXISTING ${PACKAGE}.command.realm.RealmCommand (read it first in full —
src/main/java/com/flamerealms/command/realm/RealmCommand.java — do not create a second command
class or re-register /realm; extend the same Brigadier tree, follow its EXACT existing
conventions: never call .join()/.get() on a future, every continuation hops back via
Bukkit.getScheduler().runTask(plugin, ...) before touching Bukkit API or sending a message,
every new player-facing string is a messages.yml key (add the new keys there, matching its
existing MiniMessage-template style, not a hardcoded literal), and describeError()'s switch
must be extended to cover the new ClaimService exceptions):

  - claim — no arguments. Reads the executing player's current chunk (player.getLocation()'s
    world/chunk X/Z, synchronously, main thread — this is NOT a DB call). Requires the player
    be in a realm (not-in-realm message otherwise, same pattern as deposit/withdraw). Computes
    the price this claim WOULD cost via PricingConfig + ClaimService's realm-claim-count logic
    without actually purchasing anything, shows the player a preview message (price + chunk
    coords) AND calls ClaimVisualizationService.previewChunk(...) for the particle outline, and
    stores a short-lived in-memory "pending claim" for this player (a simple
    ConcurrentHashMap<UUID, PendingClaim> field on RealmCommand or a small new
    ${PACKAGE}.command.realm.PendingClaim record + holder class — PendingClaim carries realmId,
    world, chunkX, chunkZ, an expiry Instant a configurable number of seconds out, e.g. 30s)
    that "claim confirm" (below) consumes.

  - claim confirm — no arguments. Looks up this player's pending claim; if none exists or it
    has expired, send a clear message (add a messages.yml key for each case) instead of
    calling ClaimService at all. Otherwise call
    claimService.purchaseClaim(pendingClaim.realmId(), player.getUniqueId(),
    pendingClaim.world(), pendingClaim.chunkX(), pendingClaim.chunkZ()) — non-blocking, same
    future-continuation shape as every other command here — and on success send a success
    message with the price paid, on failure map the specific exception
    (ChunkAlreadyClaimedException / ClaimNotContiguousException /
    InsufficientTreasuryFundsException / MissingPermissionException / PlayerNotInRealmException)
    to a distinct message via describeError(). Clear the pending claim entry either way once
    confirm has been attempted (do not let a failed confirm be retried against a stale pending
    claim without the player re-running "claim" to get a fresh preview/price).

  - unclaim — no arguments. Uses the player's CURRENT chunk (same synchronous lookup as
    claim). Calls claimService.unclaimChunk(realmId, player.getUniqueId(), world, chunkX,
    chunkZ) directly (no preview/confirm step needed for unclaim — it's free and reversible
    only in the sense that the chunk can be re-claimed later at full price, so an accidental
    unclaim is a real cost, but per this project's judgment for M3 a confirm step is not
    required for it; note in your report if you think it should be reconsidered, but do not
    add one). On false (nothing to unclaim there), send a "this chunk isn't claimed by your
    realm" message; on success, send confirmation.

  - map — no arguments. Calls TerritoryMapService.renderMap(player) and sends the resulting
    Component directly (this is a synchronous, main-thread-only, no-DB-I/O call — no future
    involved).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Add the corresponding permission
nodes to plugin.yml's permissions: block (flamerealms.command.claim, .unclaim, .map — all
default true, matching every other subcommand's existing convention) and register them the
same way build() already threads every other subcommand builder into the Commands.literal("realm")
tree.

Report back: files created/edited, and confirm every new future-based call correctly avoids
blocking the main thread and every new player-facing message goes through messages.yml.
`, { label: 'claim-commands', phase: 'Commands' })

// ---------------------------------------------------------------------------
// Stage 6 — Tests
// ---------------------------------------------------------------------------
phase('Tests')
const tests = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
ClaimService, UpkeepService/ActivityTrackingService, visualization services, and the new
claim/unclaim/map commands all now exist. Reports:

ClaimService: """${typeof claimService === 'string' ? claimService : JSON.stringify(claimService)}"""
Upkeep/Activity: """${typeof upkeepActivity === 'string' ? upkeepActivity : JSON.stringify(upkeepActivity)}"""
Visualization: """${typeof visualization === 'string' ? visualization : JSON.stringify(visualization)}"""
Commands: """${typeof commands === 'string' ? commands : JSON.stringify(commands)}"""

Add the M3 test suite, following the EXACT pattern M1/M2 established (read
src/test/java/com/flamerealms/service/TreasuryServiceImplTest.java and
src/test/java/com/flamerealms/service/fake/FakeRealmDao.java first, to match style/conventions
precisely — hand-written in-memory fakes for fast unit tests, Testcontainers only for what
genuinely needs a real database):

1. Unit tests for ClaimServiceImpl against hand-written in-memory fakes (extend the existing
   Fake*Dao classes or add FakeRealmClaimDao/FakeRealmMemberActivityDao following their exact
   style — NOT Mockito). Cover at least:
   - purchaseClaim succeeds for a realm's first-ever claim (no contiguity requirement).
   - purchaseClaim rejects a second claim that is NOT orthogonally adjacent to any existing
     claim (ClaimNotContiguousException), and accepts one that is.
   - purchaseClaim rejects an actor lacking the CLAIM permission bit, without touching the
     treasury balance or inserting any claim row.
   - purchaseClaim rejects when the realm treasury can't afford the tiered price
     (InsufficientTreasuryFundsException), and the treasury balance is left unchanged (assert
     this explicitly in your fake).
   - purchaseClaim's price actually reflects the correct pricing tier for the Nth claim.
   - unclaimChunk removes the claim and creates NO ledger entry (0% refund), and requires the
     UNCLAIM permission bit.
   - unclaimChunk returns false (not an exception) for a chunk the realm hasn't claimed.

2. Unit tests for the upkeep formula (territoryCost x territoryMultiplier x
   activePopulationMultiplier) as a pure function, independent of the DB/scheduler plumbing if
   you structured it that way (extract the formula into a small testable method/class if
   UpkeepService doesn't already separate "compute the charge" from "apply it via the DB" —
   note if you need to do this extraction yourself). Cover: the multiplier curve is
   genuinely saturating (marginal increase shrinks as activePopulation grows, verify this with
   at least 3 sample points), and a debt-accumulation case (insufficient funds adds exactly
   one cycle's cost to upkeep_debt_cents, not the full existing debt twice).

3. A Testcontainers-based integration test (@Tag("integration"), excluded from the default
   'test' task, following JdbcRealmDaoIT's exact pattern/task name) that spins up MariaDB,
   runs V1+V2+V3 migrations, and verifies: the uq_chunk unique constraint actually prevents
   two concurrent purchaseClaim calls for the SAME chunk from both succeeding (spawn threads
   racing to claim the identical chunk_x/chunk_z/world; assert exactly one succeeds and the
   realm treasury was debited exactly once, not twice) — this is the concrete concurrency
   guarantee the "one transaction for withdraw+insert+ledger" requirement exists to provide.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
explicitly confirm whether you were able to run the fake-DAO unit tests in this environment
and whether they passed.
`, { label: 'claims-tests', phase: 'Tests' })

// ---------------------------------------------------------------------------
// Stage 7 — Build verification and fix-up
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
current directory, covering everything built across the M3 claims workflow, on top of the
already-existing M0-M2 code:

Schema/domain report: """${typeof schemaDomain === 'string' ? schemaDomain : JSON.stringify(schemaDomain)}"""
DAO report: """${typeof dao === 'string' ? dao : JSON.stringify(dao)}"""
ClaimService report: """${typeof claimService === 'string' ? claimService : JSON.stringify(claimService)}"""
Upkeep/Activity report: """${typeof upkeepActivity === 'string' ? upkeepActivity : JSON.stringify(upkeepActivity)}"""
Visualization report: """${typeof visualization === 'string' ? visualization : JSON.stringify(visualization)}"""
Commands report: """${typeof commands === 'string' ? commands : JSON.stringify(commands)}"""
Tests report: """${typeof tests === 'string' ? tests : JSON.stringify(tests)}"""

Do the following, in order:
1. Run the build (./gradlew build if the wrapper exists, otherwise gradle build). Read the
   full error output if it fails.
2. If there are compile errors, fix them directly — you have full Read/Edit/Bash access to
   the whole project. Common cross-stage seams to check first: RealmClaimDao/
   RealmMemberActivityDao/PricingConfig method signatures actually matching what
   ClaimServiceImpl/UpkeepService call, RealmCache's new claim-tracking methods being called
   with the right types (ChunkCoordinate vs raw world/x/z), FlameRealmsPlugin actually wiring
   up every new DAO/service/config object it needs to construct RealmCommand with (check
   whether RealmCommand's constructor grew new parameters this workflow needs to thread
   through FlameRealmsPlugin.onEnable() — if so and it's missing, wire it), and every new
   exception type actually being handled in describeError().
3. Once it compiles, run the unit test task (not the Docker-dependent integration test —
   exclude/skip it explicitly if it would hang or fail for lack of Docker in this sandbox;
   note whether Docker was actually available).
4. Report exactly what still doesn't work, if anything — do not claim something works if you
   didn't actually verify it.

Do not read PROJECT.md or TECHNICAL_SPEC.md unless you hit something genuinely ambiguous that
the reports above don't resolve.
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
