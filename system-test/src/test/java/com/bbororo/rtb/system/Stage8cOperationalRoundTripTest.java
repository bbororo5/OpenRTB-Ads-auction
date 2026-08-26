package com.bbororo.rtb.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.bbororo.rtb.dsp.DspOperationalSettings;
import com.bbororo.rtb.dsp.DspOperationalSettings.CampaignSnapshotFile;
import com.bbororo.rtb.dsp.DspOperationalSettings.JdbcStore;
import com.bbororo.rtb.dsp.DspOperationalSettings.LeasePolicy;
import com.bbororo.rtb.dsp.DspOperationalSettings.ProofKeys;
import com.bbororo.rtb.dsp.DspRuntimeFactory;
import com.bbororo.rtb.dsp.DspRuntimeSettings;
import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import com.bbororo.rtb.ssp.SspRuntimeFactory;
import com.bbororo.rtb.ssp.SspRuntimeSettings;
import com.bbororo.rtb.ssp.trust.RegionalDataSourceFactory;
import com.bbororo.rtb.ssp.trust.RegionalDataSourceFactory.DatabaseConnectionSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실제 두 애플리케이션 조립 경계와 PostgreSQL을 관통하는 Stage 8C 왕복이다. */
@Tag("stage-8c-system")
class Stage8cOperationalRoundTripTest {

    private static final String USERNAME = System.getProperty("stage8c.store.username");
    private static final String PASSWORD = System.getProperty("stage8c.store.password");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void providerAuctionThroughSspAndDspPersistsOneBillingOutcome() throws Exception {
        String sspUrl = System.getProperty("stage8c.ssp.jdbc-url");
        String ledgerUrl = System.getProperty("stage8c.ledger.jdbc-url");
        String outcomeUrl = System.getProperty("stage8c.outcome.jdbc-url");
        resetSspStore(sspUrl);
        resetDspStores(ledgerUrl, outcomeUrl);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] snapshot = campaignSnapshot(now).getBytes(StandardCharsets.UTF_8);
        Path snapshotPath = directory.resolve("campaigns.json");
        Files.write(snapshotPath, snapshot);

        try (var gateway = new AuthenticatedGatewayFixture();
             var dsp = DspRuntimeFactory.createOperational(
                     dspSettings(),
                     dspOperations(ledgerUrl, outcomeUrl, snapshotPath, snapshot, gateway.baseUri()))) {
            dsp.start();
            gateway.routeTo(URI.create("http://127.0.0.1:" + dsp.activePort() + "/"));

            var sspDataSource = RegionalDataSourceFactory.create(
                    new DatabaseConnectionSettings(sspUrl, USERNAME, PASSWORD));
            try (var ssp = SspRuntimeFactory.create(
                    sspSettings(gateway.endpoint("/openrtb/2.6/bid")),
                    Clock.systemUTC(),
                    sspDataSource)) {
                ssp.start();
                HttpClient client = HttpClient.newHttpClient();
                URI sspBase = URI.create("http://127.0.0.1:" + ssp.activePort());

                HttpResponse<String> auction = client.send(
                        jsonPost(sspBase.resolve("/publisher/auction"), providerAuction()),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, auction.statusCode(), auction.body());
                JsonNode result = JSON.readTree(auction.body());
                assertEquals("project-dsp", result.path("slots").get(0).path("dspId").asText());
                assertEquals(2.0, result.path("slots").get(0).path("cpmKrw").asDouble());

                String renderProof = result.path("slots").get(0).path("renderProof").asText();
                HttpResponse<Void> render = client.send(
                        jsonPost(
                                sspBase.resolve("/publisher/render"),
                                JSON.createObjectNode().put("renderProof", renderProof).toString()),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(204, render.statusCode());

                awaitBilling(outcomeUrl);
                assertEquals(1L, scalar(outcomeUrl,
                        "SELECT count(*) FROM reservation_monetary_outcome WHERE kind='BILLING'"));
                assertEquals(1L, scalar(sspUrl,
                        "SELECT count(*) FROM ssp_billing_delivery WHERE state='DELIVERED'"));

                HttpResponse<Void> duplicate = client.send(
                        jsonPost(
                                sspBase.resolve("/publisher/render"),
                                JSON.createObjectNode().put("renderProof", renderProof).toString()),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(204, duplicate.statusCode());
                Thread.sleep(50);
                assertEquals(1L, scalar(outcomeUrl,
                        "SELECT count(*) FROM reservation_monetary_outcome WHERE kind='BILLING'"));
            }
        }
    }

    @Test
    void refusesToBindHttpWhenInitialLeaseSupplyIsPartial() throws Exception {
        String ledgerUrl = System.getProperty("stage8c.ledger.jdbc-url");
        String outcomeUrl = System.getProperty("stage8c.outcome.jdbc-url");
        resetDspStores(ledgerUrl, outcomeUrl);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] snapshot = partialCampaignSnapshot(now).getBytes(StandardCharsets.UTF_8);
        Path snapshotPath = directory.resolve("partial-campaigns.json");
        Files.write(snapshotPath, snapshot);

        try (var gateway = new AuthenticatedGatewayFixture();
             var dsp = DspRuntimeFactory.createOperational(
                     dspSettings(),
                     dspOperations(ledgerUrl, outcomeUrl, snapshotPath, snapshot, gateway.baseUri()))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, dsp::start);
            assertTrueMessageContains(failure, "initial lease supply is incomplete");
            assertThrows(IllegalStateException.class, dsp::activePort);
        }
    }

    @Test
    void returnsTechnicalFailureDuringOutcomeDbOutageAndPersistsOneRetry() throws Exception {
        String ledgerUrl = System.getProperty("stage8c.ledger.jdbc-url");
        String outcomeUrl = System.getProperty("stage8c.outcome.jdbc-url");
        resetDspStores(ledgerUrl, outcomeUrl);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] snapshot = campaignSnapshot(now).getBytes(StandardCharsets.UTF_8);
        Path snapshotPath = directory.resolve("fault-campaigns.json");
        Files.write(snapshotPath, snapshot);

        try (var outcomeProxy = JdbcFaultProxy.fromJdbcUrl(outcomeUrl);
             var gateway = new AuthenticatedGatewayFixture();
             var dsp = DspRuntimeFactory.createOperational(
                     dspSettings(),
                     dspOperations(
                             ledgerUrl, outcomeProxy.jdbcUrl(),
                             snapshotPath, snapshot, gateway.baseUri()))) {
            dsp.start();
            gateway.routeTo(URI.create("http://127.0.0.1:" + dsp.activePort() + "/"));
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build();

            HttpResponse<String> bid = client.send(
                    HttpRequest.newBuilder(gateway.endpoint("/openrtb/2.6/bid"))
                            .timeout(Duration.ofSeconds(2))
                            .header("Content-Type", "application/json")
                            .header("x-openrtb-version", "2.6")
                            .POST(HttpRequest.BodyPublishers.ofString(openRtbBidRequest()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, bid.statusCode(), bid.body());
            String billingUrl = JSON.readTree(bid.body())
                    .path("seatbid").get(0).path("bid").get(0).path("burl").asText();

            outcomeProxy.failConnections();
            HttpResponse<Void> unavailable = client.send(
                    HttpRequest.newBuilder(URI.create(billingUrl))
                            .timeout(Duration.ofSeconds(6))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(503, unavailable.statusCode());

            outcomeProxy.restoreConnections();
            HttpResponse<Void> retried = retryBilling(client, URI.create(billingUrl));
            assertEquals(204, retried.statusCode());
            assertEquals(1L, scalar(outcomeUrl,
                    "SELECT count(*) FROM reservation_monetary_outcome WHERE kind='BILLING'"));
        }
    }

    private static DspRuntimeSettings dspSettings() {
        return new DspRuntimeSettings(
                new ArmeriaDspOpenRtbServer.Settings(
                        0, "/openrtb/2.6/bid", 65_536,
                        Duration.ofMillis(180), Duration.ZERO,
                        Duration.ofSeconds(1), 4),
                new ArmeriaDspOpenRtbServer.NoticeSettings(
                        "/notices/win", "/notices/loss", "/notices/billing", 2),
                "ap-northeast-2", Duration.ofSeconds(5),
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(5), 1_000);
    }

    private static DspOperationalSettings dspOperations(
            String ledgerUrl,
            String outcomeUrl,
            Path snapshotPath,
            byte[] snapshot,
            URI gatewayBaseUri
    ) throws Exception {
        return new DspOperationalSettings(
                "dsp-system-test-1",
                gatewayBaseUri,
                new CampaignSnapshotFile(snapshotPath, "v1", sha256(snapshot)),
                new ProofKeys("key-1", Map.of("key-1", new byte[32])),
                new JdbcStore(ledgerUrl, USERNAME, PASSWORD, 4),
                new JdbcStore(outcomeUrl, USERNAME, PASSWORD, 4),
                4,
                new LeasePolicy(
                        Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofSeconds(5), Duration.ofMillis(100),
                        10_000, 100_000, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 10, Duration.ofSeconds(2)),
                Duration.ofMillis(10));
    }

    private static SspRuntimeSettings sspSettings(URI dspEndpoint) {
        return new SspRuntimeSettings(
                0, "ap-northeast-2",
                URI.create("http://ssp.invalid/publisher/render"),
                32, 65_536, 8_192,
                Map.of("project-dsp", dspEndpoint),
                Duration.ofMillis(100), 32, 65_536, 1_000,
                (byte) 1, Map.of((byte) 1, new byte[32]),
                Duration.ofMillis(10), 4, Duration.ofMillis(500));
    }

    private static HttpRequest jsonPost(URI uri, String body) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String providerAuction() {
        return """
                {"providerId":"provider-1","providerKeyId":"key-1",
                 "providerRequestId":"request-stage-8c","tmaxMillis":150,
                 "slots":[{"impId":"imp-1","width":300,"height":250,
                            "floorCpmKrw":1.000}]}
                """;
    }

    private static String openRtbBidRequest() {
        return """
                {"id":"auction-fault","at":1,"tmax":180,"cur":["KRW"],
                 "imp":[{"id":"imp-1","banner":{"format":[{"w":300,"h":250}]},
                          "bidfloor":1.000,"bidfloorcur":"KRW","exp":2}]}
                """;
    }

    private static String campaignSnapshot(Instant now) {
        return """
                {"version":"v1","campaigns":[{
                  "id":"campaign-1","active":true,"bidCpmMilliKrw":2000,
                  "startsAt":"%s","endsAt":"%s",
                  "creatives":[{"id":"creative-1","width":300,"height":250}]
                }]}
                """.formatted(now.minusSeconds(60), now.plusSeconds(3_600));
    }

    private static String partialCampaignSnapshot(Instant now) {
        return """
                {"version":"v1","campaigns":[
                  {"id":"campaign-1","active":true,"bidCpmMilliKrw":2000,
                   "startsAt":"%s","endsAt":"%s",
                   "creatives":[{"id":"creative-1","width":300,"height":250}]},
                  {"id":"campaign-2","active":true,"bidCpmMilliKrw":2500,
                   "startsAt":"%s","endsAt":"%s",
                   "creatives":[{"id":"creative-2","width":300,"height":250}]}
                ]}
                """.formatted(
                        now.minusSeconds(60), now.plusSeconds(3_600),
                        now.minusSeconds(60), now.plusSeconds(3_600));
    }

    private static void resetSspStore(String url) throws Exception {
        execute(url, "TRUNCATE ssp_billing_delivery, provider_config_head, "
                + "provider_key, provider_policy, provider_config_version CASCADE");
        execute(url, "INSERT INTO provider_config_version(version, checksum, published_at) "
                + "VALUES (1, 'stage-8c', transaction_timestamp())");
        execute(url, "INSERT INTO provider_policy(version, provider_id, active) "
                + "VALUES (1, 'provider-1', true)");
        execute(url, "INSERT INTO provider_key(version, provider_id, key_id, active) "
                + "VALUES (1, 'provider-1', 'key-1', true)");
        execute(url, "INSERT INTO provider_config_head(scope, active_version) VALUES ('global', 1)");
    }

    private static void resetDspStores(String ledgerUrl, String outcomeUrl) throws Exception {
        execute(ledgerUrl, "TRUNCATE budget_lease, regional_campaign_budget CASCADE");
        execute(outcomeUrl, "TRUNCATE monetary_event_conflict, "
                + "reservation_monetary_outcome, monetary_event CASCADE");
        execute(ledgerUrl, """
                INSERT INTO regional_campaign_budget (
                    campaign_id, responsibility_micros, available_micros,
                    campaign_starts_at, campaign_ends_at
                ) VALUES ('campaign-1', 1000000, 1000000,
                          transaction_timestamp() - interval '1 minute',
                          transaction_timestamp() + interval '1 hour')
                """);
    }

    private static void awaitBilling(String outcomeUrl) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (scalar(outcomeUrl,
                    "SELECT count(*) FROM reservation_monetary_outcome WHERE kind='BILLING'") == 1) {
                return;
            }
            Thread.sleep(10);
        }
        fail("billing outcome was not persisted within 3 seconds");
    }

    private static HttpResponse<Void> retryBilling(HttpClient client, URI billingUrl)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        HttpResponse<Void> response = null;
        while (System.nanoTime() < deadline) {
            response = client.send(
                    HttpRequest.newBuilder(billingUrl)
                            .timeout(Duration.ofSeconds(6))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 204) {
                return response;
            }
            Thread.sleep(100);
        }
        return response;
    }

    private static void execute(String url, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, USERNAME, PASSWORD);
             var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long scalar(String url, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, USERNAME, PASSWORD);
             var statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void assertTrueMessageContains(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return;
            }
            current = current.getCause();
        }
        fail("expected failure message containing: " + expected, failure);
    }
}
