package com.bbororo.rtb.ssp.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.contract.SspMessages.BillingClaim;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderAcceptance;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgreSqlClaimDeliveryStoreFailureTest {

    @Test
    void returnsRetryLaterWhenTheClaimCannotReachPostgreSql() {
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new SQLException("database unavailable");
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
        var store = new PostgreSqlClaimDeliveryStore(unavailable, Duration.ofSeconds(1));

        RenderAcceptance result = store.recordClaimAndScheduleDelivery(claim());

        assertEquals(RenderAcceptance.RETRY_LATER, result);
    }

    private static BillingClaim claim() {
        return new BillingClaim(
                "provider-1",
                "request-1",
                "imp-1",
                "auction-1/imp-1",
                "a".repeat(64),
                "project-dsp",
                2_000,
                URI.create("https://project-dsp.test/burl/1"),
                Instant.parse("2026-07-30T00:00:05Z")
        );
    }
}
