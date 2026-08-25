package com.bbororo.rtb.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.dsp.openrtb.ArmeriaDspOpenRtbServer;
import com.bbororo.rtb.dsp.openrtb.DspOpenRtbApi;
import com.bbororo.rtb.dsp.openrtb.DspOpenRtbHttpAdapter;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuctionNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.AuthenticatedBidRequest;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidHttpResult;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoContent;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeHttpResult;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class DspRuntimeTest {

    @Test
    void startsBackgroundServicesBeforeHttpAndClosesEverythingInReverseOrder() {
        var events = new ArrayList<String>();
        var first = service("first", events);
        var second = service("second", events);
        AutoCloseable firstResource = () -> events.add("close-resource-1");
        AutoCloseable secondResource = () -> events.add("close-resource-2");
        try (var runtime = new DspRuntime(
                server(),
                List.of(first, second),
                List.of(firstResource, secondResource)
        )) {
            runtime.start();
            events.add("http-running-" + (runtime.activePort() > 0));
        }

        assertEquals(List.of(
                "start-first",
                "start-second",
                "http-running-true",
                "close-second",
                "close-first",
                "close-resource-2",
                "close-resource-1"
        ), events);
    }

    private static DspRuntime.Service service(String name, List<String> events) {
        return new DspRuntime.Service(
                () -> events.add("start-" + name),
                () -> events.add("close-" + name)
        );
    }

    private static ArmeriaDspOpenRtbServer server() {
        var settings = new ArmeriaDspOpenRtbServer.Settings(
                0,
                "/openrtb/2.6/bid",
                65_536,
                Duration.ofMillis(180),
                Duration.ZERO,
                Duration.ofSeconds(1),
                1
        );
        return new ArmeriaDspOpenRtbServer(
                settings,
                new DspOpenRtbHttpAdapter(new DspOpenRtbApi() {
                    @Override
                    public BidHttpResult handleBid(AuthenticatedBidRequest request) {
                        return NoContent.INSTANCE;
                    }

                    @Override
                    public CompletionStage<NoticeHttpResult> handleNotice(AuctionNotice notice) {
                        return CompletableFuture.completedFuture(NoticeHttpResult.ACCEPTED);
                    }
                }),
                Clock.systemUTC()
        );
    }
}
