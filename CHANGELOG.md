# Changelog

All notable changes to the FlameRealms project documentation are recorded here. No code has been implemented yet — entries so far are specification/architecture changes only.

## [Unreleased]

### Targeted corrections applied (TECHNICAL_SPEC.md v0.3 → v0.4)

A correction pass, not a redesign — nothing from v0.3 was removed; three open items were resolved, one real defect was fixed, and one mechanic was formalized precisely. Full detail in `TECHNICAL_SPEC.md`.

**Fixed (real defect)**
- **Async DB I/O.** v0.2/v0.3 specified synchronous, transactional financial writes without ruling out running them on the Paper main thread — a genuine tick-blocking risk under DB latency. Corrected: all blocking JDBC now runs on a bounded async executor, never inline in a game-event handler. Every existing guarantee (balance+ledger in one transaction, deterministic cross-entity lock ordering, crash/restart safety, DB as sole source of truth) is preserved exactly — only the executing thread changed. In-process per-realm/per-player locks are now explicitly documented as a dispatch-ordering/UX convenience only, never a substitute for the database transaction.

**Resolved (previously open)**
- **WorldGuard** is now a confirmed, required backend dependency for spawn protection (hard `depend:`, no fallback implemented) — closes SPEC-027.
- **Progression** is confirmed informational-only in MVP — no feature/command locking — closes SPEC-028.
- **AuthMe wording** in `SECURITY.md` was stale (implied backend-side authentication gating); corrected to reflect that AuthMe is proxy-side and FlameRealms implements no authentication logic of its own.
- **Same-Realm friendly fire** is now an explicit, per-Realm-configurable rule (off by default), modeled as a concept separate from Realm-vs-Realm diplomacy. New column `realms.friendly_fire_enabled`.
- **ALLIANCE** is now explicitly PvP-blocking, identical to PEACE/TRUCE.
- **PvP precedence** is now a fully specified 7-layer chain (WorldGuard → Realm territory context → Same-Realm friendly fire → Realm-vs-Realm diplomacy → PeacefulToggle → New Member War Lock → War-state override), up from v0.3's 5-layer version — New Member War Lock and friendly fire are now explicit layers rather than folded into others.

**Formalized (was previously a description, now a precise model)**
- **Nexus Capture** now has an exact rate formula covering every requested case: capture zone defined by a configurable radius, attacker/defender eligibility spelled out (including how New Member War Lock excludes a locked player from the calculation entirely, not just from scoring), a distinct spatial "effective combatant" definition for capture vs. the temporal one used for War Score/rewards, and explicit branch-by-branch behavior for nobody-present, undefended-zone, defenders-only, attacker-advantage, defender-advantage, and exact-contest cases. 100% capture ends the war as `ATTACKER_WIN`; timeout without 100% ends it as `DEFENDER_WIN`.
- **Active Population's incentive structure** was reviewed per an explicit game-design concern (does this incentivize kicking inactive friends?) — confirmed it does not, since inactive members already contribute nothing to the multiplier, and the curve's design goal is now stated explicitly as "moderate, low marginal cost," without adding any new ranking/scoring subsystem.

**Reaffirmed (no change, explicitly confirmed not to have regressed)**
- No M7 system (WarService/WarStateMachine, Nexus Capture, WarForceService, WarParticipantTracker, New Member War Lock, the PvP listener rewrite, TRUCE, persisted capture percent) was removed or reduced — only its internal delivery sequencing and its position relative to other milestones were ever in scope for correction. M0–M6 are re-confirmed to implement only their own stated scope.

**Consistency audit performed** across D1–D10, PvP precedence, diplomacy states, WAR lifecycle, Nexus Capture, Force Imbalance, New Member War Lock, Active Population/Upkeep, economy/transaction atomicity, the async DB model, claim atomicity/contiguity, progression, topology, WorldGuard, AuthMe, plugin integrations, schema, services, configuration, milestones, and security documentation — no contradiction required inventing a new rule to resolve; every pair checked resolves from decisions already on record.

`TECHNICAL_SPEC.md` is now fully self-contained (v0.3 had left the full schema and concurrency tables as "see v0.2," which no longer existed once superseded — restated in full in v0.4, a documentation-completeness fix rather than a new decision).

### Final product decisions applied (TECHNICAL_SPEC.md v0.2 → v0.3)

D1–D10 from v0.2's `Decisions Required Before M0` are now settled by explicit product direction, plus several new gameplay systems. Full detail in `TECHNICAL_SPEC.md`; summary here.

**Decided**
- D1 — PvP is ON by default; Realm territory PvP is gated by diplomatic relationship (PEACE/TRUCE/ALLIANCE block it, WAR allows it, NEUTRAL doesn't care), spawn protection delegated to WorldGuard. Formalized as an exact 5-layer precedence chain (WorldGuard → Realm claims → Realm diplomacy → PeacefulToggle → War state), closing the PeacefulToggle loophole risk flagged in v0.2 (SPEC-026).
- D2 — War win condition: 100% uninterrupted Nexus capture, or defender wins on timeout. War Score is now explicitly secondary. Losing Realm is never destroyed/disbanded. This pulls Nexus Capture into MVP scope (previously v1.2) — a real milestone-plan change, not just a clarification.
- D3 — MVP Nexus = Beacon, modeled as a domain concept independent of its physical block so ItemsAdder can later swap presentation without touching capture logic.
- D4 — Claims must be contiguous to existing territory or the Nexus.
- D5 — Flat, configurable realm creation fee, default `$500`.
- D6 — Unclaim refund is 0%.
- D7 — Disband destroys treasury, releases claims, removes members, no personal cash-out; blocked during active/preparation wars.
- D8 — FlameRealms owns the economy outright; no Essentials migration needed (no meaningful existing balances); Essentials must not be a source of truth.
- D9 — Contribution uses a hybrid model: small passive credit from job income, substantially more from explicit Realm-directed activity. Weights configurable.
- D10 — Player Shops deferred to an unscheduled `M-Shop` milestone; no third-party shop dependency added now.

**Added — new systems**
- **Active Population Upkeep** — daily upkeep now scales with territory *and* a configurable, dual-signal (presence ∪ contribution) measure of active membership, replacing the earlier flat-per-chunk default outright. New table `realm_member_activity`.
- **War Force Imbalance** — diminishing-returns modifiers (not a flat damage debuff) on Nexus capture rate, War Score, and rewards based on the actual ratio of currently-engaged combatants per side. New table `war_participants`, new `WarForceService`.
- **New Member War Lock** — a freshly-joined Realm member cannot become a war combatant (the War-state PvP override doesn't apply to them) until a configurable tenure threshold passes; everything else (building, trading, funding projects) remains immediately available. Nomad gameplay unaffected.
- **Progression System** — new `ProgressionService`, config-driven milestone definitions (`progression.yml`), `/realm progress`, Nexus GUI parity, contextual unlock messages. New table `realm_progression`.
- **Territory Visualization & Claim UX** — new `ClaimVisualizationService` (particle chunk-boundary preview, vanilla Paper particle API, no packet-library dependency) and `TerritoryMapService` (`/realm map` text map), plus a chat-driven `/realm claim` preview→confirm flow. Explicitly kept out of `ClaimService`.
- Diplomacy base-state enum gains `TRUCE`, mapped automatically from War Cooldown — makes the post-war peace period an enforceable no-PvP state instead of just internal war bookkeeping.
- `wars.nexus_capture_percent` — persisted, restart-safe capture progress (a crash mid-siege now loses at most a few seconds, not the whole capture).

**Updated — server topology**
- Plugin list now split across a Velocity proxy and the Purpur backend. AuthMe now runs at the proxy — removes the earlier backend-side "gate listeners on authenticated login" requirement entirely, since the backend never sees an unauthenticated session.
- Clarified: LPC (chat plugin, superseded by EssentialsChat) and NixMC (an unreleased in-house Discord-linking plugin) — both no-integration items, removed from the open-questions list.
- New backend plugins classified: EssentialsChat, PlayerRider, ViaVersion, VoidGen (no integration needed / compatibility-only); Vulcan (anticheat — flagged to verify it tolerates legitimate large-scale Nexus-siege combat); WorldEditSUI (inspiration for FlameRealms's own territory particles, no code dependency); PlugManX (flagged — FlameRealms should not be hot-reloaded through it).
- **New open item:** WorldGuard is referenced by D1 as the spawn-protection provider but does not appear anywhere in the given topology — flagged as SPEC-027, the one remaining item before the PvP model can be considered fully final.

**Milestone plan changes**
- Nexus Capture, Force Imbalance, and New Member War Lock move into the War milestone (renumbered M7, was M6) instead of a later v1.2 milestone.
- Contribution infrastructure moved earlier (M5, was M9) — both Jobs' passive-credit path (D9) and the Active Population upkeep formula's contribution signal need it before their own milestones land.
- New milestone M4.5 (Progression & Onboarding infrastructure), inserted right after Nexus.
- New unscheduled milestone `M-Shop` for the deferred Player Shops feature (D10).

### Architecture audit (TECHNICAL_SPEC.md v0.1 → v0.2)

Full audit of the proposed technical architecture against `PROJECT.md`, plus a first review of how FlameRealms coexists with the existing production plugin stack. See `TECHNICAL_SPEC.md` for full detail.

**Added**
- `## Specification Issues` — 26 tracked issues (SPEC-001…SPEC-026) covering PvP, Shield, War lifecycle, Nexus, Claims/pricing, Upkeep, Realm creation cost, Unclaim refunds, Disband, Economy ownership, Player wallets, Realm treasury/ranks/power, Jobs, Contracts, Projects, Diplomacy, multi-world, inactivity, concurrency, crash recovery, and two newly-identified gaps (Player Shops has no owner; PeacefulToggle likely conflicts with the PvP rule).
- `## Existing Plugin Integrations` — every plugin in the current stack classified as Required / Optional Integration / Compatibility Only / No Integration Needed.
- `## Dependency Architecture` — Core / Optional / Server-Only dependency tiers.
- `## Database Audit`, `## Concurrency and Transaction Audit`, `## Testing Audit` — formalized as explicit checklists/tables rather than prose.
- `## Decisions Required Before M0` — 10 numbered decisions (D1–D10), each with 2–3 options and consequences, deliberately left unresolved for product/human input.
- New tables: `player_wallets`, `diplomacy_states`, `diplomacy_agreements`, `diplomacy_proposals` (replacing `diplomacy_relations`).
- `SECURITY.md` created (previously empty placeholder) — financial integrity guarantees, crash/restart safety, concurrency safety, permission model, data handled, known accepted risks, operational recommendations.
- `TODO.md` populated (previously empty placeholder) with a blocking-decisions checklist, schema/process fixes tied to milestones, plugin integration action items, and new required tests.

**Fixed (real bugs found in v0.1, not just clarifications)**
- Player wallets had no persistence table at all in v0.1 — personal balances only existed in memory.
- The claim-purchase flow performed the treasury withdraw and the claim-row insert as two sequential commits instead of one transaction — a crash between them could lose money with no claim granted, or (in principle) grant a claim without a durable debit.
- Every flow diagram in v0.1 wrote its audit ledger (`transactions`) row as a separate **async** step after the synchronous balance mutation — violating "every committed transaction must have an audit record." Now folded into the same transaction as the balance write.
- Diplomacy proposals were described as "in-memory first, persisted once accepted" — not restart-safe. Proposals are now persisted immediately in `diplomacy_proposals`.
- Diplomacy's data model conflated a mutually-exclusive base state (Peace/Neutral/Alliance/War) with stackable overlay agreements (Trade Agreement, Non-Aggression Pact, etc.) into one table — split into a two-tier model.
- No enforcement existed for the realm-level treasury cap implied by realm progression (`PROJECT.md` §18) despite the level-up benefit being explicitly listed.
- Shield could previously be purchased mid-war with no guard, effectively letting a losing side buy out of an active war — now blocked while a war is in `PREPARATION`/`ACTIVE`.
- The war-declaration shield guard was previously one-sided (attacker only) — made symmetric (either side being shielded blocks a new declaration).

**Retracted**
- v0.1's inline "Decision:" notes, which silently resolved a number of open product questions (PvP rule, claim pricing shape, Nexus representation, MVP war outcome, shield pricing combination, contribution model, disband handling, and others) without flagging them as choices. These are now tracked as numbered Specification Issues, with genuine product/gameplay calls surfaced explicitly in `## Decisions Required Before M0` instead of being pre-decided.

No implementation code exists yet; this entry covers specification changes only.
