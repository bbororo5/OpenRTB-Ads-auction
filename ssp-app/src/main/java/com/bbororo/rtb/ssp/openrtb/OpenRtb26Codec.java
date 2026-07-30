package com.bbororo.rtb.ssp.openrtb;

import com.bbororo.rtb.ssp.contract.KrwCpm;
import com.bbororo.rtb.ssp.contract.SspMessages.BidRequestBatch;
import com.bbororo.rtb.ssp.contract.SspMessages.DspBid;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcome;
import com.bbororo.rtb.ssp.contract.SspMessages.DspCallOutcomeKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** SSP가 소유하는 OpenRTB 2.6 하위 규격 JSON 변환기다. */
public final class OpenRtb26Codec {

    private static final String CURRENCY = "KRW";
    private static final int RENDER_EXPIRY_SECONDS = 2;

    private final ObjectMapper mapper;

    public OpenRtb26Codec() {
        this(new ObjectMapper());
    }

    OpenRtb26Codec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public byte[] encodeBidRequest(BidRequestBatch batch) {
        Objects.requireNonNull(batch);
        var request = new BidRequestJson(
                batch.auctionId(),
                batch.auction().tmaxMillis(),
                List.of(CURRENCY),
                batch.auction().slots().stream()
                        .map(slot -> new ImpJson(
                                slot.impId(),
                                KrwCpm.fromMilliKrw(slot.floorCpmMilliKrw()),
                                CURRENCY,
                                RENDER_EXPIRY_SECONDS
                        ))
                        .toList()
        );
        try {
            return mapper.writeValueAsBytes(request);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode OpenRTB bid request", exception);
        }
    }

    public DspCallOutcome decodeBidResponse(String dspId, BidRequestBatch batch, byte[] body) {
        Objects.requireNonNull(dspId);
        Objects.requireNonNull(batch);
        Objects.requireNonNull(body);
        try {
            BidResponseJson response = mapper.readValue(body, BidResponseJson.class);
            if (!batch.auctionId().equals(response.id())) {
                return invalid(dspId);
            }
            Set<String> requestedImpressions = batch.auction().slots().stream()
                    .map(slot -> slot.impId())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<DspBid> bids = response.seatbid() == null ? List.of() : response.seatbid().stream()
                    .map(OpenRtb26Codec::requireSeat)
                    .flatMap(seat -> seat.bid() == null ? java.util.stream.Stream.empty() : seat.bid().stream())
                    .map(bid -> toDspBid(dspId, bid, requestedImpressions))
                    .toList();
            return bids.isEmpty()
                    ? new DspCallOutcome(dspId, DspCallOutcomeKind.NO_BID, List.of())
                    : new DspCallOutcome(dspId, DspCallOutcomeKind.VALID_BID, bids);
        } catch (Exception exception) {
            return invalid(dspId);
        }
    }

    private static SeatBidJson requireSeat(SeatBidJson seat) {
        if (seat == null) {
            throw new IllegalArgumentException("OpenRTB seatbid must not contain null");
        }
        return seat;
    }

    private static DspBid toDspBid(String dspId, BidJson bid, Set<String> requestedImpressions) {
        if (bid == null || bid.id() == null || bid.impid() == null
                || !requestedImpressions.contains(bid.impid())
                || bid.price() == null || bid.price().signum() <= 0
                || bid.nurl() == null || bid.lurl() == null || bid.burl() == null
                || bid.expirySeconds() == null || bid.expirySeconds() != RENDER_EXPIRY_SECONDS) {
            throw new IllegalArgumentException("Invalid OpenRTB bid");
        }
        return new DspBid(
                dspId,
                bid.impid(),
                bid.id(),
                KrwCpm.toMilliKrw(bid.price()),
                URI.create(bid.nurl()),
                URI.create(bid.lurl()),
                URI.create(bid.burl())
        );
    }

    private static DspCallOutcome invalid(String dspId) {
        return new DspCallOutcome(dspId, DspCallOutcomeKind.INVALID_BID, List.of());
    }

    private record BidRequestJson(
            String id,
            int tmax,
            List<String> cur,
            List<ImpJson> imp
    ) {
    }

    private record ImpJson(
            String id,
            BigDecimal bidfloor,
            String bidfloorcur,
            int exp
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BidResponseJson(String id, List<SeatBidJson> seatbid) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeatBidJson(List<BidJson> bid) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BidJson(
            String id,
            String impid,
            BigDecimal price,
            String nurl,
            String lurl,
            String burl,
            @JsonProperty("exp") Integer expirySeconds
    ) {
    }
}
