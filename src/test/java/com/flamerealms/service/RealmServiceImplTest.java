package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.config.PricingConfig;
import com.flamerealms.domain.ChunkCoordinate;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.domain.TransactionRecord;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.service.exception.CannotKickLeaderException;
import com.flamerealms.service.exception.InsufficientFundsForRealmCreationException;
import com.flamerealms.service.exception.LeaderCannotLeaveException;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.exception.NotInvitedException;
import com.flamerealms.service.exception.NotRealmLeaderException;
import com.flamerealms.service.exception.PlayerAlreadyInRealmException;
import com.flamerealms.service.exception.PlayerNotInRealmException;
import com.flamerealms.service.exception.RealmNameTakenException;
import com.flamerealms.service.exception.RealmNotFoundException;
import com.flamerealms.service.fake.FakeLedgerDao;
import com.flamerealms.service.fake.FakePlayerWalletDao;
import com.flamerealms.service.fake.FakeRealmClaimDao;
import com.flamerealms.service.fake.FakeRealmDao;
import com.flamerealms.service.fake.FakeRealmInviteDao;
import com.flamerealms.service.fake.FakeRealmMemberDao;
import com.flamerealms.service.fake.FakeRealmRankDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.fakeConnection;
import static com.flamerealms.service.support.InlineAsyncDatabaseExecutors.inline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RealmServiceImpl}.
 *
 * <p>Uses hand-written, in-memory {@code Fake*Dao} test doubles (see
 * {@code com.flamerealms.service.fake}) for the DAO layer — no Mockito
 * mocks of the DAO interfaces, no real database, no Docker. The one
 * concrete collaborator that cannot be faked by hand,
 * {@link AsyncDatabaseExecutor} (a {@code final} class tied to a real
 * connection pool), is stubbed via
 * {@code InlineAsyncDatabaseExecutors.inline(...)} to run submitted work
 * synchronously — see that class's Javadoc. Every test here runs in
 * milliseconds.
 */
final class RealmServiceImplTest {

    private static final long OFFICER_PERMISSIONS =
            RealmPermission.INVITE.bit() | RealmPermission.KICK.bit() | RealmPermission.CLAIM.bit()
                    | RealmPermission.DEPOSIT.bit() | RealmPermission.DIPLOMACY.bit();

    private static final long CREATION_FEE_CENTS = 50_000L;
    private static final String NEXUS_WORLD = "world";
    private static final int NEXUS_X = 100;
    private static final int NEXUS_Y = 64;
    private static final int NEXUS_Z = -33;
    // floorDiv(100, 16) = 6, floorDiv(-33, 16) = -3.
    private static final ChunkCoordinate NEXUS_CHUNK = new ChunkCoordinate(NEXUS_WORLD, 6, -3);

    private Connection connection;
    private RealmCache realmCache;
    private FakeRealmDao realmDao;
    private FakeRealmRankDao realmRankDao;
    private FakeRealmMemberDao realmMemberDao;
    private FakeRealmClaimDao realmClaimDao;
    private FakeRealmInviteDao realmInviteDao;
    private FakePlayerWalletDao playerWalletDao;
    private FakeLedgerDao ledgerDao;
    private PricingConfig pricingConfig;
    private RealmServiceImpl service;

    @BeforeEach
    void setUp() {
        connection = fakeConnection();
        realmCache = new RealmCache();
        realmDao = new FakeRealmDao();
        realmRankDao = new FakeRealmRankDao();
        realmMemberDao = new FakeRealmMemberDao();
        realmClaimDao = new FakeRealmClaimDao();
        realmInviteDao = new FakeRealmInviteDao();
        playerWalletDao = new FakePlayerWalletDao();
        ledgerDao = new FakeLedgerDao(playerWalletDao, realmDao);

        pricingConfig = new PricingConfig(
                List.of(new PricingConfig.PriceTier(Integer.MAX_VALUE, 100L)),
                List.of(new PricingConfig.MultiplierTier(Integer.MAX_VALUE, 1.0)),
                100L, 60, 7, 10.0, 0.5, CREATION_FEE_CENTS, 3
        );

        AsyncDatabaseExecutor executor = inline(connection);
        service = new RealmServiceImpl(executor, realmCache, realmDao, realmRankDao, realmMemberDao,
                realmClaimDao, realmInviteDao, ledgerDao, pricingConfig);
    }

    /** Gives {@code player} enough personal balance to cover the realm creation fee (and then some). */
    private void fund(UUID player, long cents) {
        playerWalletDao.ensureExists(connection, player);
        playerWalletDao.tryAdjustBalance(connection, player, cents);
    }

    private Realm createRealm(UUID leader, String name) {
        fund(leader, CREATION_FEE_CENTS * 2);
        return service.createRealm(leader, name, NEXUS_WORLD, NEXUS_X, NEXUS_Y, NEXUS_Z).join();
    }

    @Test
    void createRealmSeedsRanksAndLeaderMembership() {
        UUID leader = UUID.randomUUID();

        Realm realm = createRealm(leader, "ember");

        assertThat(realm.name()).isEqualTo("ember");
        assertThat(realm.leaderUuid()).isEqualTo(leader);
        assertThat(realmCache.getByName("ember")).contains(realm);
        assertThat(realmCache.getByPlayer(leader)).contains(realm);

        RealmRank leaderRank = realmRankDao.findByRealmAndName(connection, realm.id(), "Leader").orElseThrow();
        RealmRank officerRank = realmRankDao.findByRealmAndName(connection, realm.id(), "Officer").orElseThrow();
        RealmRank memberRank = realmRankDao.findByRealmAndName(connection, realm.id(), "Member").orElseThrow();

        assertThat(leaderRank.permissions()).isEqualTo(RealmPermission.ALL);
        assertThat(officerRank.permissions()).isEqualTo(OFFICER_PERMISSIONS);
        assertThat(memberRank.permissions()).isEqualTo(0L);
        assertThat(memberRank.isDefault()).isTrue();
        assertThat(leaderRank.isDefault()).isFalse();
        assertThat(officerRank.isDefault()).isFalse();

        RealmMember leaderMembership = realmMemberDao.findByPlayerUuid(connection, leader).orElseThrow();
        assertThat(leaderMembership.realmId()).isEqualTo(realm.id());
        assertThat(leaderMembership.rankId()).isEqualTo(leaderRank.id());
    }

    @Test
    void createRealmSetsTheNexusChargesTheFeeAndFoundsAFreeClaimOnItsChunk() {
        UUID leader = UUID.randomUUID();
        fund(leader, CREATION_FEE_CENTS * 2);

        Realm realm = service.createRealm(leader, "ember", NEXUS_WORLD, NEXUS_X, NEXUS_Y, NEXUS_Z).join();

        assertThat(realm.nexusWorld()).isEqualTo(NEXUS_WORLD);
        assertThat(realm.nexusX()).isEqualTo(NEXUS_X);
        assertThat(realm.nexusY()).isEqualTo(NEXUS_Y);
        assertThat(realm.nexusZ()).isEqualTo(NEXUS_Z);

        // Fee charged from the leader's personal wallet, not the (nonexistent
        // yet) realm treasury.
        assertThat(playerWalletDao.balanceOf(leader)).isEqualTo(CREATION_FEE_CENTS * 2 - CREATION_FEE_CENTS);
        List<TransactionRecord> entries = ledgerDao.entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).reason()).isEqualTo("REALM_CREATION_FEE");
        assertThat(entries.get(0).amountCents()).isEqualTo(CREATION_FEE_CENTS);

        // The Nexus's own chunk is claimed for free, both in the DAO and the cache.
        assertThat(realmClaimDao.findByRealm(connection, realm.id())).hasSize(1);
        assertThat(realmClaimDao.findByRealm(connection, realm.id()).get(0).pricePaidCents()).isZero();
        assertThat(realmClaimDao.findByRealm(connection, realm.id()).get(0).coordinate()).isEqualTo(NEXUS_CHUNK);
        assertThat(realmCache.claimsOf(realm.id())).containsExactly(NEXUS_CHUNK);
    }

    @Test
    void createRealmRejectsALeaderWhoCannotAffordTheCreationFee() {
        UUID leader = UUID.randomUUID();
        fund(leader, CREATION_FEE_CENTS - 1);

        assertThatThrownBy(() ->
                service.createRealm(leader, "ember", NEXUS_WORLD, NEXUS_X, NEXUS_Y, NEXUS_Z).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(InsufficientFundsForRealmCreationException.class);

        // Nothing was left behind: no realm, no charge, no claim.
        assertThat(realmCache.isPlayerInRealm(leader)).isFalse();
        assertThat(realmCache.getByName("ember")).isEmpty();
        assertThat(playerWalletDao.balanceOf(leader)).isEqualTo(CREATION_FEE_CENTS - 1);
        assertThat(ledgerDao.entries()).isEmpty();
    }

    @Test
    void createRealmRejectsPlayerAlreadyInARealm() {
        UUID leader = UUID.randomUUID();
        createRealm(leader, "ember");

        fund(leader, CREATION_FEE_CENTS * 2);
        assertThatThrownBy(() ->
                service.createRealm(leader, "another-realm", NEXUS_WORLD, NEXUS_X, NEXUS_Y, NEXUS_Z).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PlayerAlreadyInRealmException.class);
    }

    @Test
    void createRealmRejectsDuplicateName() {
        createRealm(UUID.randomUUID(), "ember");

        UUID secondLeader = UUID.randomUUID();
        fund(secondLeader, CREATION_FEE_CENTS * 2);
        assertThatThrownBy(() ->
                service.createRealm(secondLeader, "ember", NEXUS_WORLD, NEXUS_X, NEXUS_Y, NEXUS_Z).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RealmNameTakenException.class);

        // The second leader must not have been left with a dangling membership.
        assertThat(realmCache.isPlayerInRealm(secondLeader)).isFalse();
    }

    @Test
    void leaveRejectsTheRealmsLeader() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        assertThatThrownBy(() -> service.leave(leader).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(LeaderCannotLeaveException.class);

        // Leader is still a member after the rejected leave.
        assertThat(realmCache.getByPlayer(leader)).contains(realm);
    }

    @Test
    void setRankRejectsAnActorWithoutManageRanks() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member).join();
        service.join(member, realm.id()).join();

        RealmRank officerRank = realmRankDao.findByRealmAndName(connection, realm.id(), "Officer").orElseThrow();

        // `member` holds the default "Member" rank (permissions = 0), which
        // lacks MANAGE_RANKS, so it cannot promote itself (or anyone else).
        assertThatThrownBy(() -> service.setRank(realm.id(), member, member, officerRank.id()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MissingPermissionException.class);

        // The rank must not have changed.
        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, member).orElseThrow();
        RealmRank defaultRank = realmRankDao.findDefaultRank(connection, realm.id()).orElseThrow();
        assertThat(membership.rankId()).isEqualTo(defaultRank.id());
    }

    @Test
    void setRankSucceedsWhenActorHasManageRanks() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member).join();
        service.join(member, realm.id()).join();

        RealmRank officerRank = realmRankDao.findByRealmAndName(connection, realm.id(), "Officer").orElseThrow();

        service.setRank(realm.id(), leader, member, officerRank.id()).join();

        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, member).orElseThrow();
        assertThat(membership.rankId()).isEqualTo(officerRank.id());
    }

    @Test
    void inviteThenJoinHappyPath() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");
        UUID invitee = UUID.randomUUID();

        service.invite(realm.id(), leader, invitee).join();
        service.join(invitee, realm.id()).join();

        assertThat(realmCache.getByPlayer(invitee)).contains(realm);

        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, invitee).orElseThrow();
        RealmRank defaultRank = realmRankDao.findDefaultRank(connection, realm.id()).orElseThrow();
        assertThat(membership.realmId()).isEqualTo(realm.id());
        assertThat(membership.rankId()).isEqualTo(defaultRank.id());

        // The invite is consumed on a successful join.
        assertThat(realmInviteDao.exists(connection, realm.id(), invitee)).isFalse();
    }

    @Test
    void joinRejectsAPlayerWhoWasNeverInvited() {
        Realm realm = createRealm(UUID.randomUUID(), "ember");
        UUID uninvited = UUID.randomUUID();

        assertThatThrownBy(() -> service.join(uninvited, realm.id()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NotInvitedException.class);

        assertThat(realmCache.isPlayerInRealm(uninvited)).isFalse();
        assertThat(realmMemberDao.findByPlayerUuid(connection, uninvited)).isEmpty();
    }

    @Test
    void getMemberUuidsFailsFastOnAnUnknownRealm() {
        assertThatThrownBy(() -> service.getMemberUuids(12345L).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RealmNotFoundException.class);
    }

    @Test
    void getMemberUuidsReturnsEveryCurrentMember() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member).join();
        service.join(member, realm.id()).join();

        assertThat(service.getMemberUuids(realm.id()).join()).containsExactlyInAnyOrder(leader, member);
    }

    @Test
    void kickRemovesAMemberWhenActorHasKickPermission() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member).join();
        service.join(member, realm.id()).join();

        service.kick(realm.id(), leader, member).join();

        assertThat(realmMemberDao.findByPlayerUuid(connection, member)).isEmpty();
        assertThat(realmCache.isPlayerInRealm(member)).isFalse();
    }

    @Test
    void kickRejectsAnActorWithoutKickPermission() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        UUID kicker = UUID.randomUUID();
        service.invite(realm.id(), leader, kicker).join(); // joins with default (no-permission) rank
        service.join(kicker, realm.id()).join();

        UUID target = UUID.randomUUID();
        service.invite(realm.id(), leader, target).join();
        service.join(target, realm.id()).join();

        assertThatThrownBy(() -> service.kick(realm.id(), kicker, target).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MissingPermissionException.class);

        assertThat(realmMemberDao.findByPlayerUuid(connection, target)).isPresent();
    }

    @Test
    void kickRejectsATargetWhoIsNotAMember() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        assertThatThrownBy(() -> service.kick(realm.id(), leader, UUID.randomUUID()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PlayerNotInRealmException.class);
    }

    @Test
    void kickRejectsKickingTheRealmsLeader() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        assertThatThrownBy(() -> service.kick(realm.id(), leader, leader).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CannotKickLeaderException.class);

        assertThat(realmCache.getByPlayer(leader)).contains(realm);
    }

    @Test
    void transferLeadershipSwapsTheLeaderAndTheTwoMembersRanks() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member).join();
        service.join(member, realm.id()).join();
        RealmRank memberRankBeforeTransfer = realmRankDao.findDefaultRank(connection, realm.id()).orElseThrow();
        RealmRank leaderRank = realmRankDao.findByRealmAndName(connection, realm.id(), "Leader").orElseThrow();

        service.transferLeadership(realm.id(), leader, member).join();

        assertThat(realmCache.getByName("ember").orElseThrow().leaderUuid()).isEqualTo(member);

        RealmMember newLeaderMembership = realmMemberDao.findByPlayerUuid(connection, member).orElseThrow();
        RealmMember oldLeaderMembership = realmMemberDao.findByPlayerUuid(connection, leader).orElseThrow();
        assertThat(newLeaderMembership.rankId()).isEqualTo(leaderRank.id());
        assertThat(oldLeaderMembership.rankId()).isEqualTo(memberRankBeforeTransfer.id());
    }

    @Test
    void transferLeadershipRejectsAnActorWhoIsNotTheCurrentLeader() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member).join();
        service.join(member, realm.id()).join();

        assertThatThrownBy(() -> service.transferLeadership(realm.id(), member, member).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NotRealmLeaderException.class);
    }

    @Test
    void transferLeadershipToTheCurrentLeaderIsANoOp() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        service.transferLeadership(realm.id(), leader, leader).join();

        assertThat(realmCache.getByName("ember").orElseThrow().leaderUuid()).isEqualTo(leader);
    }

    @Test
    void transferLeadershipRejectsANewLeaderWhoIsNotAMember() {
        UUID leader = UUID.randomUUID();
        Realm realm = createRealm(leader, "ember");

        assertThatThrownBy(() -> service.transferLeadership(realm.id(), leader, UUID.randomUUID()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PlayerNotInRealmException.class);
    }
}
