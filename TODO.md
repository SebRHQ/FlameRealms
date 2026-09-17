# FlameRealms — TODO

Tracks action items from the architecture audit (see [TECHNICAL_SPEC.md](TECHNICAL_SPEC.md)). D1–D10 plus the v0.4 corrections (async DB, Nexus Capture formal model, WorldGuard, Progression, AuthMe wording, PvP friendly-fire/alliance) are now all decided. Nothing currently blocks M0.

## Realm command follow-ups (from the M0+M1 implementation workflow)

- [ ] **Leadership/ownership transfer.** M1 ships `/realm disband` as the *only* way a Leader can give up a realm — there is deliberately no `/realm transfer <player>` (or equivalent `RealmService.transferLeadership(...)`) yet. Not needed now, but tracked so it isn't mistaken for an oversight later: implement it as its own small follow-up task once M1 is verified working — a Leader hands the realm to another existing member without disbanding it (realm/claims/treasury/etc. all stay intact, only `realms.leader_uuid` and the two members' ranks change).
- [ ] Confirm `/realm accept <realmName>` (not `/realm join`) is the right long-term name for consuming an invite — it reads clearly today; revisit only if a later milestone (e.g. a public/open-join mode) needs a second, distinct command.

## Operational checks — do these before/during M0, not product decisions

- [ ] Confirm WorldGuard is installed and configured on the backend (now a **required** hard dependency, not optional) — FlameRealms's entire PvP model assumes it
- [ ] Confirm Essentials' Vault economy hook is disabled server-side before FlameRealms's economy goes live (D8 — no data migration needed, but the provider-conflict risk is still operational)
- [ ] Confirm UltimateAutoRestart issues a graceful `/stop`, not a hard kill
- [ ] Exclude FlameRealms from PlugManX's hot-reload targets
- [ ] Verify Vulcan's (anticheat) combat-frequency/movement heuristics tolerate legitimate large-scale Nexus-siege fights
- [ ] Clarify whether AxiomPaper is still part of the server's plugin list (present in an earlier topology draft, absent from the latest one, not mentioned since) — not a blocker, just an open loose end

## Schema — build in this order

- [ ] `player_wallets`, `realms.friendly_fire_enabled` (M2/M1 respectively — friendly-fire column can land alongside realm core)
- [ ] `diplomacy_states` (base state incl. `TRUCE`) — minimal slice needed by M7; full `diplomacy_agreements`/`diplomacy_proposals` land with M11
- [ ] `realm_claims` with contiguity validation (M3)
- [ ] `realm_member_activity` — presence-only at M3, contribution-signal union completes once M5's `contribution_events` exists
- [ ] `contribution_events` / `contribution_totals` (M5)
- [ ] `war_participants` + `wars.nexus_capture_percent` (M7)
- [ ] `realm_progression` (M4.5)
- [ ] `treasury_cap_cents` enforcement (config-driven, not a column) — still pending, unchanged

## Async DB correction (new, required — applies to every milestone from M2 onward)

- [ ] Stand up `AsyncDatabaseExecutor` (bounded thread pool, draws connections from HikariCP) as part of M2 — every later milestone's DAO work depends on this existing first
- [ ] Structural rule, enforced from M2 onward: no DAO method is ever called from the Paper main thread — validate/dispatch on main thread, transact on the async executor, apply results back via `Bukkit.getScheduler().runTask(...)`
- [ ] Per-realm/per-player single-flight dispatch queue (ordering/UX convenience only — correctness stays in the DB transaction, never replace one with the other)
- [ ] Write-behind fields (`wars.nexus_capture_percent`, contribution/activity/progression tables) flush on a throttled interval AND synchronously in full on graceful shutdown

## Process/flow fixes (unchanged from previous audits, now explicitly async-wrapped)

- [ ] Claim purchase: one DB transaction for withdraw + claim-insert + ledger-insert (M3)
- [ ] Shared `LedgerDao.recordAndApply(...)` helper before any service uses the ledger standalone (M2)
- [ ] Shield: weekly-count + price + insert under one transaction; blocked during `PREPARATION`/`ACTIVE` war (M6)
- [ ] War declaration guard symmetric (either side shielded blocks declaration) (M7)
- [ ] Realm Contracts: escrow at creation, refund on unfulfilled expiry (M9)
- [ ] Projects: any permitted member can fund during `FUNDING` (M10)
- [ ] Disband: blocked while any war for the realm is in `PREPARATION`/`ACTIVE` (D7)
- [ ] Per-player in-flight-action lock for every money-moving command (M2 onward)

## M7 — full scope retained, sequencing only (do not front-load into earlier milestones)

- [ ] `PvpGateListener` rewrite implementing the full **7-layer** precedence chain: WorldGuard → Realm territory context → Same-Realm friendly fire → Realm-vs-Realm diplomacy → PeacefulToggle → New Member War Lock → War-state override
- [ ] `NexusCaptureService` implementing the exact formal rate model (§3 of the spec): nobody-present hold, undefended-zone bounded forward, defenders-only bounded reversal, ratio-based advance/reverse/contest, 100%→ATTACKER_WIN, timeout→DEFENDER_WIN
- [ ] `WarForceService` + `WarParticipantTracker` — temporal engagement-window definition for War Score/rewards, distinct from `NexusCaptureService`'s spatial zone-presence definition (both exist, don't conflate them)
- [ ] New Member War Lock wired as the gate on layer 6/7 specifically (not a generic PvP deny) — a locked player is transparent to capture-zone accounting, not just unscored
- [ ] Confirm M4 (Nexus) ships with placement/registration only — no capture logic — and the minimal `diplomacy_states` slice needed by M7 doesn't accidentally pull forward any of M11's player-facing Diplomacy commands

## Config files to stand up

- [ ] `pricing.yml` — active-population curve (explicitly moderate/saturating, low marginal cost per member — see design-goal note in spec), realm creation fee
- [ ] `war.yml` — `nexusCaptureRadius`, capture base rate + diminishing curve constants, Force Imbalance constants, New Member War Lock duration/threshold, TRUCE duration, write-behind flush interval
- [ ] `config.yml` — `asyncExecutor.poolSize`, `newRealmFriendlyFireDefault` (default `false`)
- [ ] `progression.yml` — milestone definitions (informational-only, confirmed — no gating logic to build)
- [ ] `visualization.yml` (or `config.yml` section) — particle colors/types, preview timeout, map radius
- [ ] `economy.yml` — Contribution hybrid weights

## Testing additions (this revision)

- [ ] Main-thread non-blocking assertion/harness — no DAO call ever originates from the main thread
- [ ] Nexus Capture formula: every branch (hold / undefended / defenders-only / advance / reverse / contest), clamping, 100%→win, timeout→win
- [ ] Friendly-fire config: denied by default in own territory, allowed when enabled, unaffected by unrelated wars
- [ ] ALLIANCE PvP denial (same shape as PEACE/TRUCE tests)
- [ ] Concurrent claim/withdrawal/transfer tests re-run specifically against the async dispatch path
- [ ] Write-behind restart safety: bounded loss on crash, zero loss on graceful shutdown

## Carried-forward testing (unchanged, see TECHNICAL_SPEC.md § Testing Audit for full list)

- [ ] Crash recovery, duplicate requests, money conservation, war state corruption, transaction rollback, database failure, progression-unlock idempotency, territory-visualization cleanup on all exit paths
