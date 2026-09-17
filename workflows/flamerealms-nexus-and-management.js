// FlameRealms — Nexus placement (mandatory at founding) + realm management gaps
// (kick, setrank surface, leadership transfer, creation fee, persisted invites,
// upkeep-debt consequence, per-player action lock, messages.yml hot-reload).
//
// Invoke with: Workflow({ scriptPath: "workflows/flamerealms-nexus-and-management.js" })
//
// Prerequisite: everything through the GUI/resilience workflow and the WorldGuard
// claim-protection work already exists (RealmActions, ClaimProtectionService, the
// full com.flamerealms.gui package, RealmService/ClaimService/etc.).
//
// Scope, by explicit user decision this turn:
//   - Realm founding now REQUIRES the player to be standing on a placed Beacon block.
//     /realm create atomically: charges a creation fee, creates the realm, registers
//     the Beacon's location as the realm's Nexus, and auto-claims the Nexus's own
//     chunk for free (price 0) as the realm's first claim — every claim after that
//     goes through the existing paid/contiguous ClaimService.purchaseClaim path
//     unchanged. Once registered, a Nexus block can never be broken by an ordinary
//     player (flamerealms.admin bypasses this, for support/moderation).
//   - /realm kick <player>, /realm setrank <player> <rank>, /realm transfer <player>
//     all get real command + GUI surfaces (setRank's service method already existed
//     with no surface; kick and transfer are entirely new).
//   - Upkeep debt that goes unpaid for more than a configurable number of cycles
//     starts releasing the realm's most-recently-claimed chunk, one per cycle.
//   - A per-player in-flight-action lock prevents double-dispatching the same
//     money-moving action (create, deposit, withdraw, claim confirm, unclaim) while
//     a previous one for that player is still in flight.
//   - Invites move from in-memory-only to a persisted realm_invites table.
//   - /realm reload hot-reloads messages.yml only for this pass (admin-only,
//     flamerealms.admin) — pricing.yml/gui.yml/config.yml still need a restart;
//     this is an explicit, documented scope-down, not an oversight.
//
// Every stage prompt is self-contained: no agent needs to read PROJECT.md or
// TECHNICAL_SPEC.md — exact schema/signatures/thread rules are restated directly.

export const meta = {
  name: 'flamerealms-nexus-and-management',
  description: 'Mandatory Nexus placement at realm founding, plus kick/setrank/transfer, creation fee, persisted invites, upkeep-debt chunk release, per-player action lock, and messages.yml reload',
  phases: [
    { title: 'Schema & DAO', detail: 'V4 migration, pricing.yml additions, DAO interface/impl growth' },
    { title: 'RealmService rewrite', detail: 'kick, transferLeadership, createRealm (fee+Nexus+auto-claim), DB-backed invites' },
    { title: 'RealmActions & commands', detail: 'kick/setrank/transfer commands, Beacon check, action lock, /realm reload' },
    { title: 'Nexus protection & upkeep consequence', detail: 'indestructible Nexus listener, debt-triggered chunk release' },
    { title: 'GUI wiring', detail: 'Kick/SetRank/Transfer buttons + member/rank selector menus' },
    { title: 'Tests', detail: 'Unit tests for every new service/action method' },
    { title: 'Verify', detail: 'Build, fix compile errors, produce final report' },
  ],
}

const PACKAGE = 'com.flamerealms'

// ---------------------------------------------------------------------------
// Stage 1 — Schema & DAO layer
// ---------------------------------------------------------------------------
phase('Schema & DAO')
const schemaDao = await agent(`
Continuing work on the existing FlameRealms plugin (package ${PACKAGE}) in the current
directory. Read src/main/resources/db/migration/V3__claims.sql,
src/main/java/com/flamerealms/persistence/dao/RealmDao.java,
src/main/java/com/flamerealms/persistence/dao/RealmRankDao.java,
src/main/java/com/flamerealms/persistence/dao/RealmMemberDao.java,
src/main/java/com/flamerealms/persistence/jdbc/JdbcRealmDao.java,
src/main/java/com/flamerealms/persistence/jdbc/JdbcRealmRankDao.java,
src/main/java/com/flamerealms/domain/Realm.java, and
src/main/resources/pricing.yml IN FULL first, to match their exact existing conventions
(FOREIGN KEY (col) REFERENCES table(col) ON DELETE CASCADE style — never inline
column-level REFERENCES, see V1/V3's actual syntax; UuidCodec for BINARY(16) columns).

1. Flyway migration src/main/resources/db/migration/V4__nexus_and_management.sql:

ALTER TABLE realms
  ADD COLUMN nexus_world VARCHAR(64) NULL,
  ADD COLUMN nexus_x INT NULL,
  ADD COLUMN nexus_y INT NULL,
  ADD COLUMN nexus_z INT NULL,
  ADD COLUMN upkeep_unpaid_cycles INT UNSIGNED NOT NULL DEFAULT 0;

CREATE TABLE realm_invites (
  realm_id    BIGINT UNSIGNED NOT NULL,
  player_uuid BINARY(16)   NOT NULL,
  invited_at  DATETIME     NOT NULL,
  PRIMARY KEY (realm_id, player_uuid),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE
);

(nexus_* are nullable because they only ever get set at realm-creation time going
forward — there is no backfill for realms created before this migration, an accepted
gap for this early-stage project, not something to solve here. upkeep_unpaid_cycles
counts CONSECUTIVE cycles a realm's daily upkeep charge has failed to fully pay off;
UpkeepService — edited in a later stage of this workflow, not this one — increments it
on failure and resets it to 0 on a fully-successful payment.)

2. Extend the ${PACKAGE}.domain.Realm record with the new nexus fields: add
   "String nexusWorld, int nexusX, int nexusY, int nexusZ" (use Integer/String that can
   be null-equivalent if you prefer nullable boxed types over sentinel primitives —
   your call, but be consistent and document whichever convention you pick, since every
   other file touching Realm construction in this whole project needs to match it).
   Update the record's Javadoc (it explicitly says "Deliberately excludes... nexus
   (M4)... those arrive via their own accessors on this record when the time comes" —
   that's happening now, update the comment accordingly).

3. Add to RealmDao (interface + JdbcRealmDao impl):
   - A way to persist the four nexus columns — either extend insert(...) to read them
     off the Realm object being inserted (simplest — Realm already carries them now)
     or add a dedicated "setNexus(Connection, long realmId, String world, int x, int y,
     int z)" if that's cleaner given your insert(...) implementation's exact shape;
     your call, but whichever you pick, ${PACKAGE}.service.RealmServiceImpl's createRealm
     (rewritten in the next stage of this workflow) needs to be able to insert a realm
     WITH its nexus location already set, atomically, in the same transaction as
     everything else create Realm does.
   - "void incrementUnpaidUpkeepCycles(Connection connection, long realmId) throws
     SQLException" — UPDATE ... SET upkeep_unpaid_cycles = upkeep_unpaid_cycles + 1.
   - "void resetUnpaidUpkeepCycles(Connection connection, long realmId) throws
     SQLException" — UPDATE ... SET upkeep_unpaid_cycles = 0.
   - "int findUnpaidUpkeepCycles(Connection connection, long realmId) throws
     SQLException" — reads the current count.

4. Add to RealmRankDao (interface + JdbcRealmRankDao impl):
   - "java.util.List<RealmRank> findAllByRealm(Connection connection, long realmId)
     throws SQLException" — every rank belonging to a realm, for a future rank-picker
     UI/command to enumerate. Order by "priority DESC" so Leader/Officer/Member come
     out in their natural hierarchy order.

5. New ${PACKAGE}.persistence.dao.RealmInviteDao (interface) + JdbcRealmInviteDao
   (impl), matching this project's exact existing DAO style (every method takes a live
   Connection first, no connection management inside the DAO itself):
   - "void insert(Connection connection, long realmId, UUID playerUuid) throws
     SQLException" — INSERT ... ON DUPLICATE KEY UPDATE invited_at = VALUES(invited_at)
     (re-inviting an already-invited player just refreshes the timestamp, not an error).
   - "boolean exists(Connection connection, long realmId, UUID playerUuid) throws
     SQLException" — is this exact (realm, player) pair currently invited.
   - "void delete(Connection connection, long realmId, UUID playerUuid) throws
     SQLException" — removes one invite (called once it's been consumed by a join, or
     could be extended later for an explicit "uninvite" — not needed by this workflow).

6. Add to src/main/resources/pricing.yml, under new top-level sections (read the
   existing file first to match its exact YAML style/comment conventions):

realm-creation:
  fee-cents: 50000

Also extend the EXISTING "upkeep:" section (do not remove/reorder anything already
there) with one new key:

  debt-release-threshold-cycles: 3

7. Add a corresponding accessor to ${PACKAGE}.config.PricingConfig (read it first) for
   both new values: "long realmCreationFeeCents()" and "int
   debtReleaseThresholdCycles()", parsed with the SAME warn-and-default-on-bad-value
   philosophy every other field in that class already uses (it's cosmetic/tunable
   config, not a fatal-if-wrong value — same reasoning as its other fields).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files
created/edited, the exact final Realm record field list/constructor shape (every later
stage constructs Realm objects and needs this exact signature), and every new DAO
method's exact signature.
`, { label: 'schema-dao', phase: 'Schema & DAO' })

// ---------------------------------------------------------------------------
// Stage 2 — RealmServiceImpl rewrite: kick, transferLeadership, createRealm, invites
// ---------------------------------------------------------------------------
phase('RealmService rewrite')
const realmService = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Schema/DAO report: """${typeof schemaDao === 'string' ? schemaDao : JSON.stringify(schemaDao)}"""

Read src/main/java/com/flamerealms/service/RealmServiceImpl.java,
src/main/java/com/flamerealms/service/RealmService.java, and
src/main/java/com/flamerealms/persistence/dao/LedgerDao.java IN FULL before changing
anything — every change below must preserve this class's existing transaction/cache/
exception-mapping conventions EXACTLY (its own Javadoc documents them at length; match
that style for anything new too).

1. Rewrite createRealm to require a Nexus location and charge a creation fee, all in
   ONE transaction:
   New signature: "CompletableFuture<Realm> createRealm(UUID leader, String name,
   String nexusWorld, int nexusX, int nexusY, int nexusZ)" (the caller — RealmActions,
   rewritten in a later stage of this workflow — is responsible for having ALREADY
   verified a Beacon block physically exists at that location; RealmServiceImpl itself
   has no Bukkit dependency and never will, so it trusts the coordinates it's given).
   Inside the existing insertNewRealm(...)-equivalent transaction, in order:
     a. Charge the leader's PERSONAL wallet the creation fee via
        LedgerDao.recordAndApplyToPlayer(conn, leader, -pricingConfig.realmCreationFeeCents(),
        TransactionCategory.SINK, "REALM_CREATION_FEE", LedgerEntity.SERVER, null) — if it
        returns false (insufficient personal funds), roll back the whole transaction
        (same InsufficientFundsSignal-style internal control-flow exception pattern
        TreasuryServiceImpl/ClaimServiceImpl already use elsewhere in this project — read
        one of those for the exact shape) and surface a NEW
        InsufficientFundsForRealmCreationException (${PACKAGE}.service.exception package,
        matching the existing exception-per-failure-mode style) instead of the generic
        RealmPersistenceException.
     b. Insert the realm row WITH its nexus_world/x/y/z already set (per Stage 1's
        RealmDao change) — do this BEFORE seeding ranks/membership, same order as today.
     c. Seed the 3 default ranks + leader membership exactly as today — unchanged.
     d. Auto-claim the Nexus's own chunk as the realm's first claim, for FREE
        (price_paid_cents = 0) — this needs ${PACKAGE}.persistence.dao.RealmClaimDao
        (constructor-inject it into RealmServiceImpl now; check FlameRealmsPlugin's
        current wiring order in a later stage, don't worry about wiring here) and
        ${PACKAGE}.domain.ChunkCoordinate/RealmClaim (read them first). Chunk coordinates
        from a block position: chunkX = Math.floorDiv(nexusX, 16), chunkZ =
        Math.floorDiv(nexusZ, 16) (NOT integer division/bit-shift — floorDiv handles
        negative coordinates correctly, unlike >> 4 combined with naive truncating
        division). Insert one RealmClaim row for (nexusWorld, chunkX, chunkZ,
        pricePaidCents=0). This claim needs NO contiguity check and NO permission check —
        it is the realm's founding act, not an ordinary purchase; do not route it through
        ClaimServiceImpl at all, insert it directly here in the same transaction as
        everything else, since "realm creation, its fee, its Nexus, and its first claim
        are one atomic act" is the whole point of this change.
   PricingConfig must now be a constructor dependency of RealmServiceImpl (it wasn't
   before) — add it.
   After the transaction commits, in the existing .thenApply(...) continuation: keep the
   existing realmCache.put(realm)/realmCache.putMember(...) calls, and ALSO call
   realmCache.addClaim(realm.id(), new ChunkCoordinate(nexusWorld, chunkX, chunkZ)) so the
   founding claim is reflected in the cache immediately, matching ClaimServiceImpl's own
   "cache mutation strictly after commit" convention. A later stage of this workflow adds
   Nexus-location tracking to RealmCache itself (for the indestructibility listener) —
   you do not need to add that here, just make sure your new createRealm still compiles
   against RealmCache's CURRENT public API; if that later stage's RealmCache addition
   isn't present yet when you build, that's expected, don't invent it yourself.

2. Add "CompletableFuture<Void> kick(long realmId, UUID actor, UUID target)" to
   RealmService/RealmServiceImpl:
   - Fast-fail against RealmCache.get(realmId) with RealmNotFoundException, same
     pattern as every other method here.
   - Inside one transaction: resolve actor's membership+rank, require
     RealmPermission.KICK (exact same requireXxxPermission-in-transaction shape
     TreasuryServiceImpl/ClaimServiceImpl already use for their own permission checks —
     match it, do not invent a new shape).
   - Resolve target's membership; if target isn't a member of realmId, throw
     PlayerNotInRealmException(target).
   - If target equals realm.leaderUuid(), reject with a NEW
     CannotKickLeaderException(target) (matching this project's exception-per-failure
     style) — a leader must transfer leadership or disband, never be kicked.
   - Delete target's realm_members row (realmMemberDao.delete(conn, realmId, target)).
   After commit: realmCache.removeMember(target). (WorldGuard region resync for the
   kicked player is handled by RealmActions in a later stage of this workflow — this
   service method does not touch Bukkit/WorldGuard at all, by design.)

3. Add "CompletableFuture<Void> transferLeadership(long realmId, UUID currentLeader,
   UUID newLeader)" to RealmService/RealmServiceImpl:
   - Fast-fail RealmNotFoundException as usual.
   - If realm.leaderUuid() != currentLeader, throw NotRealmLeaderException(currentLeader,
     realmId) (existing exception, reuse it).
   - Inside one transaction: resolve newLeader's membership; if not a member of realmId,
     throw PlayerNotInRealmException(newLeader). If newLeader already equals
     currentLeader, that's a no-op — still succeed (idempotent), don't treat "transfer
     to yourself" as an error worth a dedicated exception.
   - realmDao needs a new method to update leader_uuid — add
     "void updateLeader(Connection connection, long realmId, UUID newLeaderUuid) throws
     SQLException" to RealmDao/JdbcRealmDao yourself now (Stage 1 didn't add this one).
   - Swap ranks: look up the realm's "Leader" rank (findByRealmAndName(conn, realmId,
     "Leader")) and give it to newLeader (realmMemberDao.updateRank); give currentLeader
     whatever rank newLeader previously held BEFORE the swap (i.e. actually SWAP the two
     members' rank_id values — read newLeader's pre-swap rank_id first, then apply both
     updates). realms/claims/treasury are otherwise completely untouched, per this
     project's own TODO.md description of this feature ("realm/claims/treasury/etc. all
     stay intact, only realms.leader_uuid and the two members' ranks change").
   After commit: realmCache.put(...) with an updated Realm record reflecting the new
   leaderUuid (construct a new Realm via its record's "with new leaderUuid, everything
   else unchanged" — records have no built-in "with", just construct a new one
   positionally with the same other field values).

4. Replace the in-memory "pendingInvites" ConcurrentHashMap entirely with the new
   RealmInviteDao from Stage 1 (constructor-inject it):
   - "invite(long realmId, UUID inviter, UUID target)" — currently synchronous
     (returns void); change its signature to
     "CompletableFuture<Void> invite(long realmId, UUID inviter, UUID target)", fast-fail
     RealmNotFoundException as today, then dispatch through AsyncDatabaseExecutor to call
     realmInviteDao.insert(conn, realmId, target) (no permission check here, same as
     today — that enforcement still belongs to the command layer per this class's
     existing Javadoc note, unchanged).
   - "join(UUID player, long realmId)": replace the
     "pendingInvites.get(realmId).contains(player)" check with an async
     realmInviteDao.exists(conn, realmId, player) check INSIDE the same transaction the
     join already runs in (so the invite-check and the membership-insert commit
     together), throwing NotInvitedException(player, realmId) if false. On successful
     join, also realmInviteDao.delete(conn, realmId, player) in the same transaction
     (consuming the invite), replacing today's post-commit
     "pendingInvites.get(realmId).remove(player)" cleanup.
   - disbandRealm: realm_invites.realm_id cascades via ON DELETE CASCADE (Stage 1's
     migration), so remove the now-unnecessary manual "pendingInvites.remove(realmId)"
     cache cleanup line entirely — there is no more in-memory invite map to clean up.
   Update the class's own Javadoc: its current "Invites are in-memory only for M1" note
   is no longer true — say so plainly, and update the paragraph explaining why
   invite()/join() are now BOTH async (the underlying storage moved from an in-memory
   map to the database).

5. Update the RealmService interface to match every signature change above exactly
   (createRealm's new params, kick, transferLeadership, invite's new async return type).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: the complete final
RealmService interface (every method signature, verbatim), and confirm the
RealmServiceImpl constructor's full final parameter list (RealmClaimDao/RealmInviteDao/
PricingConfig/LedgerDao all being NEW dependencies it didn't have before) — every later
stage needs this exact list to update FlameRealmsPlugin's wiring correctly.
`, { label: 'realm-service', phase: 'RealmService rewrite' })

// ---------------------------------------------------------------------------
// Stage 3 — RealmActions & RealmCommand: kick/setrank/transfer, Beacon check,
// per-player action lock, /realm reload
// ---------------------------------------------------------------------------
phase('RealmActions & commands')
const actionsCommands = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmService report: """${typeof realmService === 'string' ? realmService : JSON.stringify(realmService)}"""

Read src/main/java/com/flamerealms/command/realm/RealmActions.java,
src/main/java/com/flamerealms/command/realm/RealmCommand.java,
src/main/java/com/flamerealms/config/Messages.java, and
src/main/resources/messages.yml IN FULL before changing anything.

1. Fix every call site broken by RealmService's rewritten signatures (from the report
   above): createRealm now needs a Nexus location, invite/join semantics are unchanged
   from the CALLER's perspective except invite() is now async. Specifically rewrite
   RealmActions.createRealm(Player player, String name):
   - BEFORE calling realmService.createRealm at all: check the block directly beneath
     the player's feet — "org.bukkit.block.Block standFoot =
     player.getLocation().subtract(0, 1, 0).getBlock();" — is
     org.bukkit.Material.BEACON. If not, send a NEW messages.yml-backed error (add the
     key yourself, matching this file's existing style, e.g.
     "realm-create-requires-beacon": something like "<red>You must stand on a placed
     Beacon block to found a realm.</red>") and return WITHOUT calling the service at
     all — no fee is ever attempted if there's no Beacon.
   - If there is a Beacon: call the async
     realmService.createRealm(leaderId, name, world, x, y, z) using that Beacon block's
     own world name + block X/Y/Z (the Beacon's own coordinates, i.e.
     standFoot.getWorld().getName()/getX()/getY()/getZ() — NOT the player's own
     fractional location), exactly as before but async now instead of already being
     async (createRealm was already a CompletableFuture — only its parameter list
     changed), with the same runSync/success/exceptionally message-sending shape as
     today. Add a new success message noting the fee paid and Nexus/first-claim
     established if you think it improves clarity, but do not remove or reword the
     existing "realm-created" message's placeholder ("name") — just decide whether to
     extend it or add a second message sent alongside it, your call, briefly justify
     whichever you pick in your report.
   - RealmNameTakenException/PlayerAlreadyInRealmException handling in describeError()
     is unchanged. Add a case for the new InsufficientFundsForRealmCreationException
     (from the RealmService report above) -> a new messages.yml key, e.g.
     "error-insufficient-funds-for-realm-creation": "<red>You don't have enough money to
     found a realm (costs <fee>).</red>" — if describeError()'s signature doesn't
     currently support a placeholder like <fee> (check — it likely just returns a plain
     Component per exception type with no dynamic data), either add the fee amount as
     a static reference (read it from pricingConfig, which RealmActions/RealmCommand
     already has access to per Stage 1/2 wiring) via Placeholder.unparsed, or keep it
     generic with no dynamic amount — your call, prefer showing the actual fee if it's a
     small, clean change; don't force it if describeError()'s existing shape makes that
     awkward.
   - Update invite()'s caller (wherever RealmCommand/RealmActions currently calls the
     now-async realmService.invite(...) synchronously) to handle the CompletableFuture
     properly (dispatch, hop back via runSync before sending success/failure messages) —
     read the current invite flow in RealmCommand/RealmActions fully first; this used to
     be a synchronous call wrapped in a try/catch inside an async permission-check
     continuation — now it needs its own async continuation chained after that
     permission check succeeds, still all inside the existing runSync(...) discipline.

2. Add three new RealmActions methods, following this class's existing patterns exactly
   (permission/existence pre-checks done by the CALLER where they're genuinely
   argument-resolution concerns — read RealmActions's existing invite(Player,Player)
   method for the exact division of labor between RealmCommand's argument-resolution and
   RealmActions's business logic):
   - "kick(Player actor, UUID targetUuid, String targetDisplayName)" — actor must be in
     a realm (not-in-realm message otherwise); calls realmService.kick(realmId, actor,
     targetUuid); on success send a new "kick-success" message (with the target's
     display name placeholder); on failure, describeError() must now handle
     CannotKickLeaderException too (new messages.yml key,
     "error-cannot-kick-leader": "<red>You cannot kick a realm's leader — transfer
     leadership or disband instead.</red>"). ALSO resync WorldGuard region membership
     for every claim of that realm once the kick commits — this needs the SAME
     fetchClaimResync/applyClaimResync-style helpers RealmActions already has for
     join/leave (read them, reuse the exact pattern: fetch realmCache.claimsOf(realmId)
     + realmService.getMemberUuids(realmId) post-commit, then
     claimProtectionService.protectClaim(...) for each claim with the now-smaller member
     set — the kicked player is simply absent from that fresh list, no special-casing
     needed).
   - "setRank(Player actor, UUID targetUuid, String targetDisplayName, String
     rankName)" — resolve the rank by name via
     realmRankDao.findByRealmAndName(conn, realmId, rankName) — wait, RealmActions does
     not currently have direct DAO access wired for a synchronous pre-check the way it
     does for e.g. hasInvitePermission's dispatched read; since rank resolution needs a
     DB read too, dispatch it through asyncDatabaseExecutor (RealmActions already has
     this field) the same way hasInvitePermission does, resolve the rank id, then call
     realmService.setRank(realmId, actor.getUniqueId(), targetUuid, rankId) — chain
     these two async steps together (.thenCompose(...)), applying the final result back
     via runSync. If the named rank doesn't exist for this realm, send a new
     "error-rank-not-found" message ("<red>No rank named <rank> exists in your
     realm.</red>") without ever calling realmService.setRank at all.
   - "transfer(Player currentLeader, UUID newLeaderUuid, String newLeaderDisplayName)" —
     calls realmService.transferLeadership(realmId, currentLeader.getUniqueId(),
     newLeaderUuid); on success send a new "transfer-success" message (with the new
     leader's display name); describeError() needs no new case (NotRealmLeaderException/
     PlayerNotInRealmException already exist and cover this method's failure modes).

3. Add three new /realm subcommands to RealmCommand, following its EXACT existing style
   for argument-resolution-then-delegate (read buildInvite()/executeInvite() as the
   closest existing template — an offline-target-tolerant variant, since kick/setrank/
   transfer should all work against a realm member who might currently be OFFLINE,
   unlike invite's online-only target):
   - "kick <player>" (permission node "flamerealms.command.kick") — resolve the target
     name to a UUID+display-name via a DISPATCHED (async, off the main thread — this can
     block on disk for an offline player, exactly the bug this project already fixed
     once for /realm info's leader-name lookup, so do NOT call
     Bukkit.getOfflinePlayer(name) synchronously here) lookup, following the SAME
     "wrap Bukkit.getOfflinePlayer in Bukkit.getScheduler().runTaskAsynchronously(...)
     then hop back via runSync" pattern RealmActions.showInfo already uses for exactly
     this reason (read it to copy the pattern precisely) — if no player has ever been
     seen by this server under that name, send a clear "player not found" message
     (reuse or add a messages.yml key as appropriate) instead of calling
     actions.kick(...).
   - "setrank <player> <rank>" (permission node "flamerealms.command.setrank") — same
     offline-tolerant name resolution as kick, then calls actions.setRank(...).
   - "transfer <player>" (permission node "flamerealms.command.transfer") — same
     resolution, then calls actions.transfer(...).
   Add all three permission nodes to plugin.yml's permissions: block (default true),
   matching every existing subcommand's entry style exactly, plus add them to the
   flamerealms.command.* umbrella's children list. Update messages.yml's "usage" key to
   include kick/setrank/transfer.

4. Per-player in-flight-action lock: add a
   "private final java.util.Set<UUID> busyPlayers =
   java.util.concurrent.ConcurrentHashMap.newKeySet();" field to RealmActions. At the
   START of createRealm, deposit, withdraw, confirmClaim, and unclaim (exactly these
   five — the "every money-moving command, M2 onward" ones per this project's own
   TODO.md, NOT kick/setrank/transfer/join/leave, which aren't money-moving): if
   "!busyPlayers.add(player.getUniqueId())", the player already has one of these five
   in flight — send a new messages.yml key ("action-in-progress": "<red>Your previous
   action is still processing, please wait.</red>") and return immediately WITHOUT
   dispatching anything. If the add succeeded (lock acquired), ensure
   "busyPlayers.remove(player.getUniqueId())" runs exactly once no matter how that
   method's CompletableFuture chain ends (success, expected failure like insufficient
   funds, or an exceptional failure) — the cleanest way is almost certainly a
   ".whenComplete((ignored, ignoredError) -> busyPlayers.remove(playerId))" appended to
   the very end of each of these five methods' existing chain, since whenComplete runs
   regardless of outcome and doesn't swallow/alter the result the way a bare
   .thenApply/.exceptionally would. Double-check each of these five methods actually has
   exactly one exit path per successful/failed dispatch (several of them return early
   with a synchronous message-and-return BEFORE ever reaching the async dispatch, e.g.
   "not in a realm" — for those early-return paths, do NOT acquire the lock at all,
   since nothing is actually being dispatched; only wrap the lock around the branch that
   genuinely dispatches an async action).

5. "/realm reload" (permission node "flamerealms.admin" — the existing reserved
   admin-only node, do not create a new one): reloads messages.yml ONLY for this pass.
   Give Messages (read Messages.java first) a new public "reload()" method — if Messages
   already re-reads its backing FileConfiguration on every get() call rather than
   caching parsed values at construction, reload() is as simple as re-loading that same
   FileConfiguration object in place (so every class already holding a reference to this
   SAME Messages instance picks up the change immediately, no wrapper/indirection
   needed); if Messages instead bakes values into fields at construction, you will need
   to restructure it minimally so reload() actually takes effect for every existing
   holder — read it carefully before assuming which case applies. Add a new
   RealmCommand subcommand "reload" that calls messages.reload() synchronously (no
   database/Bukkit-blocking concern here, it's a local file read) and sends a
   plain confirmation message (does not need to go through Messages itself, a
   locally-built Component is fine for this one bootstrap-adjacent message, since if
   messages.yml itself is broken you don't want the confirmation to depend on it).
   Explicitly do NOT attempt to reload pricing.yml/gui.yml/config.yml in this pass —
   note in your report that this is a deliberate, documented scope-down (those would
   need every downstream holder of PricingConfig/GuiConfig to be restructured behind a
   mutable indirection, out of scope for this workflow) rather than silently pretending
   to support it.

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files
created/edited, every new messages.yml key you added (exact strings), every new
permission node, and the final RealmActions constructor parameter list (unchanged from
before, or did you need to add anything new? — the Nexus-registration listener in the
next stage of this workflow needs to know if RealmCache gained any new methods you
relied on here).
`, { label: 'actions-commands', phase: 'RealmActions & commands' })

// ---------------------------------------------------------------------------
// Stage 4 — Nexus indestructibility + upkeep-debt chunk release
// ---------------------------------------------------------------------------
phase('Nexus protection & upkeep consequence')
const nexusUpkeep = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
RealmActions/commands report: """${typeof actionsCommands === 'string' ? actionsCommands : JSON.stringify(actionsCommands)}"""

Read src/main/java/com/flamerealms/cache/RealmCache.java,
src/main/java/com/flamerealms/service/UpkeepService.java,
src/main/java/com/flamerealms/FlameRealmsPlugin.java, and
src/main/java/com/flamerealms/domain/Realm.java (post Stage 1's nexus fields) IN FULL
before writing anything.

## Part A — Nexus indestructibility

1. Extend RealmCache with Nexus-location tracking, following its EXISTING
   write-through/warm-up-once conventions exactly (read its claim-tracking methods —
   claimsOf/ownerOf/addClaim/removeClaim/loadClaims — as the closest template, since
   this is the same shape of problem: "a small, fast, in-memory lookup populated at
   startup and kept in sync by post-commit writes"):
   - A Set<${PACKAGE}.domain.ChunkCoordinate>-equivalent is NOT right here — a Nexus is
     one exact BLOCK, not a whole chunk, and two realms could in principle have Nexuses
     in the same chunk (contiguity only prevents chunk-level claim overlap, it says
     nothing about exact block coordinates within different chunks/realms). Add a small
     new record, e.g. ${PACKAGE}.domain.BlockCoordinate(String world, int x, int y, int
     z) (plain data, no Bukkit types, matching ChunkCoordinate's own style exactly —
     equals/hashCode from the record, case-sensitive world name comparison) if one
     doesn't already fit; then track "Set<BlockCoordinate> nexusLocations" (you don't
     even need to know WHICH realm owns a given Nexus for the indestructibility check
     below — "is this exact block ANY realm's Nexus" is all that's needed).
   - "loadNexusLocations(Connection connection) throws SQLException" — a warm-up query
     "SELECT nexus_world, nexus_x, nexus_y, nexus_z FROM realms WHERE disbanded_at IS
     NULL AND nexus_world IS NOT NULL", called alongside the existing loadAll()/
     loadClaims() at startup (wire this into FlameRealmsPlugin's existing cache
     warm-up submit(...) call — read its current exact shape first).
   - "isNexus(BlockCoordinate location) -> boolean" (or equivalent) — O(1) lookup.
   - "addNexus(BlockCoordinate location)" / "removeNexus(BlockCoordinate location)" —
     post-commit write-through mutators, same contract as every other mutator on this
     class (called only after the corresponding transaction has committed). Wire
     addNexus into RealmServiceImpl.createRealm's post-commit continuation (Stage 2
     already built createRealm — read its current final shape and add this one line to
     its existing .thenApply(...), alongside the realmCache.put/putMember/addClaim calls
     already there) and removeNexus into RealmServiceImpl.disbandRealm's post-commit
     continuation (a disbanded realm's Nexus becomes an ordinary, breakable block again —
     read disbandRealm's current .thenApply(...) and add one line there for the
     disbanding realm's own nexus location, which you can read off the Realm object
     already in scope there before the disband call, same "capture before, act after"
     shape RealmActions already uses for its own claim-resync-before-leave logic).

2. New ${PACKAGE}.protection.NexusProtectionListener implements
   org.bukkit.event.Listener, taking a RealmCache reference. One @EventHandler on
   org.bukkit.event.block.BlockBreakEvent: if
   "realmCache.isNexus(new BlockCoordinate(block.getWorld().getName(), block.getX(),
   block.getY(), block.getZ()))" is true AND the breaking player does NOT hold
   "flamerealms.admin" (Player#hasPermission — this is the one, existing, reserved
   admin-only permission node in plugin.yml, do not invent a second one), cancel the
   event outright (event.setCancelled(true)) and send the player a new messages.yml
   key, e.g. "error-nexus-indestructible": "<red>A realm's Nexus cannot be broken.</red>"
   (this class needs a Messages reference too, for that one message — constructor-inject
   it). Register this listener once in FlameRealmsPlugin.onEnable() via
   getServer().getPluginManager().registerEvents(...), matching how GuiManager/
   ChatInputService are already registered there.

## Part B — Upkeep-debt chunk release

Extend UpkeepService's per-realm chargeUpkeep(...) transaction (read its current exact
shape first — Stage 1 already added RealmDao.incrementUnpaidUpkeepCycles/
resetUnpaidUpkeepCycles/findUnpaidUpkeepCycles for you to use here):
- On a SUCCESSFUL full payment (the existing "paid" branch that already calls
  realmDao.resetUpkeepDebt(...)): also call realmDao.resetUnpaidUpkeepCycles(conn,
  realmId) in the same transaction.
- On a FAILED payment (the existing "not paid, increment debt" branch): also call
  realmDao.incrementUnpaidUpkeepCycles(conn, realmId), then read the new count back via
  realmDao.findUnpaidUpkeepCycles(conn, realmId) (or just track it locally as
  previousCount+1 if that's simpler/avoids an extra read — your call). If that count
  now EXCEEDS pricingConfig.debtReleaseThresholdCycles() (Stage 1 added this accessor),
  release exactly ONE of the realm's claims THIS SAME CYCLE, in the SAME transaction:
  pick the claim with the latest claimed_at (add a method to RealmClaimDao if one
  doesn't already exist for "find the most-recently-claimed chunk for a realm" — check
  its current interface first, add "Optional<RealmClaim> findMostRecentByRealm(
  Connection connection, long realmId) throws SQLException" if needed) and delete it
  (realmClaimDao.delete(conn, realmId, claim.world(), claim.chunkX(), claim.chunkZ())).
  If the realm has zero claims left to release, skip silently (nothing left to lose,
  the unpaid-cycles count still keeps climbing but there is nothing further this
  mechanism can do about it — do not treat this as an error).
  IMPORTANT: this transaction runs OFF the main thread (inside
  AsyncDatabaseExecutor.submit(...), same as the rest of UpkeepService) — the actual
  realmCache.removeClaim(...) / any WorldGuard unprotect call MUST NOT happen inside
  this transaction's work function. Instead, have chargeUpkeep(...)'s CompletableFuture
  chain (after the transaction commits) apply realmCache.removeClaim(...) for the
  released claim if one was released (UpkeepService already has a RealmCache reference)
  — but do NOT call ClaimProtectionService.unprotectClaim(...) from UpkeepService itself
  (UpkeepService is part of the com.flamerealms.service package and must stay
  Bukkit/WorldGuard-free per this project's strict layering rule — read
  ClaimServiceImpl's own class Javadoc for why). Instead, return enough information from
  chargeUpkeep's future (e.g. an Optional<ChunkCoordinate> of what got released, if
  anything) so that whatever OWNS the actual WorldGuard unprotect call can do it; since
  UpkeepService's daily cycle currently has no Bukkit-aware caller reading its results
  (runUpkeepCycle's aggregate future is fire-and-forget from a scheduled task, per its
  own existing Javadoc), the cleanest fix given that constraint: have UpkeepService ALSO
  take a ${PACKAGE}.protection.ClaimProtectionService reference as a constructor
  parameter is NOT allowed (breaks the layering rule above) — instead, thread a small
  functional callback through instead: add a constructor parameter
  "java.util.function.BiConsumer<String, ${PACKAGE}.domain.ChunkCoordinate>
  onClaimReleased" (or similarly named) that UpkeepService invokes, wrapped in
  Bukkit.getScheduler().runTask(plugin, () -> ...) (UpkeepService already has a
  JavaPlugin reference for its own scheduled task registration, reuse it) whenever this
  mechanism actually releases a claim, and have FlameRealmsPlugin wire that callback (in
  a later stage of this workflow, when it updates the UpkeepService construction call
  site) to call claimProtectionService.unprotectClaim(...) — this keeps
  UpkeepService itself free of any WorldGuard/protection-package dependency, matching
  every other service class in this project, while still letting the actual
  unprotect happen. Document this callback's purpose clearly in UpkeepService's class
  Javadoc (it already has an extensive one — extend it, don't replace it).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files
created/edited, RealmCache's exact new Nexus-tracking API, UpkeepService's new
constructor parameter (the callback) and its exact type, and confirm
FlameRealmsPlugin's UpkeepService construction call site and cache-warmup call site will
both need updating in a later stage (do not update FlameRealmsPlugin.java yourself in
this stage unless it's a trivial one-line addition you're confident about — the wiring
stage after this one owns getting the full picture right).
`, { label: 'nexus-upkeep', phase: 'Nexus protection & upkeep consequence' })

// ---------------------------------------------------------------------------
// Stage 5 — GUI wiring: Kick/SetRank/Transfer + member/rank selector menus,
// plus FlameRealmsPlugin wiring for everything from stages 1-4
// ---------------------------------------------------------------------------
phase('GUI wiring')
const guiWiring = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Reports so far:
RealmService: """${typeof realmService === 'string' ? realmService : JSON.stringify(realmService)}"""
RealmActions/commands: """${typeof actionsCommands === 'string' ? actionsCommands : JSON.stringify(actionsCommands)}"""
Nexus/upkeep: """${typeof nexusUpkeep === 'string' ? nexusUpkeep : JSON.stringify(nexusUpkeep)}"""

Read src/main/java/com/flamerealms/gui/MainMenu.java,
src/main/java/com/flamerealms/gui/PlayerSelectorMenu.java,
src/main/java/com/flamerealms/gui/RealmSelectorMenu.java,
src/main/java/com/flamerealms/gui/GuiConfig.java,
src/main/resources/gui.yml, and src/main/java/com/flamerealms/FlameRealmsPlugin.java
IN FULL before changing anything — FlameRealmsPlugin in particular has now accumulated
several new constructor dependencies across the last three stages of this workflow
(RealmClaimDao/RealmInviteDao/PricingConfig/LedgerDao into RealmServiceImpl, a new
callback into UpkeepService, ClaimProtectionService already existed) — read its CURRENT
actual state, do not assume the shape from any earlier report above is still exactly
right.

## Part A — FlameRealmsPlugin wiring (do this FIRST, before the GUI additions below,
so you have a compiling baseline to add menu items onto)

1. Update the RealmServiceImpl construction call site with its new constructor
   parameters (RealmClaimDao/RealmInviteDao instance — construct
   JdbcRealmInviteDao/reuse the existing RealmClaimDao instance already constructed
   earlier in onEnable/PricingConfig, already loaded earlier in onEnable/LedgerDao,
   already constructed earlier in onEnable).
2. Update the UpkeepService construction call site with its new callback parameter,
   wiring it to call claimProtectionService.unprotectClaim(world, chunkX, chunkZ) inside
   a Bukkit.getScheduler().runTask(this, ...) hop (UpkeepService's own report says it
   already wraps the callback invocation in runTask itself — check whether that's
   actually true by reading its final code; if UpkeepService already hops to the main
   thread before invoking the callback, your callback body here does not need to
   re-hop; if it does NOT, wrap it yourself here).
3. Add the Nexus-location cache warm-up call (realmCache.loadNexusLocations(connection))
   alongside the existing loadAll(connection)/loadClaims(connection) calls in onEnable's
   cache-warming submit(...) block.
4. Construct and register ${PACKAGE}.protection.NexusProtectionListener (needs
   RealmCache + Messages) via getServer().getPluginManager().registerEvents(...).
5. Update RealmCommand's construction call site for whatever new fields/methods it
   needs per the RealmActions/commands report (kick/setrank/transfer wiring, the
   Messages#reload() method needing no new plugin-level wiring since it operates on the
   already-injected Messages instance).
6. Confirm the whole project compiles at this checkpoint before moving to Part B — run
   "./gradlew compileJava" now and fix anything broken before continuing. Do not wait
   until the final verification stage to discover a wiring mistake this early.

## Part B — GUI: Kick, SetRank, Transfer

Add three more entries to the shipped src/main/resources/gui.yml under
"main-menu.items" (pick reasonable unused slots within the existing 27-slot main menu —
read the current file to see which slots 0-26 are still free; if none are free, you may
need to slightly rearrange, but PREFER finding free slots over displacing an existing
item's position, since that would surprise anyone who already customized their own
gui.yml on an existing install):
  - "kick": e.g. material LEATHER_BOOTS (or your own sensible choice), name
    "<red>Kick Member</red>", lore describing what it does.
  - "setrank": e.g. material NAME_TAG, name "<gold>Set Rank</gold>".
  - "transfer": e.g. material TOTEM_OF_UNDYING (or similar "leadership" flavored
    item — your call), name "<gold>Transfer Leadership</gold>", lore noting this
    hands over the realm without disbanding it.

Wire all three into MainMenu.java's action dispatch (read its existing switch/lookup
shape for the 13 already-wired actions and match it exactly):
  - "kick" -> close inventory, open a member-selector menu. Build a NEW
    ${PACKAGE}.gui.MemberSelectorMenu, following PlayerSelectorMenu's exact existing
    structure/conventions (same list-menu chrome from GuiConfig.listMenu(), same 45-entry
    cap with an explicitly-noted pagination-out-of-scope simplification, same
    empty-state handling) but listing the VIEWER'S OWN realm's current members
    EXCLUDING the viewer themselves and EXCLUDING the realm's leader (a leader cannot be
    kicked per RealmService.kick's own guard — filtering them out of the list here is a
    better UX than letting the click fail with an error afterward, though
    RealmActions.kick(...) still correctly rejects it server-side regardless, per this
    project's existing "GUI selector lists only pre-validated choices, but the service
    layer is still the actual authority" pattern already established by
    PlayerSelectorMenu/RealmSelectorMenu). You need every member's UUID+display name —
    use realmService.getMemberUuids(realmId) (already exists) then resolve each UUID's
    display name via Bukkit.getOfflinePlayer(uuid).getName() (same null-fallback pattern
    RealmActions.showInfo already uses) — since this touches potentially-offline
    players' names, and Bukkit.getOfflinePlayer(UUID) (not the String-name overload) is
    generally safe/fast for cache-hit players but can still block for a true cache miss,
    follow the SAME async-then-runSync pattern already established elsewhere in this
    project for exactly this reason: dispatch the whole "resolve every member's display
    name" step via Bukkit.getScheduler().runTaskAsynchronously(...), THEN build/open the
    menu back on the main thread via runSync. Clicking an entry closes the inventory and
    calls realmActions.kick(viewer, thatMemberUuid, thatMemberDisplayName).
  - "setrank" -> close inventory, open the SAME kind of member-selector menu as kick
    (you can reuse MemberSelectorMenu, but this time do NOT exclude the leader — you can
    set someone else's rank without it being a kick), then ON SELECTING a member, open a
    SECOND new ${PACKAGE}.gui.RankSelectorMenu (same list-menu chrome) listing that
    realm's ranks via realmRankDao... actually RealmActions does not expose direct DAO
    access to the GUI layer (by design) — you need a way to list a realm's ranks from
    the GUI/command layer without reaching into a DAO directly. Check whether
    RealmActions or RealmService already expose anything rank-listing-shaped from
    earlier stages of this workflow; if not, add ONE new method to RealmService/
    RealmServiceImpl: "CompletableFuture<List<RealmRank>> getRanks(long realmId)"
    (trivial pass-through to RealmRankDao.findAllByRealm inside an
    AsyncDatabaseExecutor.submit(...), same fast-fail-on-unknown-realm pattern as
    getMemberUuids) — this is a small, narrowly-scoped addition, add it directly rather
    than reaching around the service layer. Clicking a rank in RankSelectorMenu closes
    the inventory and calls realmActions.setRank(viewer, targetMemberUuid,
    targetMemberDisplayName, thatRank.name()).
  - "transfer" -> close inventory, open a member-selector menu (reuse MemberSelectorMenu,
    excluding the viewer themselves but NOT excluding anyone else — you genuinely can
    transfer to any existing member), then open a ConfirmMenu ("Transfer leadership to
    <name>? This cannot be undone by you alone.") whose onConfirm calls
    realmActions.transfer(viewer, selectedMemberUuid, selectedMemberDisplayName).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files
created/edited, confirm the project compiles after Part A (paste the actual command
output's final line), and the exact new gui.yml slots/keys you chose for kick/setrank/
transfer.
`, { label: 'gui-wiring', phase: 'GUI wiring' })

// ---------------------------------------------------------------------------
// Stage 6 — Tests
// ---------------------------------------------------------------------------
phase('Tests')
const tests = await agent(`
Continuing work on the FlameRealms plugin (package ${PACKAGE}) in the current directory.
Everything from this workflow (schema/DAO, RealmService rewrite, RealmActions/commands,
Nexus protection, upkeep-debt release, GUI wiring) now exists. Reports:

Schema/DAO: """${typeof schemaDao === 'string' ? schemaDao : JSON.stringify(schemaDao)}"""
RealmService: """${typeof realmService === 'string' ? realmService : JSON.stringify(realmService)}"""
RealmActions/commands: """${typeof actionsCommands === 'string' ? actionsCommands : JSON.stringify(actionsCommands)}"""
Nexus/upkeep: """${typeof nexusUpkeep === 'string' ? nexusUpkeep : JSON.stringify(nexusUpkeep)}"""
GUI wiring: """${typeof guiWiring === 'string' ? guiWiring : JSON.stringify(guiWiring)}"""

Read src/test/java/com/flamerealms/service/RealmServiceImplTest.java and
src/test/java/com/flamerealms/service/fake/FakeRealmDao.java (and any other Fake*Dao
that now needs a matching new method — FakeRealmClaimDao, FakeRealmRankDao, and a brand
new FakeRealmInviteDao) IN FULL first, to match this project's exact existing
hand-written-fake testing conventions (no Mockito for DAOs, real *ServiceImpl instances
wired to fakes plus InlineAsyncDatabaseExecutors).

Add/extend tests covering, at minimum:
1. RealmServiceImplTest: createRealm charges the creation fee AND fails atomically
   (no realm row, no rank rows, no membership row, no claim row) when the leader can't
   afford it — extend/add a FakeLedgerDao-backed assertion exactly like
   TreasuryServiceImplTest already does for its own insufficient-funds cases. createRealm
   succeeds and the Nexus's chunk ends up as the realm's first claim with
   price_paid_cents == 0 (assert via FakeRealmClaimDao). kick rejects kicking the leader,
   rejects an actor lacking KICK, and succeeds otherwise (member row removed).
   transferLeadership swaps leader_uuid and the two members' ranks correctly, rejects a
   non-leader caller, rejects a target not in the realm. invite/join now round-trip
   through FakeRealmInviteDao instead of an in-memory map — update any existing
   invite/join tests that assumed the old in-memory pendingInvites shape.
2. A focused test for UpkeepService's new debt-release mechanism: after
   pricingConfig.debtReleaseThresholdCycles() consecutive failed-payment cycles, the
   realm's most-recently-claimed chunk is removed (assert via FakeRealmClaimDao) and the
   release callback fires with the right ChunkCoordinate; a realm that pays successfully
   at any point has its unpaid-cycle counter reset to 0 (assert via FakeRealmDao) and no
   further releases happen.
3. If RealmActionsTest already exists (it does, from an earlier workflow) and covers a
   representative sample of RealmActions methods for parity with the service layer, add
   at least one more representative case for kick/setRank/transfer confirming
   RealmActions is a correct thin pass-through (same "verify a couple of representative
   methods, don't re-test business logic" scope as that file's existing tests) —
   including confirming the per-player in-flight-action lock actually rejects a second
   concurrent-looking call for one of the five money-moving methods (e.g. call
   deposit(...) twice back-to-back without letting the first complete first, if your
   test's InlineAsyncDatabaseExecutors setup makes that meaningfully testable — if it
   completes synchronously inline such that there's no real "in flight" window to test
   this way, say so explicitly in your report rather than writing a test that can't
   actually fail if the lock were removed).

You do not need to read PROJECT.md or TECHNICAL_SPEC.md. Report back: files created, and
explicitly confirm whether you were able to run the fake-DAO unit tests in this
environment and whether they passed.
`, { label: 'nexus-tests', phase: 'Tests' })

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
current directory, covering everything built across this large workflow (Nexus
placement, realm creation fee, kick/setrank/transfer, persisted invites, upkeep-debt
chunk release, per-player action lock, messages.yml reload, GUI wiring) on top of all
prior work:

Schema/DAO: """${typeof schemaDao === 'string' ? schemaDao : JSON.stringify(schemaDao)}"""
RealmService: """${typeof realmService === 'string' ? realmService : JSON.stringify(realmService)}"""
RealmActions/commands: """${typeof actionsCommands === 'string' ? actionsCommands : JSON.stringify(actionsCommands)}"""
Nexus/upkeep: """${typeof nexusUpkeep === 'string' ? nexusUpkeep : JSON.stringify(nexusUpkeep)}"""
GUI wiring: """${typeof guiWiring === 'string' ? guiWiring : JSON.stringify(guiWiring)}"""
Tests: """${typeof tests === 'string' ? tests : JSON.stringify(tests)}"""

Do the following, in order:
1. Run the build (./gradlew build). Read the full error output if it fails.
2. If there are compile errors, fix them directly — you have full Read/Edit/Bash access
   to the whole project. This workflow spans SEVEN sequential stages, each depending on
   the previous one's exact final shape rather than an earlier report's paraphrase of
   it — check these seams first:
   - RealmService/RealmServiceImpl's final signatures (createRealm, kick,
     transferLeadership, invite, getRanks) actually matching every call site in
     RealmActions/RealmCommand/the GUI classes
   - FlameRealmsPlugin constructing RealmServiceImpl/UpkeepService/NexusProtectionListener
     with their real, current constructor parameter lists (these are the pieces most
     likely to have drifted across 5 sequential edits to the same files)
   - RealmCache's Nexus-tracking methods actually being called with a real
     BlockCoordinate type that compiles, from every call site (createRealm's post-commit
     continuation, disbandRealm's post-commit continuation, NexusProtectionListener,
     FlameRealmsPlugin's warm-up)
   - The UpkeepService release-callback's exact type matching both where it's declared
     (UpkeepService's constructor) and where it's supplied (FlameRealmsPlugin) and
     invoked (inside chargeUpkeep's continuation)
   - No leftover reference anywhere to the old in-memory "pendingInvites" map after
     Stage 2 removed it
   - plugin.yml's three new permission nodes (kick/setrank/transfer) actually present
     and wired into RealmCommand's requires(...) the same way every other subcommand's
     permission is
3. Once it compiles, run the unit test task (skip/exclude the Docker-dependent
   integration tests explicitly, noting whether Docker was available).
4. Manually grep-verify (no live server needed) that every one of: kick, setrank,
   transfer, and the Nexus-gated create flow has BOTH a command path (RealmCommand) and
   a GUI path (MainMenu + its selector menus) — report any that seem to have only one.
5. Report exactly what still doesn't work, if anything — do not claim something works
   if you didn't actually verify it. Anything that fundamentally cannot be verified
   without a live Paper server + WorldGuard + a real Beacon block (e.g. "does standing
   on a real Beacon actually let you create a realm in-game") should be listed as an
   explicit follow-up for live-server testing, not claimed as working.

Do not read PROJECT.md or TECHNICAL_SPEC.md unless you hit something genuinely ambiguous
that the reports above don't resolve.
`, { schema: VERIFY_SCHEMA, label: 'verify', phase: 'Verify' })

// agent() returns null if the subagent was skipped or died on a terminal API error
// (e.g. a session usage-limit cutoff) — never assume a non-null result, or a
// transient infrastructure failure crashes the whole workflow script instead of
// just being treated as "still failing, try again."
function asVerifyResult(result, note) {
  return result ?? {
    buildSucceeded: false,
    unitTestsSucceeded: false,
    summary: note,
    remainingIssues: [note],
  }
}
verify = asVerifyResult(verify, 'verify agent returned null (skipped or died) on the first attempt')

let fixAttempts = 0
while (!verify.buildSucceeded && fixAttempts < 4) {
  fixAttempts++
  log(`Build still failing, fix attempt ${fixAttempts}/4...`)
  const attempt = await agent(`
The FlameRealms build is still failing after a previous attempt. Its report:
"""${JSON.stringify(verify)}"""

Fix the remaining compile/build errors directly (Read/Edit/Bash access to the whole
project), then re-run the build and the unit test task, and report the same structured
result again (buildSucceeded, unitTestsSucceeded, summary, remainingIssues).
`, { schema: VERIFY_SCHEMA, label: `verify-fix-${fixAttempts}`, phase: 'Verify' })
  verify = asVerifyResult(attempt, `verify-fix-${fixAttempts} agent returned null (skipped or died)`)
}

log(verify.buildSucceeded
  ? `Build succeeded after ${fixAttempts} fix attempt(s). Unit tests ${verify.unitTestsSucceeded ? 'passed' : 'did NOT pass'}.`
  : `Build still failing after ${fixAttempts} fix attempt(s) — see remainingIssues.`)

return { ...verify, fixAttempts }
