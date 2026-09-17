package com.flamerealms.service;

import com.flamerealms.cache.RealmCache;
import com.flamerealms.domain.Realm;
import com.flamerealms.domain.RealmMember;
import com.flamerealms.domain.RealmPermission;
import com.flamerealms.domain.RealmRank;
import com.flamerealms.persistence.AsyncDatabaseExecutor;
import com.flamerealms.service.exception.LeaderCannotLeaveException;
import com.flamerealms.service.exception.MissingPermissionException;
import com.flamerealms.service.exception.NotInvitedException;
import com.flamerealms.service.exception.PlayerAlreadyInRealmException;
import com.flamerealms.service.exception.RealmNameTakenException;
import com.flamerealms.service.fake.FakeRealmDao;
import com.flamerealms.service.fake.FakeRealmMemberDao;
import com.flamerealms.service.fake.FakeRealmRankDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
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

    private Connection connection;
    private RealmCache realmCache;
    private FakeRealmDao realmDao;
    private FakeRealmRankDao realmRankDao;
    private FakeRealmMemberDao realmMemberDao;
    private RealmServiceImpl service;

    @BeforeEach
    void setUp() {
        connection = fakeConnection();
        realmCache = new RealmCache();
        realmDao = new FakeRealmDao();
        realmRankDao = new FakeRealmRankDao();
        realmMemberDao = new FakeRealmMemberDao();

        AsyncDatabaseExecutor executor = inline(connection);
        service = new RealmServiceImpl(executor, realmCache, realmDao, realmRankDao, realmMemberDao);
    }

    @Test
    void createRealmSeedsRanksAndLeaderMembership() {
        UUID leader = UUID.randomUUID();

        Realm realm = service.createRealm(leader, "ember").join();

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
    void createRealmRejectsPlayerAlreadyInARealm() {
        UUID leader = UUID.randomUUID();
        service.createRealm(leader, "ember").join();

        assertThatThrownBy(() -> service.createRealm(leader, "another-realm").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PlayerAlreadyInRealmException.class);
    }

    @Test
    void createRealmRejectsDuplicateName() {
        service.createRealm(UUID.randomUUID(), "ember").join();

        UUID secondLeader = UUID.randomUUID();
        assertThatThrownBy(() -> service.createRealm(secondLeader, "ember").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RealmNameTakenException.class);

        // The second leader must not have been left with a dangling membership.
        assertThat(realmCache.isPlayerInRealm(secondLeader)).isFalse();
    }

    @Test
    void leaveRejectsTheRealmsLeader() {
        UUID leader = UUID.randomUUID();
        Realm realm = service.createRealm(leader, "ember").join();

        assertThatThrownBy(() -> service.leave(leader).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(LeaderCannotLeaveException.class);

        // Leader is still a member after the rejected leave.
        assertThat(realmCache.getByPlayer(leader)).contains(realm);
    }

    @Test
    void setRankRejectsAnActorWithoutManageRanks() {
        UUID leader = UUID.randomUUID();
        Realm realm = service.createRealm(leader, "ember").join();

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member);
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
        Realm realm = service.createRealm(leader, "ember").join();

        UUID member = UUID.randomUUID();
        service.invite(realm.id(), leader, member);
        service.join(member, realm.id()).join();

        RealmRank officerRank = realmRankDao.findByRealmAndName(connection, realm.id(), "Officer").orElseThrow();

        service.setRank(realm.id(), leader, member, officerRank.id()).join();

        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, member).orElseThrow();
        assertThat(membership.rankId()).isEqualTo(officerRank.id());
    }

    @Test
    void inviteThenJoinHappyPath() {
        UUID leader = UUID.randomUUID();
        Realm realm = service.createRealm(leader, "ember").join();
        UUID invitee = UUID.randomUUID();

        service.invite(realm.id(), leader, invitee);
        service.join(invitee, realm.id()).join();

        assertThat(realmCache.getByPlayer(invitee)).contains(realm);

        RealmMember membership = realmMemberDao.findByPlayerUuid(connection, invitee).orElseThrow();
        RealmRank defaultRank = realmRankDao.findDefaultRank(connection, realm.id()).orElseThrow();
        assertThat(membership.realmId()).isEqualTo(realm.id());
        assertThat(membership.rankId()).isEqualTo(defaultRank.id());
    }

    @Test
    void joinRejectsAPlayerWhoWasNeverInvited() {
        Realm realm = service.createRealm(UUID.randomUUID(), "ember").join();
        UUID uninvited = UUID.randomUUID();

        assertThatThrownBy(() -> service.join(uninvited, realm.id()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NotInvitedException.class);

        assertThat(realmCache.isPlayerInRealm(uninvited)).isFalse();
        assertThat(realmMemberDao.findByPlayerUuid(connection, uninvited)).isEmpty();
    }
}
