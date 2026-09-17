package com.flamerealms.util;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Converts between {@link UUID} and the raw 16-byte big-endian form stored in
 * every {@code BINARY(16)} UUID column in the schema.
 *
 * <p>The MariaDB JDBC driver has no native binding for {@code java.util.UUID}
 * to {@code BINARY(16)}, so every JDBC DAO that reads or writes a UUID column
 * goes through this class rather than handling the byte layout inline.
 */
public final class UuidCodec {

    private static final int UUID_BYTES = 16;

    private UuidCodec() {
    }

    /** Encodes {@code uuid} as 16 bytes (most significant bits first). */
    public static byte[] toBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(UUID_BYTES);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    /** Decodes a 16-byte big-endian value, as produced by {@link #toBytes}, back into a {@link UUID}. */
    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != UUID_BYTES) {
            throw new IllegalArgumentException(
                    "Expected " + UUID_BYTES + " bytes for a UUID, got " + (bytes == null ? "null" : bytes.length));
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long mostSignificantBits = buffer.getLong();
        long leastSignificantBits = buffer.getLong();
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
