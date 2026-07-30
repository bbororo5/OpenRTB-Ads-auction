package com.bbororo.rtb.ssp.claim;

import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.BillingDeliveryTask;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryLease;
import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.LeasedBillingDelivery;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** 청구 근거와 burl 전달 작업을 한 행·한 트랜잭션으로 보존하는 지역 PostgreSQL 어댑터다. */
public final class PostgreSqlClaimDeliveryStore implements ClaimDeliveryStore {

    private static final String INSERT_CLAIM = """
            INSERT INTO ssp_billing_delivery (
                delivery_id, proof_digest, provider_id, provider_request_id, imp_id,
                slot_auction_key, dsp_id, cpm_milli_krw, billing_url, billing_deadline, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (slot_auction_key) DO NOTHING
            """;

    private static final String SELECT_PROOF_DIGEST_BY_SLOT = """
            SELECT proof_digest
              FROM ssp_billing_delivery
             WHERE slot_auction_key = ?
            """;

    private static final String EXPIRE_DUE = """
            UPDATE ssp_billing_delivery
               SET state = 'UNDELIVERED', completed_at = ?
             WHERE state IN ('PENDING', 'LEASED')
               AND billing_deadline <= ?
            """;

    private static final String LEASE_ONE = """
            WITH candidate AS (
                SELECT delivery_id
                  FROM ssp_billing_delivery
                 WHERE billing_deadline > ?
                   AND (state = 'PENDING' OR (state = 'LEASED' AND lease_until <= ?))
                 ORDER BY created_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE ssp_billing_delivery AS delivery
               SET state = 'LEASED',
                   lease_generation = delivery.lease_generation + 1,
                   lease_until = ?
              FROM candidate
             WHERE delivery.delivery_id = candidate.delivery_id
            RETURNING delivery.delivery_id, delivery.proof_digest, delivery.provider_id,
                      delivery.provider_request_id, delivery.imp_id, delivery.slot_auction_key,
                      delivery.dsp_id, delivery.cpm_milli_krw, delivery.billing_url,
                      delivery.billing_deadline, delivery.lease_generation, delivery.lease_until
            """;

    private final DataSource dataSource;
    private final Duration leaseDuration;

    public PostgreSqlClaimDeliveryStore(DataSource dataSource, Duration leaseDuration) {
        this.dataSource = Objects.requireNonNull(dataSource);
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
    }

    /**
     * 서버 시작 전에 연결·스키마·쓰기 권한을 확인한다.
     *
     * <p>실제와 같은 행을 삽입한 뒤 트랜잭션을 롤백하므로 청구 근거는 남지 않는다.</p>
     */
    public void verifyReady() {
        Instant now = Instant.now();
        BillingClaim readinessClaim = new BillingClaim(
                "__readiness__",
                UUID.randomUUID().toString(),
                "__readiness__",
                "__readiness__/" + UUID.randomUUID(),
                "0".repeat(64),
                "__readiness__",
                1,
                URI.create("https://readiness.invalid/burl"),
                now.plusSeconds(1)
        );
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!insertClaim(connection, readinessClaim)) {
                    throw new IllegalStateException("SSP billing readiness row conflicted");
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("SSP billing storage is not ready", exception);
        }
    }

    @Override
    public RenderAcceptance recordClaimAndScheduleDelivery(BillingClaim claim) {
        Objects.requireNonNull(claim);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (insertClaim(connection, claim)) {
                    connection.commit();
                    return RenderAcceptance.ACCEPTED;
                }
                RenderAcceptance conflict = classifyConflict(connection, claim);
                connection.commit();
                return conflict;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            return RenderAcceptance.RETRY_LATER;
        }
    }

    private static boolean insertClaim(Connection connection, BillingClaim claim) throws Exception {
        try (var statement = connection.prepareStatement(INSERT_CLAIM)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, claim.proofDigest());
            statement.setString(3, claim.providerId());
            statement.setString(4, claim.providerRequestId());
            statement.setString(5, claim.impId());
            statement.setString(6, claim.slotAuctionKey());
            statement.setString(7, claim.dspId());
            statement.setLong(8, claim.cpmMilliKrw());
            statement.setString(9, claim.billingUrl().toString());
            statement.setTimestamp(10, Timestamp.from(claim.billingDeadline()));
            return statement.executeUpdate() == 1;
        }
    }

    private static RenderAcceptance classifyConflict(Connection connection, BillingClaim claim) throws Exception {
        try (var statement = connection.prepareStatement(SELECT_PROOF_DIGEST_BY_SLOT)) {
            statement.setString(1, claim.slotAuctionKey());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Conflicting SSP billing claim was not found");
                }
                return result.getString("proof_digest").trim().equals(claim.proofDigest())
                        ? RenderAcceptance.DUPLICATE
                        : RenderAcceptance.REJECTED;
            }
        }
    }

    @Override
    public Optional<LeasedBillingDelivery> leaseDueDelivery(Instant now) {
        Objects.requireNonNull(now);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                expireDue(connection, now);
                Optional<LeasedBillingDelivery> result = leaseOne(connection, now);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not lease SSP billing delivery", exception);
        }
    }

    @Override
    public void completeOrReleaseDelivery(DeliveryLease lease, DeliveryOutcome outcome, Instant now) {
        Objects.requireNonNull(lease);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(now);
        String nextState = switch (outcome) {
            case DELIVERED -> "DELIVERED";
            case RETRY -> "PENDING";
            case UNDELIVERED -> "UNDELIVERED";
        };
        String sql = """
                UPDATE ssp_billing_delivery
                   SET state = CASE
                         WHEN ? = 'PENDING' AND billing_deadline <= ? THEN 'UNDELIVERED'
                         ELSE ?
                       END,
                       lease_until = NULL,
                       completed_at = CASE
                         WHEN ? IN ('DELIVERED', 'UNDELIVERED') OR billing_deadline <= ?
                           THEN CAST(? AS TIMESTAMPTZ)
                         ELSE NULL
                       END
                 WHERE delivery_id = ?
                   AND state = 'LEASED'
                   AND lease_generation = ?
                """;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, nextState);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, nextState);
            statement.setString(4, nextState);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setObject(7, UUID.fromString(lease.deliveryId()));
            statement.setLong(8, lease.generation());
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not complete SSP billing delivery", exception);
        }
    }

    private static void expireDue(Connection connection, Instant now) throws Exception {
        try (var statement = connection.prepareStatement(EXPIRE_DUE)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private Optional<LeasedBillingDelivery> leaseOne(Connection connection, Instant now) throws Exception {
        try (var statement = connection.prepareStatement(LEASE_ONE)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now.plus(leaseDuration)));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String deliveryId = result.getObject("delivery_id", UUID.class).toString();
                BillingClaim claim = new BillingClaim(
                        result.getString("provider_id"),
                        result.getString("provider_request_id"),
                        result.getString("imp_id"),
                        result.getString("slot_auction_key"),
                        result.getString("proof_digest").trim(),
                        result.getString("dsp_id"),
                        result.getLong("cpm_milli_krw"),
                        URI.create(result.getString("billing_url")),
                        result.getTimestamp("billing_deadline").toInstant()
                );
                DeliveryLease lease = new DeliveryLease(
                        deliveryId,
                        result.getLong("lease_generation"),
                        result.getTimestamp("lease_until").toInstant()
                );
                return Optional.of(new LeasedBillingDelivery(
                        new BillingDeliveryTask(deliveryId, claim),
                        lease
                ));
            }
        }
    }
}
