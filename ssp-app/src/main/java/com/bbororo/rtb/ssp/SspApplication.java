package com.bbororo.rtb.ssp;

import com.bbororo.rtb.ssp.admission.AuctionAdmissionService;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer;
import com.bbororo.rtb.ssp.api.DefaultAuctionRenderApi;
import com.bbororo.rtb.ssp.api.ProviderApiJsonCodec;
import com.bbororo.rtb.ssp.api.ProviderHttpServer;
import com.bbororo.rtb.ssp.auction.CoordinatingAuctionStarter;
import com.bbororo.rtb.ssp.auction.DeadlineBoundAuctionCoordinator;
import com.bbororo.rtb.ssp.claim.PostgreSqlClaimDeliveryStore;
import com.bbororo.rtb.ssp.claim.StoreBackedRenderClaimService;
import com.bbororo.rtb.ssp.deduplication.InMemoryAuctionDeduplicator;
import com.bbororo.rtb.ssp.dspbid.HttpOpenRtbDspBidExecutor;
import com.bbororo.rtb.ssp.notification.AsyncAuctionNoticeDelivery;
import com.bbororo.rtb.ssp.notification.BillingDeliveryWorker;
import com.bbororo.rtb.ssp.notification.HttpDspNoticeClient;
import com.bbororo.rtb.ssp.notification.StoreBackedDspNotificationDelivery;
import com.bbororo.rtb.ssp.openrtb.OpenRtb26Codec;
import com.bbororo.rtb.ssp.renderproof.AeadRenderProofService;
import com.bbororo.rtb.ssp.renderproof.AuctionResultAssembler;
import com.bbororo.rtb.ssp.trust.ProviderTrustControlPlane;
import com.bbororo.rtb.ssp.trust.RegionalDataSourceFactory;
import com.bbororo.rtb.ssp.winner.FirstPriceWinnerSelector;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;

/** 실제 SSP 어댑터와 0~4단계 컴포넌트를 조립하는 프로세스 진입점이다. */
public final class SspApplication {

    private SspApplication() {
    }

    public static void main(String[] args) throws InterruptedException {
        SspRuntimeSettings settings = SspRuntimeSettings.fromEnvironment(System.getenv());
        Clock clock = Clock.systemUTC();
        HikariDataSource dataSource = RegionalDataSourceFactory.createFromEnvironment();
        ProviderTrustControlPlane trustControl = ProviderTrustControlPlane.start(dataSource);
        ExecutorService auctionExecutor = Executors.newVirtualThreadPerTaskExecutor();

        var store = new PostgreSqlClaimDeliveryStore(dataSource, Duration.ofSeconds(1));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        var noticeDelegate = new StoreBackedDspNotificationDelivery(
                store,
                new HttpDspNoticeClient(httpClient, settings.noticeTimeout())
        );
        var notificationDelivery = new AsyncAuctionNoticeDelivery(noticeDelegate);
        var proofService = new AeadRenderProofService(
                settings.renderProofKeyId(),
                Map.of(
                        settings.renderProofKeyId(),
                        new SecretKeySpec(settings.renderProofKey(), "AES")
                )
        );
        var bidExecutor = new HttpOpenRtbDspBidExecutor(
                httpClient,
                new OpenRtb26Codec(),
                settings.dspEndpoints()
        );
        var coordinator = new DeadlineBoundAuctionCoordinator(
                bidExecutor,
                new FirstPriceWinnerSelector(),
                new ArrayList<>(settings.dspEndpoints().keySet())
        );
        var admission = new AuctionAdmissionService(
                new ProviderRequestAuthorizer(trustControl.trustSnapshot()),
                new InMemoryAuctionDeduplicator(),
                new CoordinatingAuctionStarter(coordinator, auctionExecutor)
        );
        var api = new DefaultAuctionRenderApi(
                admission,
                new AuctionResultAssembler(proofService, clock),
                proofService,
                new StoreBackedRenderClaimService(store, trustControl.trustSnapshot()),
                notificationDelivery,
                System::nanoTime
        );
        var worker = new BillingDeliveryWorker(
                notificationDelivery,
                clock,
                settings.billingWorkerInterval()
        );
        var server = new ProviderHttpServer(
                new InetSocketAddress("0.0.0.0", settings.serverPort()),
                api,
                new ProviderApiJsonCodec(),
                clock
        );
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            worker.close();
            notificationDelivery.close();
            auctionExecutor.close();
            trustControl.close();
            shutdown.countDown();
        }, "ssp-shutdown"));

        worker.start();
        server.start();
        shutdown.await();
    }
}
