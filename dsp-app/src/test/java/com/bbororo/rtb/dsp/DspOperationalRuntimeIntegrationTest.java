package com.bbororo.rtb.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.DspOperationalSettings.CampaignSnapshotFile;
import com.bbororo.rtb.dsp.DspOperationalSettings.JdbcStore;
import com.bbororo.rtb.dsp.DspOperationalSettings.LeasePolicy;
import com.bbororo.rtb.dsp.DspOperationalSettings.ProofKeys;
import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import com.bbororo.rtb.dsp.openrtb.DspOpenRtbHttpAdapter;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실제 PostgreSQL과 HTTP를 통해 8B 운영 조립의 입찰·과금 흐름을 검증한다. */
@Tag("dsp-operational-runtime")
class DspOperationalRuntimeIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void startsWithAnInitialLeaseAndPersistsBillingThroughTheNoticeRoute() throws Exception {
        String ledgerUrl = System.getProperty("dsp.ledger.jdbc-url");
        String outcomeUrl = System.getProperty("dsp.money.jdbc-url");
        String username = System.getProperty("dsp.store.username");
        String password = System.getProperty("dsp.store.password");
        resetStores(ledgerUrl, outcomeUrl, username, password);

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] snapshot = snapshot(now).getBytes(StandardCharsets.UTF_8);
        Path snapshotPath = directory.resolve("campaigns.json");
        Files.write(snapshotPath, snapshot);
        var runtimeSettings = new DspRuntimeSettings(
                new ArmeriaDspOpenRtbServer.Settings(
                        0, "/openrtb/2.6/bid", 65_536,
                        Duration.ofMillis(180), Duration.ZERO,
                        Duration.ofSeconds(1), 2
                ),
                new ArmeriaDspOpenRtbServer.NoticeSettings(
                        "/notices/win", "/notices/loss", "/notices/billing", 2),
                "ap-northeast-2",
                Duration.ofSeconds(5),
                Duration.ZERO,
                Duration.ZERO,
                Duration.ofSeconds(5),
                100
        );
        var operations = new DspOperationalSettings(
                "dsp-integration-1",
                URI.create("http://dsp.invalid/"),
                new CampaignSnapshotFile(snapshotPath, "v1", sha256(snapshot)),
                new ProofKeys("key-1", Map.of("key-1", new byte[32])),
                new JdbcStore(ledgerUrl, username, password, 4),
                new JdbcStore(outcomeUrl, username, password, 4),
                4,
                new LeasePolicy(
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(100),
                        10_000,
                        100_000,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        10,
                        Duration.ofSeconds(2)
                ),
                Duration.ofMillis(10)
        );

        try (var runtime = DspRuntimeFactory.createOperational(runtimeSettings, operations)) {
            runtime.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> bid = client.send(
                    HttpRequest.newBuilder(endpoint(runtime, "/openrtb/2.6/bid"))
                            .header("Content-Type", "application/json")
                            .header(DspOpenRtbHttpAdapter.VERSION_HEADER, "2.6")
                            .header(ArmeriaDspOpenRtbServer.AUTHENTICATED_SSP_HEADER, "ssp-1")
                            .POST(HttpRequest.BodyPublishers.ofString(bidRequest()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, bid.statusCode());

            String billingUrl = new ObjectMapper().readTree(bid.body())
                    .path("seatbid").get(0).path("bid").get(0).path("burl").asText();
            String query = URI.create(billingUrl).getRawQuery();
            HttpResponse<Void> billing = client.send(
                    HttpRequest.newBuilder(endpoint(runtime, "/notices/billing?" + query))
                            .header(ArmeriaDspOpenRtbServer.AUTHENTICATED_SSP_HEADER, "ssp-1")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding()
            );

            assertEquals(204, billing.statusCode());
            assertEquals(1L, scalar(
                    outcomeUrl, username, password,
                    "SELECT count(*) FROM reservation_monetary_outcome WHERE kind='BILLING'"));
        }
    }

    private static void resetStores(
            String ledgerUrl,
            String outcomeUrl,
            String username,
            String password
    ) throws Exception {
        execute(ledgerUrl, username, password,
                "TRUNCATE budget_lease, regional_campaign_budget CASCADE");
        execute(outcomeUrl, username, password,
                "TRUNCATE monetary_event_conflict, reservation_monetary_outcome, monetary_event CASCADE");
        execute(ledgerUrl, username, password, """
                INSERT INTO regional_campaign_budget (
                    campaign_id, responsibility_micros, available_micros,
                    campaign_starts_at, campaign_ends_at
                ) VALUES ('campaign-1', 1000000, 1000000,
                          transaction_timestamp() - interval '1 minute',
                          transaction_timestamp() + interval '1 hour')
                """);
    }

    private static void execute(
            String url,
            String username,
            String password,
            String sql
    ) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long scalar(
            String url,
            String username,
            String password,
            String sql
    ) throws Exception {
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.prepareStatement(sql);
             var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static URI endpoint(DspRuntime runtime, String path) {
        return URI.create("http://127.0.0.1:" + runtime.activePort() + path);
    }

    private static String snapshot(Instant now) {
        return """
                {
                  "version":"v1",
                  "campaigns":[{
                    "id":"campaign-1",
                    "active":true,
                    "bidCpmMilliKrw":2000,
                    "startsAt":"%s",
                    "endsAt":"%s",
                    "creatives":[{"id":"creative-1","width":300,"height":250}]
                  }]
                }
                """.formatted(now.minusSeconds(60), now.plusSeconds(3_600));
    }

    private static String bidRequest() {
        return """
                {
                  "id":"auction-1","at":1,"tmax":180,"cur":["KRW"],
                  "imp":[{
                    "id":"imp-1",
                    "banner":{"format":[{"w":300,"h":250}]},
                    "bidfloor":1.000,"bidfloorcur":"KRW","exp":2
                  }]
                }
                """;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
