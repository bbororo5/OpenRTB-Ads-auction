package com.bbororo.rtb.ssp;

import com.bbororo.rtb.ssp.admission.AuctionAdmissionService;
import com.bbororo.rtb.ssp.admission.ProviderRequestAuthorizer;
import com.bbororo.rtb.ssp.api.DefaultAuctionRenderApi;
import com.bbororo.rtb.ssp.api.ProviderApiJsonCodec;
import com.bbororo.rtb.ssp.api.ProviderHttpServer;
import com.bbororo.rtb.ssp.api.ProviderHttpLimits;
import com.bbororo.rtb.ssp.auction.CoordinatingAuctionStarter;
import com.bbororo.rtb.ssp.auction.DeadlineBoundAuctionCoordinator;
import com.bbororo.rtb.ssp.claim.PostgreSqlClaimDeliveryStore;
import com.bbororo.rtb.ssp.claim.StoreBackedRenderClaimService;
import com.bbororo.rtb.ssp.deduplication.InMemoryAuctionDeduplicator;
import com.bbororo.rtb.ssp.dspbid.DspBidChannel;
import com.bbororo.rtb.ssp.dspbid.DspBidExecutor;
import com.bbororo.rtb.ssp.dspbid.HttpOpenRtbDspBidExecutor;
import com.bbororo.rtb.ssp.notification.AsyncAuctionNoticeDelivery;
import com.bbororo.rtb.ssp.notification.BillingDeliveryWorker;
import com.bbororo.rtb.ssp.notification.DspNotificationDelivery;
import com.bbororo.rtb.ssp.notification.HttpDspNoticeClient;
import com.bbororo.rtb.ssp.notification.StoreBackedDspNotificationDelivery;
import com.bbororo.rtb.ssp.openrtb.OpenRtb26Codec;
import com.bbororo.rtb.ssp.renderproof.AeadRenderProofService;
import com.bbororo.rtb.ssp.renderproof.AuctionResultAssembler;
import com.bbororo.rtb.ssp.renderproof.RenderProofService;
import com.bbororo.rtb.ssp.trust.ProviderTrustControlPlane;
import com.bbororo.rtb.ssp.trust.RegionalDataSourceFactory;
import com.bbororo.rtb.ssp.winner.FirstPriceWinnerSelector;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.crypto.spec.SecretKeySpec;

/** 환경 설정과 실제 어댑터로 SSP 객체 그래프를 조립한다. */
public final class SspRuntimeFactory {

    private SspRuntimeFactory() {
    }

    public static SspRuntime createFromEnvironment() {
        return create(
                SspRuntimeSettings.fromEnvironment(System.getenv()),
                Clock.systemUTC(),
                RegionalDataSourceFactory.createFromEnvironment()
        );
    }

    static SspRuntime create(SspRuntimeSettings settings, Clock clock) {
        return create(
                settings,
                clock,
                RegionalDataSourceFactory.createFromEnvironment()
        );
    }

    /** 명시적 운영 설정과 지역 DB 연결 풀로 SSP 객체 그래프를 조립한다. */
    public static SspRuntime create(
            SspRuntimeSettings settings,
            Clock clock,
            HikariDataSource dataSource
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(dataSource, "dataSource");
        ProviderTrustControlPlane trustControl = ProviderTrustControlPlane.start(dataSource);
        ExecutorService auctionExecutor = null;
        HttpClient noticeClient = null;
        List<HttpClient> bidClients = new ArrayList<>();
        AsyncAuctionNoticeDelivery notificationDelivery = null;
        BillingDeliveryWorker billingWorker = null;
        ProviderHttpServer server = null;

        try {
            auctionExecutor = Executors.newVirtualThreadPerTaskExecutor();
            var store = new PostgreSqlClaimDeliveryStore(
                    dataSource,
                    billingLeaseDuration(settings)
            );
            store.verifyReady();

            noticeClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500))
                    .build();
            notificationDelivery = createNotificationDelivery(
                    store,
                    noticeClient,
                    settings,
                    clock
            );
            RenderProofService proofService = createProofService(settings);
            var resultAssembler = new AuctionResultAssembler(
                    proofService,
                    clock,
                    settings.renderCompletionUrl()
            );
            DspBidExecutor bidExecutor = createBidExecutor(
                    settings,
                    bidClients
            );
            var api = new DefaultAuctionRenderApi(
                    createAdmission(
                            settings,
                            trustControl,
                            auctionExecutor,
                            bidExecutor,
                            resultAssembler,
                            notificationDelivery
                    ),
                    proofService,
                    new StoreBackedRenderClaimService(
                            store,
                            trustControl.trustSnapshot()
                    ),
                    System::nanoTime
            );
            billingWorker = new BillingDeliveryWorker(
                    notificationDelivery,
                    clock,
                    settings.billingWorkerConcurrency()
            );
            server = new ProviderHttpServer(
                    new InetSocketAddress("0.0.0.0", settings.serverPort()),
                    api,
                    new ProviderApiJsonCodec(),
                    clock,
                    new ProviderHttpLimits(
                            settings.providerMaxInFlight(),
                            settings.providerMaxAuctionRequestBytes(),
                            settings.providerMaxRenderRequestBytes()
                    )
            );
            return new SspRuntime(
                    server,
                    billingWorker,
                    notificationDelivery,
                    bidClients,
                    noticeClient,
                    auctionExecutor,
                    trustControl
            );
        } catch (RuntimeException | Error failure) {
            closeAfterFailedAssembly(
                    failure,
                    server,
                    billingWorker,
                    notificationDelivery,
                    bidClients,
                    noticeClient,
                    auctionExecutor,
                    trustControl
            );
            throw failure;
        }
    }

    private static Duration billingLeaseDuration(SspRuntimeSettings settings) {
        Duration attemptWithMargin = settings.noticeTimeout().plusMillis(250);
        return attemptWithMargin.compareTo(Duration.ofSeconds(1)) < 0
                ? Duration.ofSeconds(1)
                : attemptWithMargin;
    }

    private static AsyncAuctionNoticeDelivery createNotificationDelivery(
            PostgreSqlClaimDeliveryStore store,
            HttpClient noticeClient,
            SspRuntimeSettings settings,
            Clock clock
    ) {
        return new AsyncAuctionNoticeDelivery(
                new StoreBackedDspNotificationDelivery(
                        store,
                        new HttpDspNoticeClient(noticeClient),
                        clock,
                        settings.noticeTimeout()
                )
        );
    }

    private static RenderProofService createProofService(SspRuntimeSettings settings) {
        return new AeadRenderProofService(
                settings.regionId(),
                settings.renderProofKeyId(),
                settings.renderProofKeys().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> new SecretKeySpec(entry.getValue(), "AES")
                        ))
        );
    }

    private static DspBidExecutor createBidExecutor(
            SspRuntimeSettings settings,
            List<HttpClient> ownedClients
    ) {
        Map<String, DspBidChannel> channels = new LinkedHashMap<>();
        settings.dspEndpoints().forEach((dspId, endpoint) -> {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(settings.dspBidTimeout())
                    .build();
            ownedClients.add(client);
            channels.put(
                    dspId,
                    new DspBidChannel(
                            endpoint,
                            client,
                            settings.dspMaxInFlight()
                    )
            );
        });
        return new HttpOpenRtbDspBidExecutor(
                new OpenRtb26Codec(),
                channels,
                settings.dspBidTimeout(),
                settings.dspMaxResponseBytes()
        );
    }

    private static AuctionAdmissionService createAdmission(
            SspRuntimeSettings settings,
            ProviderTrustControlPlane trustControl,
            ExecutorService auctionExecutor,
            DspBidExecutor bidExecutor,
            AuctionResultAssembler resultAssembler,
            DspNotificationDelivery notificationDelivery
    ) {
        var coordinator = new DeadlineBoundAuctionCoordinator(
                bidExecutor,
                new FirstPriceWinnerSelector(),
                new ArrayList<>(settings.dspEndpoints().keySet())
        );
        return new AuctionAdmissionService(
                new ProviderRequestAuthorizer(trustControl.trustSnapshot()),
                new InMemoryAuctionDeduplicator(settings.auctionDedupMaximumEntries()),
                new CoordinatingAuctionStarter(
                        coordinator,
                        auctionExecutor,
                        resultAssembler,
                        notificationDelivery
                )
        );
    }

    private static void closeAfterFailedAssembly(
            Throwable failure,
            ProviderHttpServer server,
            BillingDeliveryWorker worker,
            AsyncAuctionNoticeDelivery notificationDelivery,
            List<HttpClient> bidClients,
            HttpClient noticeClient,
            ExecutorService auctionExecutor,
            ProviderTrustControlPlane trustControl
    ) {
        closeAndSuppress(server, failure);
        closeAndSuppress(worker, failure);
        closeAndSuppress(auctionExecutor, failure);
        closeAndSuppress(notificationDelivery, failure);
        bidClients.forEach(client -> closeAndSuppress(client, failure));
        closeAndSuppress(noticeClient, failure);
        closeAndSuppress(trustControl, failure);
    }

    private static void closeAndSuppress(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
