package com.bbororo.rtb.dsp.proof.internal;

import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ReservationNoticeClaims;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** 봉인 본문의 고정된 이진 표현이다. 형식 버전은 외부 봉투가 소유한다. */
final class ReservationNoticeBinaryFormat {

    private static final int MAX_STRING_BYTES = 4_096;

    private ReservationNoticeBinaryFormat() {
    }

    static byte[] encode(SealReservationNotice notice) {
        try {
            var output = new ByteArrayOutputStream(256);
            try (var data = new DataOutputStream(output)) {
                data.writeByte(notice.kind().ordinal());
                var claims = notice.claims();
                writeString(data, claims.authenticatedSspId());
                writeString(data, claims.regionId());
                writeString(data, claims.reservationId());
                writeString(data, claims.leaseId());
                writeString(data, claims.campaignId());
                writeString(data, claims.bidId());
                data.writeLong(claims.impressionAmountMicros());
                writeInstant(data, claims.reservedAt());
                writeInstant(data, claims.expiresAt());
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new NoticeIssuanceException("failed to encode reservation notice", failure);
        }
    }

    static SealReservationNotice decode(byte[] encoded) {
        try (var data = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int kindOrdinal = data.readUnsignedByte();
            ReservationNoticeKind[] kinds = ReservationNoticeKind.values();
            if (kindOrdinal >= kinds.length) {
                throw new IllegalArgumentException("unknown notice kind ordinal: " + kindOrdinal);
            }
            String authenticatedSspId = readString(data);
            String regionId = readString(data);
            String reservationId = readString(data);
            String leaseId = readString(data);
            String campaignId = readString(data);
            String bidId = readString(data);
            long amount = data.readLong();
            Instant reservedAt = readInstant(data);
            Instant expiresAt = readInstant(data);
            if (data.read() != -1) {
                throw new IllegalArgumentException("reservation notice contains trailing bytes");
            }
            return new SealReservationNotice(
                    kinds[kindOrdinal],
                    new ReservationNoticeClaims(
                            authenticatedSspId,
                            regionId,
                            reservationId,
                            leaseId,
                            campaignId,
                            bidId,
                            amount,
                            reservedAt,
                            expiresAt
                    )
            );
        } catch (EOFException failure) {
            throw new IllegalArgumentException("truncated reservation notice", failure);
        } catch (IOException failure) {
            throw new IllegalArgumentException("malformed reservation notice", failure);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("reservation notice string exceeds 4096 UTF-8 bytes");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("invalid reservation notice string length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated reservation notice string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeInstant(DataOutputStream output, Instant instant) throws IOException {
        output.writeLong(instant.getEpochSecond());
        output.writeInt(instant.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        return Instant.ofEpochSecond(input.readLong(), input.readInt());
    }
}
