package com.bbororo.rtb.ssp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bbororo.rtb.ssp.contract.NoticeUrlTemplate.Context;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NoticeUrlTemplateTest {

    @Test
    void rendersAvailableOpenRtbMacrosAndEmptiesUnavailableOnes() {
        var template = new NoticeUrlTemplate(
                "https://dsp.test/notice?auction=${AUCTION_ID}"
                        + "&imp=${AUCTION_IMP_ID}&price=${AUCTION_PRICE}"
                        + "&currency=${AUCTION_CURRENCY}&loss=${AUCTION_LOSS}"
                        + "&ts=${AUCTION_IMP_TS}&seat=${AUCTION_SEAT_ID}"
        );

        URI rendered = template.render(new Context(
                "auction/1", "imp-1", 2_000L, 102,
                Instant.ofEpochMilli(1_723_000_000_123L)
        ));

        assertEquals(
                "https://dsp.test/notice?auction=auction%2F1&imp=imp-1"
                        + "&price=2&currency=KRW&loss=102&ts=1723000000123&seat=",
                rendered.toString()
        );
    }

    @Test
    void rejectsANonHttpTemplate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NoticeUrlTemplate("file:///tmp/${AUCTION_ID}")
        );
    }
}
