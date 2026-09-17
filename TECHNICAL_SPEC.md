# FlameRealms — Technical Specification

Status: draft v0.4 — targeted corrections applied, still pre-implementation
Source requirements: [PROJECT.md](PROJECT.md)
History: v0.1 proposed an architecture and silently resolved open questions. v0.2 retracted that and formalized every open question as a numbered Specification Issue. v0.3 applied the final product decisions D1–D10 plus several new gameplay systems. **v0.4 (this revision) is a targeted-correction pass over v0.3** — it does not redesign anything that wasn't flagged; it fixes a genuine main-thread-blocking flaw in the persistence model, formalizes the Nexus Capture mechanic precisely, resolves the last three open product questions (Progression, WorldGuard, friendly fire/alliance PvP), and fixes stale AuthMe wording in the security documentation. Every other v0.3 decision and system stands unchanged.

This document is now fully self-contained (v0.3 left several sections as "see v0.2," which no longer exists once superseded — that gap is closed here; restating that content is a documentation-completeness fix, not a new decision).

---

## Table of contents

1. [Final Product Decisions Applied](#final-product-decisions-applied)
2. [PvP & Diplomacy Precedence Model](#pvp--diplomacy-precedence-model) — rewritten (7 layers, friendly fire, alliance)
3. [War System](#war-system) — Nexus Capture now formally modeled
4. [Async Execution Model](#async-execution-model) — new, required correction
5. [Active Population & Upkeep](#active-population--upkeep) — incentive-structure review
6. [Progression System](#progression-system) — informational-only, resolved
7. [Claiming UX & Territory Visualization](#claiming-ux--territory-visualization) — unchanged from v0.3
8. [Specification Issues](#specification-issues) — updated register
9. [Database Audit](#database-audit) — full current schema
10. [Concurrency and Transaction Audit](#concurrency-and-transaction-audit) — rewritten for async
11. [Existing Plugin Integrations](#existing-plugin-integrations) — WorldGuard required, AuthMe corrected
12. [Dependency Architecture](#dependency-architecture)
13. [Economy Architecture](#economy-architecture)
14. [API Architecture](#api-architecture)
15. [Configuration Audit](#configuration-audit)
16. [Testing Audit](#testing-audit)
17. [Architecture Simplification Check](#architecture-simplification-check)
18. [Architecture (current)](#architecture-current) — package layout, services, event flows, milestones
19. [Consistency Audit Results](#consistency-audit-results)
20. [Remaining Open Items](#remaining-open-items)

---

## Final Product Decisions Applied

D1–D10 (from v0.3) are unchanged in substance; D1's detail is now more precise (§2). This revision resolves the three items that were still open at the end of v0.3:

| Item | Resolution |
|---|---|
| SPEC-027 (WorldGuard) | **Resolved.** WorldGuard is a **required** backend dependency for spawn/admin-region protection. No fallback is implemented. §2, §11, §12. |
| SPEC-028 (Progression gating) | **Resolved.** Informational-only in MVP, no feature/command locking. §6. |
| AuthMe wording | **Resolved.** Corrected — authentication is enforced entirely at the Velocity proxy; FlameRealms implements none of it and assumes every connection it sees has already passed that boundary. §11, and SECURITY.md. |
| Async DB I/O | **Corrected.** Blocking JDBC never runs on the Paper main thread. All existing transactional/atomicity/crash-safety guarantees are preserved exactly, now executed on an async worker. §4. |
| PvP: same-Realm friendly fire | **New, explicit.** Off by default, configurable per Realm; separate concept from Realm-vs-Realm diplomacy. §2. |
| PvP: ALLIANCE | **New, explicit.** Denies PvP between the two Realms, same as PEACE/TRUCE. §2. |

Everything else from v0.3's summary table (D1–D10, Active Population, Force Imbalance, New Member War Lock, Progression's existence, Territory Visualization) is unchanged in kind — only the items above were corrected, per the instruction to make targeted fixes rather than a redesign.

---

## PvP & Diplomacy Precedence Model

*(Supersedes v0.3's version — same intent, now precise about same-Realm friendly fire and ALLIANCE, and with New Member War Lock promoted to its own explicit layer rather than a footnote.)*

### The rule, stated plainly

PvP defaults to **on**. Claims establish land *ownership*; Realm-vs-Realm diplomacy and same-Realm friendly-fire settings establish *permission* to fight — these are two separate, independently-configurable concepts, not one rule. WorldGuard is the one absolute, non-negotiable exception, and it is now a required dependency, not an assumption.

### Canonical precedence chain

| Layer | Name | Can ALLOW? | Can DENY? | Behavior |
|---|---|---|---|---|
| 1 | **WorldGuard** | No | **Yes, absolute** | Required backend dependency (§11). Spawn/admin-protected regions deny PvP outright; FlameRealms's own listener checks `event.isCancelled()` first and never overrides a WorldGuard denial, under any circumstance — including an active war. FlameRealms implements no fallback and no duplicate spawn-protection logic. |
| 2 | **Realm territory / ownership context** | — (context only) | — (context only) | Determines whether the interaction is in unclaimed wilderness (layers 3–4 skipped, falls to layer 5) or inside some Realm's claimed territory (layers 3–4 evaluated). Not itself an allow/deny. |
| 3 | **Same-Realm friendly fire configuration** | Falls through if enabled | **Yes, if disabled (default)** | Applies only when both combatants belong to the **same** Realm and the interaction occurs within **that Realm's own** claimed territory — matching the plain-language rule exactly. Per-Realm configurable flag (`realms.friendly_fire_enabled`, default `FALSE`), settable by a Leader-permission command. If disabled, same-Realm members cannot damage each other there; if enabled, this layer has no opinion (falls through). **Same-Realm combat happening in wilderness or inside a third Realm's territory is outside this rule's stated scope** and is governed by whatever the other layers say (in practice: allowed, since nothing else denies it) — this is a technical completion of a partially-scoped rule, not an additional invented restriction. |
| 4 | **Realm-vs-Realm diplomacy** | Falls through if NEUTRAL/no relation | **Yes** — `PEACE`, `TRUCE`, or `ALLIANCE` between the two (different) Realms | Applies only when the two combatants belong to **different** Realms and the interaction is inside claimed Realm territory. `PEACE`/`TRUCE`/`ALLIANCE` → deny. `NEUTRAL`/no relation → falls through (allowed). `WAR` → does not allow here directly; it identifies the pairing as a candidate for layer 7's override. |
| 5 | **PeacefulToggle** | No | **Yes** | Personal opt-out. Reached only if layers 1–4 didn't already decide. Overridable only by layer 7. |
| 6 | **New Member War Lock** | No | — (gates layer 7, not a standalone deny) | Does not itself deny combat. For a given war pairing, a combatant whose Realm-membership tenure (and optional Contribution minimum) hasn't cleared the configured threshold is excluded from layer 7's override — they gain no war-based rescue from a layer-5 (or any other) denial, and War-state cannot force-allow combat for them. |
| 7 | **War-state override** | **Yes, overrides layer 5** | No | If layer 4 identified an active `WAR` between the two Realms, **and neither combatant is locked by layer 6 for that pairing**, combat is force-allowed. Still strictly subordinate to layer 1, and cannot apply to a same-Realm pairing at all (layers 4/6/7 only ever concern two *different* Realms — mutually exclusive with layer 3's same-Realm scope by construction, so a Realm's war with a third party can never bleed into its own friendly-fire setting). |

### Explicit resolutions requested

- **WAR never overrides WorldGuard** — layer 7 is strictly below layer 1; no code path evaluates layer 7 before checking layer 1's result.
- **WAR never overrides same-Realm friendly fire** — not because one explicitly beats the other, but because layers 3 and 7 can never both apply to the same pairing (same-Realm vs. different-Realm are mutually exclusive contexts). Stated explicitly here so an implementer doesn't accidentally build a single combined check that could conflate the two.
- **A locked New Member never gets the WAR override merely from membership** — layer 6 gates layer 7 directly; there is no path from "is a Realm member" to "gets the war-allow" that skips the tenure/contribution check.
- **PeacefulToggle vs. WAR is unchanged from D1** — layer 7 overrides layer 5, and only layer 7.
- **ALLIANCE denies PvP exactly like PEACE/TRUCE** — all three are treated identically at layer 4; `NEUTRAL` is the only base state that allows, and `WAR` is the only one that can trigger layer 7.

No additional PvP behavior beyond what's stated above is introduced by this revision, per instruction to avoid inventing further gameplay rules.

---

## War System

### State machine (unchanged in shape from v0.3)

```
DECLARED → PREPARATION (10 min, config) → ACTIVE → ENDED → COOLDOWN (= diplomacy TRUCE) → NEUTRAL
```

Inside `ACTIVE`, the war ends via exactly one of: Nexus capture reaching 100% (`ATTACKER_WIN`), the configured time limit being reached without 100% capture (`DEFENDER_WIN`), or a surrender by either side (retained from v0.2/v0.3, unchanged). War Score (`wars.score_attacker/score_defender`, `war_events`) remains secondary/advisory, not a win condition. **The losing Realm is never destroyed or auto-disbanded** — a hard constraint on every future outcome-enhancement.

**Milestone discipline, stated explicitly (per instruction not to front-load M7 work):** none of this section's mechanics are implemented before M7. M4 (Nexus) implements only the Nexus as a placed, registered domain object — it contains no capture logic whatsoever. The `diplomacy_states` table's minimal slice (base state including `TRUCE`) is the only piece of this system that needs to exist earlier than M7, because `WarService` is what writes to it — see milestone table, §18.

### Nexus Capture — formal model

*(New in this revision — precise, exploit-resistant definition, replacing v0.3's informal description.)*

**Capture zone.** A fixed-radius region centered on the Nexus block (radius `nexusCaptureRadius`, config, `war.yml`). Presence in this region is what "participating in capture" means, mechanically.

**General eligibility to participate in capture (both sides).** A player is eligible only if **all** of: (a) online, (b) a member of one of the two belligerent Realms, (c) not New-Member-War-Locked for this specific war pairing (§3, New Member War Lock — tenure/contribution threshold cleared), and (d) the war is currently `ACTIVE` (capture never progresses during `DECLARED`/`PREPARATION`).

**Attacker eligibility.** General eligibility, membership in the attacking Realm, currently physically present within the capture zone.

**Defender eligibility.** General eligibility, membership in the defending Realm, currently physically present within the capture zone. (Allied-Realm defenders are a Diplomacy/v1.3+ concept, out of MVP scope — not introduced here.)

**New Member War Lock's effect on capture, precisely.** A locked player is not merely unrewarded — they are **transparent to the capture calculation entirely**, exactly as if not present. This follows directly from §2's layer 6/7: since a locked player never receives the WAR-state override, they cannot even be a valid combatant in this war's zone in the first place.

**Effective attacker/defender count, for this specific mechanic.** `effectiveAttackers`/`effectiveDefenders` = the count of eligible members of each side **currently present in the capture zone at this evaluation tick.** This is a **spatial**, real-time definition, distinct from the broader **temporal** "engagement window" definition of "effective combatant" used elsewhere for War Score and reward distribution (§3, Force Imbalance) — stating this distinction explicitly closes an ambiguity that existed in v0.3 between two similar-sounding but different eligibility windows; it is a technical clarification, not a new rule.

**Capture rate per evaluation tick:**

```
if effectiveAttackers == 0 and effectiveDefenders == 0:
    rate = 0                                                  // nobody present → hold steady
elif effectiveDefenders == 0:                                 // zone undefended
    rate = +baseRate × diminishing(effectiveAttackers)        // bounded forward progress, not instant
elif effectiveAttackers == 0:                                 // only defenders present
    rate = −baseRate × diminishing(effectiveDefenders)        // bounded reversal toward 0%
else:
    ratio = effectiveAttackers / effectiveDefenders
    if ratio > 1: rate = +baseRate × diminishingAdvantage(ratio)      // attacker advantage → progresses toward 100%
    elif ratio < 1: rate = −baseRate × diminishingAdvantage(1/ratio)  // defender advantage → reverses toward 0%
    else: rate = 0                                                    // exactly contested → no net progress
```

`nexus_capture_percent` is clamped to `[0, 100]` after each application. `baseRate`, the diminishing-curve shape/exponent, and `nexusCaptureRadius` are all configurable (`war.yml`). The undefended-zone and defenders-only branches are the natural boundary cases of "attacker advantage"/"defender advantage" (a ratio against zero on one side), not additional invented rules — they exist because a plain division by zero isn't otherwise defined.

**At 100%:** the war ends immediately; outcome = `ATTACKER_WIN`; War Stake settles (zero-sum, §12); transition to `ENDED → COOLDOWN` (`TRUCE`) proceeds exactly as in the base state machine.

**At the configured time limit without 100%:** the war ends; outcome = `DEFENDER_WIN`; same settlement/transition.

**This formula is War Force Imbalance's concrete instantiation for the Nexus-capture objective** (§3, Force Imbalance below) — the same diminishing-curve philosophy also governs War Score gain and reward distribution elsewhere in the war, using the broader engagement-window definition of "effective combatant," not this section's zone-presence definition.

### War Force Imbalance (general concept, unchanged from v0.3)

A 5-vs-1 war must not behave like 5-vs-5 — solved via diminishing-returns modifiers on rate/score/rewards, never a flat per-player damage debuff. `war_participants` (§9) tracks `first_seen_at`/`last_active_at` (refreshed on any war-relevant action anywhere in the war, not just in the capture zone), `kills`, `deaths`, feeding the **temporal** engagement-window definition of "effective combatant" used for War Score gain and reward scaling — distinct from, but built on the same underlying eligibility rules as, Nexus Capture's spatial definition above. A declare-time minimum-combatants guard (`minEligibleToDeclare`, config) remains a separate concept from the runtime ratio floor. Alt-padding resistance is unchanged: an idle account near the objective doesn't count without recent, real engagement.

### New Member War Lock (unchanged from v0.3, restated for completeness)

A Realm member is war-eligible once `now − realm_members.joined_at ≥ warLockDuration` (config, default 48h) **and**, optionally, once a configurable minimum Contribution total is met. Everything else a new member can do (build, mine, farm, trade, contribute materials/money, participate in projects) is available immediately — only war-combatant eligibility is gated, and it's gated precisely as described in §2, layer 6.

---

## Async Execution Model

*(Required correction — this section is new. It changes **how** every previously-specified transactional guarantee is executed, not **what** is guaranteed.)*

### The problem being fixed

v0.2/v0.3 specified synchronous, transactional DB writes for every financial/claim operation, correctly reasoned about atomicity and crash-safety, but did not explicitly rule out that synchronous execution happening **on the Paper main thread** — which would block the server tick loop on network I/O to MariaDB, a real production defect (tick lag/stalls under any DB latency, worst near instantaneous during peak Nexus-siege moments when many financial and capture events fire close together).

### The corrected pattern

```
Paper main thread
  → validate input, read cached/authoritative-so-far state (fast, non-blocking)
  → dispatch to the async DB executor (a bounded thread pool, drawing connections from HikariCP)
       Async worker thread
       → BEGIN transaction
       → row locks (SELECT ... FOR UPDATE, deterministic ascending-id order for cross-entity ops)
       → balance/state mutation + ledger insert — SAME transaction, unchanged guarantee
       → COMMIT (or ROLLBACK on guard failure)
       → produce a result (success/failure + new authoritative values)
  → CompletableFuture completes; Bukkit.getScheduler().runTask(...) hops back to the main thread
  → main thread applies the result: updates in-memory cache to match the now-authoritative DB state,
    sends player feedback, fires Bukkit events, applies any world-state change (e.g. actually grants the claim)
```

**Nothing about the transactional guarantees changes:**
- Balance mutation + ledger entry still commit in the **same** DB transaction — now executed on the async worker instead of the main thread, never split across two round-trips.
- Claim purchase (withdraw + claim-row insert + ledger insert) remains one atomic transaction.
- Cross-entity transfers still lock both sides in deterministic ascending-id order, inside the async transaction.
- Crash/restart safety is unchanged: a crash before `COMMIT` means the transaction never happened, exactly as it would for a synchronous write — moving the write to a different thread does not change what "durable" means, only which thread blocks while waiting for it.
- MariaDB remains the durable source of truth; the in-memory cache is always corrected to match what the DB transaction actually committed, never the other way around.

### What the in-process locks are actually for (and are not for)

v0.2/v0.3 described per-realm/per-player "locks" alongside the DB transaction model. To be unambiguous: **these locks are a dispatch-ordering and UX convenience, never a substitute for the DB transaction.** Concretely:

- A per-realm (and per-player, for wallets) **single-flight dispatch queue** on the main thread ensures that a second command for the same realm isn't fired into the async executor while an earlier one for that realm is still in flight — this avoids wasted round-trips and rollback churn, and lets the command layer reply "please wait, your last action is still processing" immediately rather than after a failed race.
- **Correctness itself comes entirely from the DB layer** — row locks, conditional (`WHERE balance >= amount`) updates, and unique constraints. If the in-process queue were removed entirely, the system would still be correct (just less efficient, with more transactions racing and rolling back against each other); if the DB-level guards were removed, no amount of in-process locking would make the system correct, because the async executor is a real thread pool and nothing in-process can serialize what a future clustered/second-instance deployment might do to the same rows (not a current requirement, but the reasoning holds even for a single instance: the DB transaction is the actual invariant enforcer, the in-process lock is an optimization on top of it).

### Financial/claim-owning vs. write-behind operations, precisely distinguished

| Category | Examples | Persistence pattern |
|---|---|---|
| **Synchronous-transactional** (must never be "eventually" consistent) | Claim purchase, treasury withdrawal, player↔realm and realm↔realm transfers, War Stake settlement, Shield purchase, Disband, Contract fulfillment, Project funding | Dispatched off-thread per the pattern above, but the *result* is awaited before the main thread confirms success to the player — i.e. "async" here means "not blocking the main thread," not "eventually consistent." The player never sees a false-positive success. |
| **Write-behind / eventually consistent** (non-financial) | `wars.nexus_capture_percent`, `contribution_events`, `realm_member_activity`, `realm_progression` unlocks | Main thread updates its own in-memory value immediately (for fast reads/UI/boss bars) and the async executor persists it on a throttled interval (e.g. every few seconds for capture percent), not on every single tick. **Restart-safety requirement, unchanged from v0.3:** a hard crash loses at most a small, bounded amount of progress (bounded by the flush interval); a **graceful shutdown (`onDisable`) flushes all pending write-behind state synchronously and completely** before the process exits, so only a true crash — never a normal restart — has any loss window at all. This is the precise reconciliation of "write-behind" with "restart-safe." |

### Package/class implication

A single `AsyncDatabaseExecutor` (bounded thread pool, separate from HikariCP's own internal pool but drawing connections from it) is the one place all DB-transaction work runs; `DatabaseManager` owns its lifecycle (including a bounded drain-with-timeout on `onDisable`, per the existing shutdown-safety requirement). No service is allowed to open a JDBC connection or execute a query directly on the calling thread if that thread might be the main thread — this is a structural rule for every DAO method, not a per-flow judgment call.

---

## Active Population & Upkeep

*(Core formula and dual-signal model unchanged from v0.3; this revision reviews and tightens the incentive structure per the explicit game-design concern raised.)*

```
Daily Upkeep = territoryCost(claims) × territoryMultiplier(claims) × activePopulationMultiplier(activePopulation)
```

**Why this does not incentivize kicking inactive friends — stated explicitly, because the concern is legitimate and worth being clear about rather than just asserting:** `activePopulation` counts only members meeting the presence-∪-contribution bar (§9's `realm_member_activity`, unchanged from v0.3) within the rolling window. A member who is genuinely inactive **already contributes zero to the multiplier** — keeping them in the Realm costs nothing upkeep-wise; there is no cost delta between "keep an inactive friend" and "kick an inactive friend." The only members who can possibly move the multiplier are the ones meeting the activity bar, and removing *those* members would be removing genuinely engaged players, which is not the behavior anyone would want to encourage either. The design therefore already has no lever that rewards pruning inactive members — this is a property of counting *active* population rather than *raw* population, not something bolted on separately.

**Curve-shape design goal, made explicit (this is the actual correction):** `activePopulationMultiplier` must be a genuinely **moderate**, saturating curve — the marginal cost of each additional active member should be small and should not feel punishing for an ordinary small group of real friends playing together. This is a tuning/config-shape goal for whoever sets the constants in `pricing.yml`, not a new subsystem: no "free tier," no per-member scoring, no activity-ranking system (explicitly rejected, per instruction). A single saturating curve over the active-population count, with constants chosen so a handful of genuinely active members produces only a small upkeep bump, satisfies this without adding any new mechanic.

The presence-∪-contribution signal, the rolling window, and the "feeds §35 economy analytics as an ordinary `SINK` transaction" behavior are all unchanged from v0.3.

---

## Progression System

**Resolved: `ProgressionService` and `/realm progress` are informational-only in MVP.** No feature or command is locked behind a progression milestone unless a future revision explicitly says so. This was v0.3's stated default (SPEC-028); it is now the confirmed, final answer, not an open item.

What it shows (config-driven, `progression.yml`, unchanged mechanism from v0.3 — `ProgressionService`, event-driven evaluation with a periodic safety-net sweep, persisted in `realm_progression` so an unlock message fires exactly once):

- The Realm's current level/state and which milestones it has already reached.
- Which systems are currently available/unlocked (which, in MVP, is simply "all of them" — informational-only means nothing is actually locked, so this is a descriptive list, not a gate status).
- What systems exist later in the project's development (a forward-looking, explanatory list — this is explicitly onboarding/discovery content, matching PROJECT.md's stated goal of not requiring a wiki to understand the plugin).
- What requirements are conceptually associated with reaching further milestones, described informationally.

`/realm progress` and the Nexus GUI render from the same underlying view (`ProgressionService.getProgress(realmId)`), unchanged from v0.3. No new mechanics, gates, or requirements are introduced by this system beyond describing state that already exists elsewhere.

---

## Claiming UX & Territory Visualization

Unchanged from v0.3 — `ClaimVisualizationService` (particle chunk-boundary preview, vanilla Paper particle API, no packet-library dependency), `TerritoryMapService` (`/realm map`), and the chat-driven `/realm claim` preview→confirm flow. Not restated in full here since nothing in this revision touches it; see v0.3's description, now folded into this document's Architecture section (§18) for a single source of truth.

---

## Specification Issues

Only status changes from v0.3 are shown; everything else carries forward unchanged (see v0.3 for full original problem statements where not restated).

| ID | Status |
|---|---|
| SPEC-001, SPEC-026 (PvP model, PeacefulToggle) | **Resolved, refined.** Now a 7-layer chain with explicit friendly-fire and ALLIANCE handling, §2. |
| SPEC-027 (WorldGuard) | **Resolved.** Required dependency, confirmed, §11–§12. |
| SPEC-028 (Progression gating) | **Resolved.** Informational-only, confirmed, §6. |
| **SPEC-029 (new)** | **Resolved by this revision's own correction.** v0.2/v0.3's synchronous DB-write flows never explicitly stated they must not run on the Paper main thread — a real correctness-adjacent production defect (tick-thread blocking), fixed in §4. |
| All other v0.2/v0.3 issues (SPEC-002–025, excluding those listed above) | Unchanged — still open where previously open (architecture-decidable, non-blocking), still resolved where previously resolved. |

---

## Database Audit

Full current schema, self-contained (v0.3 left several tables as "see v0.2," which is no longer valid once superseded — restated in full here).

```sql
-- realms ------------------------------------------------------------
CREATE TABLE realms (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name              VARCHAR(32)  NOT NULL UNIQUE,
  display_name      VARCHAR(48)  NOT NULL,
  leader_uuid       BINARY(16)   NOT NULL,
  level             INT UNSIGNED NOT NULL DEFAULT 1,
  balance_cents     BIGINT       NOT NULL DEFAULT 0,
  upkeep_debt_cents BIGINT       NOT NULL DEFAULT 0,
  specialization    VARCHAR(24)  NULL,
  power_cached      INT UNSIGNED NOT NULL DEFAULT 0,
  friendly_fire_enabled BOOLEAN  NOT NULL DEFAULT FALSE,   -- NEW this revision, §2 layer 3
  nexus_world       VARCHAR(64)  NULL,
  nexus_x           INT          NULL,
  nexus_y           INT          NULL,
  nexus_z           INT          NULL,
  created_at        DATETIME     NOT NULL,
  last_upkeep_at    DATETIME     NULL,
  disbanded_at      DATETIME     NULL,
  INDEX idx_leader (leader_uuid)
);

CREATE TABLE realm_ranks (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id          BIGINT UNSIGNED NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
  name              VARCHAR(24)  NOT NULL,
  priority          INT          NOT NULL,
  permissions       BIGINT UNSIGNED NOT NULL,
  is_default        BOOLEAN      NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_realm_rank_name (realm_id, name)
);

CREATE TABLE realm_members (
  realm_id          BIGINT UNSIGNED NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
  player_uuid       BINARY(16)   NOT NULL,
  rank_id           BIGINT UNSIGNED NOT NULL REFERENCES realm_ranks(id),
  joined_at         DATETIME     NOT NULL,
  PRIMARY KEY (realm_id, player_uuid),
  UNIQUE KEY uq_player_one_realm (player_uuid)
);

CREATE TABLE realm_claims (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id          BIGINT UNSIGNED NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
  world             VARCHAR(64)  NOT NULL,
  chunk_x           INT          NOT NULL,
  chunk_z           INT          NOT NULL,
  claimed_at        DATETIME     NOT NULL,
  price_paid_cents  BIGINT       NOT NULL,
  UNIQUE KEY uq_chunk (world, chunk_x, chunk_z),
  INDEX idx_realm (realm_id)
);

CREATE TABLE player_wallets (
  player_uuid       BINARY(16)   NOT NULL PRIMARY KEY,
  balance_cents     BIGINT       NOT NULL DEFAULT 0,
  updated_at        DATETIME     NOT NULL
);

CREATE TABLE transactions (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ts                DATETIME     NOT NULL,
  category          ENUM('FAUCET','SINK','TRANSFER') NOT NULL,
  reason            VARCHAR(32)  NOT NULL,
  source_type       ENUM('PLAYER','REALM','SERVER') NOT NULL,
  source_id         VARCHAR(36)  NULL,
  target_type       ENUM('PLAYER','REALM','SERVER') NULL,
  target_id         VARCHAR(36)  NULL,
  amount_cents      BIGINT       NOT NULL,
  metadata          JSON         NULL,
  INDEX idx_ts (ts),
  INDEX idx_reason (reason)
);

CREATE TABLE shields (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id          BIGINT UNSIGNED NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
  started_at        DATETIME     NOT NULL,
  expires_at        DATETIME     NOT NULL,
  cost_cents        BIGINT       NOT NULL,
  sequence_in_week  INT          NOT NULL,
  INDEX idx_realm_active (realm_id, expires_at)
);

CREATE TABLE wars (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  attacker_realm_id BIGINT UNSIGNED NOT NULL REFERENCES realms(id),
  defender_realm_id BIGINT UNSIGNED NOT NULL REFERENCES realms(id),
  state             ENUM('DECLARED','PREPARATION','ACTIVE','ENDED','COOLDOWN') NOT NULL,
  declared_at       DATETIME     NOT NULL,
  prep_ends_at      DATETIME     NOT NULL,
  active_ends_at    DATETIME     NULL,
  ended_at          DATETIME     NULL,
  cooldown_ends_at  DATETIME     NULL,
  stake_cents       BIGINT       NOT NULL,
  declaration_fee_cents BIGINT   NOT NULL,
  score_attacker    INT          NOT NULL DEFAULT 0,
  score_defender    INT          NOT NULL DEFAULT 0,
  nexus_capture_percent DECIMAL(5,2) NOT NULL DEFAULT 0,   -- persisted, write-behind, §4
  outcome           ENUM('ATTACKER_WIN','DEFENDER_WIN','DRAW','CANCELLED') NULL,
  INDEX idx_realm_active (attacker_realm_id, defender_realm_id, state)
);

CREATE TABLE war_events (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  war_id            BIGINT UNSIGNED NOT NULL REFERENCES wars(id) ON DELETE CASCADE,
  ts                DATETIME     NOT NULL,
  type              VARCHAR(32)  NOT NULL,
  actor_uuid        BINARY(16)   NULL,
  points            INT          NOT NULL,
  data              JSON         NULL,
  INDEX idx_war (war_id)
);

CREATE TABLE war_participants (
  war_id            BIGINT UNSIGNED NOT NULL REFERENCES wars(id) ON DELETE CASCADE,
  player_uuid       BINARY(16)   NOT NULL,
  realm_id          BIGINT UNSIGNED NOT NULL REFERENCES realms(id),
  first_seen_at     DATETIME     NOT NULL,
  last_active_at    DATETIME     NOT NULL,
  kills             INT UNSIGNED NOT NULL DEFAULT 0,
  deaths            INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (war_id, player_uuid),
  INDEX idx_war_realm_active (war_id, realm_id, last_active_at)
);

CREATE TABLE diplomacy_states (
  realm_a_id        BIGINT UNSIGNED NOT NULL REFERENCES realms(id),
  realm_b_id        BIGINT UNSIGNED NOT NULL REFERENCES realms(id),  -- realm_a_id < realm_b_id
  state             ENUM('PEACE','NEUTRAL','ALLIANCE','TRUCE','WAR') NOT NULL DEFAULT 'NEUTRAL',
  since             DATETIME     NOT NULL,
  PRIMARY KEY (realm_a_id, realm_b_id)
);

CREATE TABLE diplomacy_agreements (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_a_id        BIGINT UNSIGNED NOT NULL,
  realm_b_id        BIGINT UNSIGNED NOT NULL,
  type              ENUM('TRADE_AGREEMENT','NON_AGGRESSION_PACT','MUTUAL_DEFENSE_PACT','TRIBUTE') NOT NULL,
  started_at        DATETIME     NOT NULL,
  expires_at        DATETIME     NULL,
  terms             JSON         NULL,
  UNIQUE KEY uq_pair_type (realm_a_id, realm_b_id, type)
);

CREATE TABLE diplomacy_proposals (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  from_realm_id     BIGINT UNSIGNED NOT NULL REFERENCES realms(id),
  to_realm_id       BIGINT UNSIGNED NOT NULL REFERENCES realms(id),
  proposed_state_or_agreement VARCHAR(24) NOT NULL,
  terms             JSON         NULL,
  proposed_at       DATETIME     NOT NULL,
  expires_at        DATETIME     NOT NULL,
  status            ENUM('PENDING','ACCEPTED','REJECTED','EXPIRED') NOT NULL DEFAULT 'PENDING',
  INDEX idx_to_pending (to_realm_id, status)
);

CREATE TABLE contracts (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  scope             ENUM('SERVER','REALM') NOT NULL,
  issuer_realm_id   BIGINT UNSIGNED NULL REFERENCES realms(id),
  requirement       JSON         NOT NULL,
  reward_cents      BIGINT       NOT NULL,
  expires_at        DATETIME     NOT NULL,
  status            ENUM('OPEN','FULFILLED','EXPIRED') NOT NULL DEFAULT 'OPEN',
  fulfilled_by_uuid BINARY(16)   NULL,
  fulfilled_at      DATETIME     NULL
);

CREATE TABLE projects (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id          BIGINT UNSIGNED NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
  name              VARCHAR(48)  NOT NULL,
  cost_cents        BIGINT       NOT NULL,
  material_requirements JSON     NOT NULL,
  status            ENUM('FUNDING','IN_PROGRESS','COMPLETED','CANCELLED') NOT NULL,
  started_at        DATETIME     NOT NULL,
  deadline_at       DATETIME     NULL,
  completed_at      DATETIME     NULL
);

CREATE TABLE project_contributions (
  project_id        BIGINT UNSIGNED NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  player_uuid       BINARY(16)   NOT NULL,
  resource_type     VARCHAR(32)  NOT NULL,
  amount            INT          NOT NULL,
  contributed_at    DATETIME     NOT NULL
);

CREATE TABLE contribution_events (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id          BIGINT UNSIGNED NOT NULL,
  player_uuid       BINARY(16)   NOT NULL,
  category          ENUM('BUILDING','RESOURCES','MONEY','TRADING','DIPLOMACY','WARS','PROJECTS') NOT NULL,
  points            INT          NOT NULL,
  ts                DATETIME     NOT NULL,
  INDEX idx_realm_player (realm_id, player_uuid),
  INDEX idx_ts (ts)
);

CREATE TABLE contribution_totals (
  realm_id          BIGINT UNSIGNED NOT NULL,
  player_uuid       BINARY(16)   NOT NULL,
  category          VARCHAR(16)  NOT NULL,
  lifetime_points   BIGINT       NOT NULL DEFAULT 0,
  rolling_30d_points BIGINT      NOT NULL DEFAULT 0,
  updated_at        DATETIME     NOT NULL,
  PRIMARY KEY (realm_id, player_uuid, category)
);

CREATE TABLE realm_member_activity (
  realm_id          BIGINT UNSIGNED NOT NULL,
  player_uuid       BINARY(16)   NOT NULL,
  activity_date     DATE         NOT NULL,
  online_minutes    INT UNSIGNED NOT NULL DEFAULT 0,
  contributed       BOOLEAN      NOT NULL DEFAULT FALSE,
  PRIMARY KEY (realm_id, player_uuid, activity_date)
);

CREATE TABLE realm_progression (
  realm_id          BIGINT UNSIGNED NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
  milestone_id      VARCHAR(48)  NOT NULL,
  unlocked_at       DATETIME     NOT NULL,
  PRIMARY KEY (realm_id, milestone_id)
);

CREATE TABLE realm_power_history (
  realm_id          BIGINT UNSIGNED NOT NULL,
  ts                DATETIME     NOT NULL,
  power_value       INT UNSIGNED NOT NULL,
  PRIMARY KEY (realm_id, ts)
);
```

**Only change this revision:** `realms.friendly_fire_enabled` (new column, §2). Every other table is carried forward from v0.3 unchanged, now simply restated in one place instead of split across two superseded documents.

---

## Concurrency and Transaction Audit

Rewritten to describe the async-corrected model precisely; the guarantees themselves are unchanged from v0.2/v0.3.

### Dispatch pattern (applies to every row below)

Main thread validates and reads cached state → dispatches to the per-realm/per-player single-flight queue (ordering/UX only, §4) → async worker opens a transaction, applies the operation's specific locks/constraints/mutations described below, commits → result returns to the main thread for cache/UI/world-state application. The table below describes what happens **inside the async transaction** — unchanged in substance from v0.2/v0.3, just no longer executed on the main thread.

| Operation | DB transaction | Row lock | Unique/conditional constraint | Notes |
|---|---|---|---|---|
| Claim purchase | Yes — withdraw + claim-insert + ledger-insert, one transaction | Realm balance row | `UNIQUE(world,x,z)` on `realm_claims`; contiguity check against existing claims/Nexus | Second concurrent claim on the same chunk fails the unique constraint; the whole transaction rolls back, no orphaned debit. |
| Treasury withdrawal (shield, nexus upgrade, project funding, etc.) | Yes | Realm balance row | Conditional `UPDATE realms SET balance_cents = balance_cents - ? WHERE id=? AND balance_cents >= ?` | |
| Player → Realm transfer (deposit) | Yes | Player wallet row + realm balance row, fixed order | Conditional decrement on the wallet | |
| Realm → Realm transfer (war stake, trade settlement) | Yes | Both realm rows, locked in ascending realm-id order | Conditional decrement on the debited side | Deadlock structurally impossible by construction. |
| Realm → Player transfer (contract reward, project payout) | Yes | Realm row + player wallet row | Conditional decrement on the realm side | |
| War stake settlement | Yes | War row (`SELECT ... FOR UPDATE`) | Transition fires only if `state='ACTIVE'` at lock time | A concurrent duplicate trigger (surrender command racing the capture-100%/timeout tick) — only one acquires the lock while state is still `ACTIVE`. |
| Shield purchase | Yes | Realm balance row | Weekly-count read + price computation + insert inside the same transaction/lock | |
| Disband | Yes | Realm row | Guard: reject if any war for this realm is in `PREPARATION`/`ACTIVE` (D7, confirmed) | |
| Contract fulfillment | Yes | Contract row | Conditional `UPDATE contracts SET status='FULFILLED', fulfilled_by_uuid=? WHERE id=? AND status='OPEN'` | The conditional update itself is the idempotency mechanism — no lock needed beforehand. |
| Project funding contribution | Yes (per contribution) | Project row, briefly, only at the funding-threshold-crossing moment | Conditional check-and-transition to `IN_PROGRESS`/`COMPLETED` | |
| `war_participants` upsert | No (single-row upsert suffices) | No | `PRIMARY KEY(war_id, player_uuid)`, `ON DUPLICATE KEY UPDATE` | Non-financial, high-frequency, eventually consistent. |
| `wars.nexus_capture_percent` update | No | No | Plain `UPDATE`, write-behind (§4) | Financial guard (war-state transition lock, above) is a separate concern from this progress field. |
| `realm_member_activity` daily upsert | No | No | `PRIMARY KEY(realm_id, player_uuid, activity_date)` | Non-financial. |
| `realm_progression` unlock insert | No | No | `PRIMARY KEY(realm_id, milestone_id)`, conditional insert | Worst case of a race is a harmlessly-skipped duplicate, not a financial/ownership bug. |

### Final recommended consistency model

- Every financial or claim-owning write is transactional and durable at commit time, executed off the Paper main thread, never eventually-consistent, never confirmed to a player before it has actually committed.
- Cross-entity money movement always locks in a fixed, deterministic (ascending id) order.
- Idempotency comes from unique constraints ("this must never exist twice") and conditional/guarded updates ("this transition fires at most once"), backed by a thin main-thread dispatch queue that also gives quick "please wait" feedback for genuine duplicate command spam.
- Non-financial state (capture percent, contribution events, activity, progression) is write-behind, bounded-loss on crash, and fully flushed on graceful shutdown.
- Restart safety across the board comes from storing every timer as an absolute timestamp and treating in-memory caches as a reload of the database, never the reverse.

No new infrastructure is introduced by this correction — it's a bounded thread pool and `CompletableFuture`/`Bukkit.getScheduler()` plumbing, not a new architectural layer.

---

## Existing Plugin Integrations

Only changes from v0.3 are shown; the rest of the classification table (Vault, Essentials, PlaceholderAPI, ItemsAdder, CustomNamePlates, WorldEdit/FAWE, ProtocolLib/PacketEvents, Multiverse-Core, PeacefulToggle, PlugManX, Vulcan, VoidGen, WorldEditSUI, EssentialsChat, PlayerRider, ViaVersion, LibsDisguises, LiteBans, SkinsRestorer, UltimateAutoRestart, ItsMyConfig, LPC/NixMC clarifications) carries forward unchanged from v0.3.

| Plugin | Classification | Notes |
|---|---|---|
| **WorldGuard** | **REQUIRED** *(was: flagged/absent, SPEC-027)* | Confirmed backend dependency for spawn/admin-region PvP protection (§2, layer 1). Declared as a hard `depend:` in `plugin.yml`, not a soft-depend — FlameRealms should not start without it, since its entire outermost PvP guarantee assumes WorldGuard is present and functioning. No fallback spawn-protection logic is implemented in FlameRealms, by explicit instruction. |
| **AuthMe** (proxy-side) | NO INTEGRATION NEEDED *(wording corrected)* | Runs on the Velocity proxy, not the backend. Authentication/session enforcement happens entirely at the proxy boundary; FlameRealms implements none of it and assumes every player it sees on the backend has already passed that boundary. `PlayerLifecycleListener` has no AuthMe-awareness of any kind — this was already true in v0.3's spec text but is now also corrected in `SECURITY.md`, which had carried stale wording implying backend-side gating. |

---

## Dependency Architecture

Unchanged in shape from v0.3, with one addition:

**Core Dependencies** now includes **WorldGuard**, distinct in kind from the infrastructure items (Paper/Purpur API, MariaDB/MySQL+HikariCP+Flyway, Adventure/MiniMessage): WorldGuard is a required **third-party plugin**, not bundled infrastructure, but it is required in the same sense — FlameRealms's PvP model is not considered complete or safe without it, and no fallback is implemented. This is a deliberate, explicit exception to "FlameRealms must not require third-party plugins for core gameplay" — spawn safety is treated as a hard prerequisite the server operator provides, exactly as directed.

Everything else (Optional Integrations, Server-Only Integrations) is unchanged from v0.3.

---

## Economy Architecture

Unchanged from v0.3 in every respect — player wallet, realm treasury, faucets, sinks, all transfer types, ledger shape, cents-precision rule, and the D8/D9 resolutions. The only change touching this area is purely mechanical: these operations now execute per the Async Execution Model (§4) instead of synchronously on the main thread — the economic model itself, categories, and precision rules are untouched.

---

## API Architecture

Unchanged from v0.3: immutable domain views, read-oriented `FlameRealmsAPI` facade, custom Bukkit events as the extension point, no DAO/cache/JDBC/mutable-internal-entity leakage. `ProgressionService`'s read view remains a reasonable public addition (now explicitly informational, which if anything makes it safer to expose, since there's no gate state to misuse). Visualization services remain internal.

---

## Configuration Audit

Only additions from this revision are shown; the full v0.3 file list (`pricing.yml`, `war.yml`, `pvp.yml`, `progression.yml`, `visualization.yml`, `economy.yml`, `ranks.yml`, `messages.yml`/`lang/*.yml`) is unchanged and still applies.

| File | New keys this revision |
|---|---|
| `pricing.yml` | Active Population multiplier curve constants, explicitly documented with the "moderate/saturating, low marginal cost" design goal (§5) so future tuning doesn't accidentally reintroduce a harsh curve. |
| `war.yml` | `nexusCaptureRadius`, `baseRate` and diminishing-curve constants for capture (§3), `nexusCapturePersistFlushIntervalSeconds` (write-behind flush cadence, §4). |
| `config.yml` | `asyncExecutor.poolSize` (bounded thread pool size for the async DB executor, §4). |
| — | `realms.friendly_fire_enabled` is per-Realm runtime state (a database column, set via command), not a config-file default — noted here so it isn't mistaken for a global config toggle; a global **default** for newly-created Realms may live in `config.yml` (`newRealmFriendlyFireDefault`, defaulting to `false` per D1's stated default). |

---

## Testing Audit

Only additions from this revision are shown; the full v0.2/v0.3 invariant list and layered strategy are unchanged and still apply.

| Category | What it verifies |
|---|---|
| Main-thread non-blocking | A test harness (or a runtime assertion in dev builds) confirms no DAO method is ever invoked from the main server thread — the structural rule in §4 is actually enforced, not just documented. |
| Nexus Capture formula | Every branch of §3's rate formula: nobody present (hold), undefended zone (bounded forward), defenders-only (bounded reversal), attacker advantage (progresses), defender advantage (reverses), exact contest (holds); percent clamps at `[0,100]`; 100% ends the war with `ATTACKER_WIN`; timeout ends it with `DEFENDER_WIN`. |
| Friendly-fire configuration | Same-Realm combat denied by default within the Realm's own territory; allowed when the flag is enabled; unaffected by that Realm's WAR status with any third party; same-Realm combat outside the Realm's own territory is not denied by this rule specifically (falls through to the general default). |
| ALLIANCE PvP | Two allied Realms cannot PvP inside Realm territory, identical test shape to the existing PEACE/TRUCE cases. |
| Async correctness | Concurrent claim/withdrawal/transfer tests (carried from v0.2/v0.3) are re-run against the async dispatch path specifically, confirming the same invariants hold when the transaction runs on a worker thread instead of inline. |
| Write-behind restart safety | Nexus capture percent survives a hard-kill-and-restart within its bounded loss window; a graceful `/stop` loses nothing at all (full flush). |

---

## Architecture Simplification Check

Still true after this revision: nothing here justifies Redis, Kafka, microservices, or an ORM.

- The async correction is a bounded `ExecutorService` plus `CompletableFuture`/`Bukkit.getScheduler()` — standard Java concurrency primitives, not a new architectural layer or message queue.
- The Nexus Capture formula is a small pure function over two integers (effective attacker/defender counts) — no new infrastructure.
- WorldGuard becoming required is a plugin-loader dependency declaration, not new infrastructure FlameRealms itself must build or run.

---

## Architecture (current)

### Package layout (additions/changes this revision; v0.3's additions are unchanged and carry forward)

```
com.flamerealms
├── persistence/
│   └── AsyncDatabaseExecutor       # NEW — bounded thread pool for all DB transactions, §4
├── war/
│   ├── WarForceService             # unchanged from v0.3
│   ├── WarParticipantTracker       # unchanged from v0.3
│   └── NexusCaptureService         # NEW — implements §3's formal capture-rate model specifically
```

Everything else in the package layout (`domain`, `service`, `presentation`, `listener`, `command`, `gui`, `task`, `config`, `api`, `util`, and v0.3's `ProgressionService`/`ActivePopulationService`/`ClaimVisualizationService`/`TerritoryMapService`/`PlayerActivityListener`) is unchanged.

`PvpGateListener` is updated to implement the 7-layer chain in §2 exactly (was described as 5 layers in v0.3; New Member War Lock and same-Realm friendly fire are now explicit layers rather than folded into others).

`PricingService` gains the explicit "moderate/saturating" design-goal documentation for `activePopulationMultiplier` (§5) — no interface change, a tuning-guidance note.

### Event flows (financial ones, corrected for async — shape unchanged, execution model corrected)

**Claim purchase:**
```
Player runs /realm claim
  → command handler resolves actor's realm + target chunk, shows preview (ClaimVisualizationService)
  → player confirms
  → main thread: fast pre-check against cached balance/claim state (optimistic, non-authoritative)
  → dispatch to per-realm single-flight queue → AsyncDatabaseExecutor
       → BEGIN; contiguity + unique-chunk check; conditional balance debit; claim-row insert;
         ledger insert — all one transaction; COMMIT (or ROLLBACK, e.g. unique-constraint loss)
  → result hops back to main thread via Bukkit scheduler
  → on success: update ChunkClaimIndex/RealmCache, fire ClaimPurchaseEvent, clear preview, message player
  → on failure: clear preview, message player with the specific reason (funds/contiguity/race lost)
```

**War stake settlement (on Nexus capture reaching 100%, or timeout):**
```
NexusCaptureService/TickService detects the terminal condition on the main thread (reading its
own write-behind-cached capture percent / absolute timestamps)
  → dispatch to AsyncDatabaseExecutor
       → BEGIN; SELECT ... FOR UPDATE on the war row (guarded by state='ACTIVE'); if still ACTIVE:
         settle War Stake (realm↔realm transfer, ascending-id lock order) + ledger inserts;
         set wars.state='ENDED', outcome, ended_at; COMMIT
  → main thread applies: diplomacy_states → TRUCE, broadcast outcome, fire WarStateChangeEvent/WarEndEvent
```

Every other event flow from v0.3 (job payout, shield activation, diplomacy proposal/accept) follows the identical corrected pattern — main-thread validation and cache reads, async transactional commit, main-thread result application — and is not restated line-by-line here to avoid repetition; the two above are representative of the financial and the war-outcome shape respectively.

### Milestones (unchanged numbering and scope from v0.3; sequencing discipline reaffirmed per instruction 2)

M0 (Bootstrap) → M1 (Realm core) → M2 (Economy foundation — now explicitly stands up `AsyncDatabaseExecutor` and the shared ledger-transaction helper here, since every later milestone depends on the corrected async pattern existing from this point on) → M3 (Claims + Territory Visualization + base upkeep) → M4 (Nexus — placement/registration **only**, no capture logic) → M4.5 (Progression & Onboarding, informational-only, confirmed) → M5 (Contribution infrastructure) → M6 (Shield) → **M7 (War — full scope retained: WarService/WarStateMachine, Nexus Capture per §3's formal model, WarForceService, WarParticipantTracker, New Member War Lock, the 7-layer PvpGateListener rewrite, TRUCE, persisted capture percent — nothing removed; internally sequenced as (a) declare/prep/active/timeout/surrender skeleton then (b) capture+imbalance+lock layered on top, both still within M7, per the explicit instruction that expanded scope changes sequencing, not feature content)** → M8 (Jobs) → M9 (Contracts) → M10 (Projects) → M11 (Diplomacy, full) → M12 (Specialization/Power/Rankings) → M13 (Hardening) → `M-Shop` (unscheduled).

**Explicit milestone-discipline note:** M0 implements bootstrap only — no realm/economy/war code of any kind. M1–M6 implement only their own stated scope; in particular M4's Nexus milestone is placement/registration as a domain concept and contains zero capture logic, and the minimal `diplomacy_states` slice needed by M7 is the one piece of "later" schema that exists before its full feature (Diplomacy, M11) — this was already true in v0.3 and is reaffirmed, not changed, here.

---

## Consistency Audit Results

Explicit cross-check, as requested, over the pairs most likely to hide a contradiction:

| Pair | Result |
|---|---|
| "DB is the source of truth" vs. async persistence | **Consistent.** Async changes which thread blocks, not what's durable — the DB transaction is still the only thing that makes a mutation real; the cache is always corrected to match it, never the reverse (§4). |
| Transactional guarantees vs. async execution | **Consistent.** Every previously-specified atomic transaction (claim purchase, transfers, stake settlement, etc.) is still exactly one DB transaction — it simply runs on a worker thread instead of the main thread (§4, §10). |
| WAR PvP vs. New Member War Lock | **Consistent by construction.** Layer 7 (WAR override) is gated by layer 6 (lock) directly — there is no path to the override that skips the lock check (§2). |
| Same-Realm PvP vs. WAR | **Consistent — mutually exclusive contexts.** Layer 3 (same-Realm friendly fire) only ever applies to two members of one Realm; layers 4/6/7 (diplomacy/lock/war-override) only ever apply to two different Realms. Neither can fire in the other's context, so there is no possible conflict between them, by construction rather than by a tie-breaking rule (§2). |
| ALLIANCE vs. WAR | **Consistent — mutually exclusive by schema.** `diplomacy_states` holds exactly one base state per Realm pair; a pair cannot simultaneously be `ALLIANCE` and `WAR` (§9). |
| WorldGuard vs. all PvP overrides | **Consistent.** Layer 1 is evaluated first and nothing below it — including layer 7's WAR override — is ever allowed to reverse a WorldGuard denial (§2). |
| Active Population vs. Realm membership | **Consistent — deliberately decoupled.** The multiplier is driven by active population, never raw membership count; an inactive member contributes nothing to cost, removing any incentive to prune them (§5). |
| Progression information vs. feature locking | **Consistent, now explicit.** Informational-only, confirmed; no code path in any milestone gates a command or feature behind a progression milestone (§6). |
| Milestone sequencing vs. M7 feature scope | **Consistent.** No M7 system was removed or reduced; only its position in the milestone list and its internal (a)/(b) delivery order were addressed, and earlier milestones were re-checked to confirm none of them accidentally implement M7-scoped logic ahead of time (§18). |

No contradiction was found that required inventing a new rule to resolve — every pair above resolves from decisions already on record (D1–D10, the layer model, the schema's own uniqueness constraints), consistent with the instruction not to introduce new gameplay decisions during this audit.

---

## Remaining Open Items

Everything that was open at the end of v0.3 and not addressed by this revision's 11 instructions remains exactly as it was (architecture-decidable, non-blocking): shield pricing combination shape, claim price formula combination shape, upkeep grace-period/release-order specifics, treasury cap enforcement mechanism, Realm Power weights (still explicitly deferred by the source document itself), multi-world claim whitelist mechanics, realm inactivity succession/auto-disband specifics, and the still-outstanding question (not a blocker) of whether AxiomPaper is even still part of the server's plugin list — it appeared in an earlier topology draft, was absent from the most recent one, and has not been mentioned since; this is noted rather than resolved, per the instruction not to invent an answer to something not actually addressed.

**Nothing in this revision introduces a new blocker.** SPEC-027 (WorldGuard), SPEC-028 (Progression), and the async correction were the three items standing between v0.3 and M0-readiness — all three are now resolved.
