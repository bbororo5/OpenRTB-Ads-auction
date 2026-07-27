package com.bbororo.rtb.ssp.renderproof;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bbororo.rtb.ssp.contract.SspMessages.AuctionOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionWinners;
import com.bbororo.rtb.ssp.contract.SspMessages.WinningBid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuctionResultAssemblerTest {

    @Test
    void issuesOneProofForEachWinningSlot() {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        AuctionResultAssembler assembler = new AuctionResultAssembler(
                new HmacRenderProofService("test-key".getBytes(StandardCharsets.UTF_8)),
                Clock.fixed(now, ZoneOffset.UTC)
        );
        AuctionRequest request = new AuctionRequest(
                "provider-1", "key-1", "request-1", 50,
                List.of(new AuctionSlot("imp-1", 0), new AuctionSlot("imp-2", 0))
        );
        AuctionOutcome outcome = new AuctionOutcome("auction-1", new AuctionWinners(List.of(
                winner("imp-1"), winner("imp-2")
        )));

        var result = assembler.assemble(request, outcome);

        assertEquals("auction-1", result.auctionId());
        assertEquals(List.of("imp-1", "imp-2"), result.slots().stream()
                .map(slot -> slot.winningBid().impId()).toList());
        assertEquals(2, result.slots().stream().map(slot -> slot.renderProof().encodedValue()).distinct().count());
    }

    private static WinningBid winner(String impId) {
        URI url = URI.create("https://dsp.example.test/burl");
        return new WinningBid("auction-1/" + impId, impId, "project-dsp", "bid-" + impId,
                2_000, url, url, url);
    }
}
