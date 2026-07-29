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
                slot_auction_key, dsp_id, cpm_krw, billing_url, billing_deadline, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (proof_digest) DO NOTHING
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
                      delivery.dsp_id, delivery.cpm_krw, delivery.billing_url,
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

    @Override
    public RenderAcceptance recordClaimAndScheduleDelivery(BillingClaim claim) {
        Objects.requireNonNull(claim);
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(INSERT_CLAIM)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, claim.proofDigest());
            statement.setString(3, claim.providerId());
            statement.setString(4, claim.providerRequestId());
            statement.setString(5, claim.impId());
            statement.setString(6, claim.slotAuctionKey());
            statement.setString(7, claim.dspId());
            statement.setLong(8, claim.cpmKrw());
            statement.setString(9, claim.billingUrl().toString());
            statement.setTimestamp(10, Timestamp.from(claim.billingDeadline()));
            return statement.executeUpdate() == 1
                    ? RenderAcceptance.ACCEPTED
                    : RenderAcceptance.DUPLICATE;
        } catch (Exception exception) {
            return RenderAcceptance.RETRY_LATER;
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
                         WHEN ? IN ('DELIVERED', 'UNDELIVERED') OR billing_deadline <= ? THEN ?
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
                        result.getLong("cpm_krw"),
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
