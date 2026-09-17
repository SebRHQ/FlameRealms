package com.flamerealms.domain;

/**
 * Every permission bit a realm rank can hold, packed into the single
 * {@code BIGINT UNSIGNED} {@code realm_ranks.permissions} column.
 *
 * <p>The bit layout below is fixed project-wide and defined once, here, even
 * though most of these permissions have no feature checking them yet (M1
 * only ships the realm/rank/member data model — invites, claims, the
 * treasury, diplomacy, and the nexus are all later milestones). Do not
 * renumber or reorder existing constants once a permission bit has shipped:
 * doing so would silently change the meaning of every already-stored
 * {@code permissions} value.
 */
public enum RealmPermission {

    INVITE(1L << 0),
    KICK(1L << 1),
    CLAIM(1L << 2),
    UNCLAIM(1L << 3),
    DEPOSIT(1L << 4),
    WITHDRAW(1L << 5),
    MANAGE_RANKS(1L << 6),
    DIPLOMACY(1L << 7),
    DECLARE_WAR(1L << 8),
    MANAGE_NEXUS(1L << 9);

    /** Bitmask combining every permission bit defined above. */
    public static final long ALL =
            INVITE.bit | KICK.bit | CLAIM.bit | UNCLAIM.bit | DEPOSIT.bit
                    | WITHDRAW.bit | MANAGE_RANKS.bit | DIPLOMACY.bit
                    | DECLARE_WAR.bit | MANAGE_NEXUS.bit;

    private final long bit;

    RealmPermission(long bit) {
        this.bit = bit;
    }

    /** This permission's single-bit value within a {@code permissions} mask. */
    public long bit() {
        return bit;
    }

    /** Whether {@code mask} grants {@code perm}. */
    public static boolean has(long mask, RealmPermission perm) {
        return (mask & perm.bit) != 0;
    }
}
