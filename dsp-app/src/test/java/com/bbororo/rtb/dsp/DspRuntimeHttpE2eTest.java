package com.bbororo.rtb.dsp;

import static com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.SnapshotInstallResult.INSTALLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bbororo.rtb.dsp.DspRuntimeFactory.Components;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.Campaign;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.CampaignSnapshot;
import com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages.Creative;
import com.bbororo.rtb.dsp.campaignruntime.internal.DefaultCampaignRuntime;
import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import com.bbororo.rtb.dsp.openrtb.DspOpenRtbHttpAdapter;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessed;
import com.bbororo.rtb.dsp.outcome.api.ReservationOutcomeMessages.NoticeProcessingStatus;
import com.bbororo.rtb.dsp.proof.internal.AesGcmReservationNoticeSealer;
import com.bbororo.rtb.dsp.proof.internal.DefaultNoticeUrlFactory;
import com.bbororo.rtb.dsp.proof.internal.DefaultReservationNoticeIssuer;
import com.bbororo.rtb.dsp.proof.internal.ReservationNoticeClaimsFactory;
import com.bbororo.rtb.dsp.proof.spi.NoticeTokenKey;
import com.bbororo.rtb.dsp.proof.spi.NoticeTokenKeySource;
import com.bbororo.rtb.dsp.spending.api.SpendingMessages.InstallLease;
import com.bbororo.rtb.dsp.spending.internal.InMemoryLocalSpendingAuthority;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Stage 7 실제 컴포넌트를 RuntimeFactory로 조립해 HTTP까지 관통한다. */
class DspRuntimeHttpE2eTest {

    private static final Instant NOW = Instant.now();

    @Test
    void returnsAReservedBidThroughTheActualHttpRuntime() throws Exception {
        var clock = Clock.systemUTC();
        var spending = new InMemoryLocalSpendingAuthority(ignored -> { });
        assertEquals(
                com.bbororo.rtb.dsp.spending.api.SpendingMessages.LeaseInstallResult.INSTALLED,
                spending.install(new InstallLease(
                "lease-1", "campaign-1", 10_000, 1,
                NOW.minusSeconds(1), NOW.plusSeconds(60)
        ), System.nanoTime()));

        var campaigns = new DefaultCampaignRuntime(
                (campaignId, evaluatedAt) -> spending.positionOf(campaignId).lagPpm());
        assertEquals(INSTALLED, campaigns.install(new CampaignSnapshot(
                "v1", "runtime-e2e", List.of(new Campaign(
                        "campaign-1", true, 2_000,
                        NOW.minusSeconds(60), NOW.plusSeconds(60),
                        List.of(new Creative("creative-1", 300, 250))
                ))
        )));

        var settings = new DspRuntimeSettings(
                new ArmeriaDspOpenRtbServer.Settings(
                        0, "/openrtb/2.6/bid", 65_536,
                        Duration.ofMillis(180), Duration.ZERO,
                        Duration.ofSeconds(1), 1
                ),
                "ap-northeast-2",
                Duration.ofSeconds(2),
                Duration.ZERO,
                Duration.ZERO,
                Duration.ofSeconds(5),
                100
        );
        var outcomes = new NoticeProcessed(
                NoticeProcessingStatus.ACCEPTED, "reservation-1");
        try (var runtime = DspRuntimeFactory.create(
                settings,
                new Components(
                        campaigns,
                        spending,
                        noticeIssuer(),
                        ignored -> CompletableFuture.completedFuture(outcomes)
                ),
                clock,
                System::nanoTime,
                () -> "bid-1"
        )) {
            runtime.start();

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + runtime.activePort()
                                            + "/openrtb/2.6/bid"))
                            .header("Content-Type", "application/json")
                            .header(DspOpenRtbHttpAdapter.VERSION_HEADER, "2.6")
                            .header(ArmeriaDspOpenRtbServer.AUTHENTICATED_SSP_HEADER, "ssp-1")
                            .POST(HttpRequest.BodyPublishers.ofString(validRequest()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"id\":\"auction-1\""));
            assertTrue(response.body().contains("\"impid\":\"imp-1\""));
            assertTrue(response.body().contains("\"price\":2.000"));
            assertTrue(response.body().contains("\"nurl\":\"https://dsp.example/notices/win"));
            assertTrue(response.body().contains("\"exp\":2"));
        }
    }

    private static DefaultReservationNoticeIssuer noticeIssuer() {
        byte[] keyBytes = new byte[32];
        Arrays.fill(keyBytes, (byte) 7);
        NoticeTokenKeySource keys = () -> new NoticeTokenKey(
                "runtime-key", new SecretKeySpec(keyBytes, "AES"));
        return new DefaultReservationNoticeIssuer(
                new ReservationNoticeClaimsFactory(),
                new AesGcmReservationNoticeSealer(keys),
                new DefaultNoticeUrlFactory(URI.create("https://dsp.example/"))
        );
    }

    private static String validRequest() {
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
}
