package com.bbororo.rtb.dsp.openrtb;

import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Bid;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.BidResponse;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.Impression;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.SeatBid;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** DSP 공개 경계에서 OpenRTB 2.6 KRW·1가격·웹 배너 프로필을 변환한다. */
final class DspOpenRtb26JsonCodec {

    private static final int FIRST_PRICE_AUCTION = 1;
    private static final String CURRENCY = OpenRtbMessages.BID_CURRENCY;
    private static final int MONEY_SCALE = 3;

    private final ObjectMapper mapper;

    DspOpenRtb26JsonCodec() {
        this(JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.empty()
                        .withValueInclusion(JsonInclude.Include.NON_NULL))
                .build());
    }

    DspOpenRtb26JsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    OpenRtbMessages.BidRequest decodeBidRequest(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            BidRequestJson request = mapper.readValue(body, BidRequestJson.class);
            if (request.at() == null || request.at() != FIRST_PRICE_AUCTION) {
                throw new IllegalArgumentException("the project profile requires at=1");
            }
            if (request.tmax() == null) {
                throw new IllegalArgumentException("tmax is required by the project profile");
            }
            if (request.cur() == null
                    || request.cur().size() != 1
                    || !CURRENCY.equals(request.cur().getFirst())) {
                throw new IllegalArgumentException("the project profile requires cur=[KRW]");
            }
            if (request.imp() == null) {
                throw new IllegalArgumentException("imp is required");
            }
            return new OpenRtbMessages.BidRequest(
                    request.id(),
                    request.tmax(),
                    request.imp().stream().map(DspOpenRtb26JsonCodec::toImpression).toList()
            );
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid OpenRTB bid request", exception);
        }
    }

    byte[] encodeBidResponse(BidResponse response) {
        Objects.requireNonNull(response, "response");
        var json = new BidResponseJson(
                response.id(),
                response.currency(),
                response.seatbid().stream().map(DspOpenRtb26JsonCodec::toSeatBid).toList(),
                response.nbr().isPresent() ? response.nbr().getAsInt() : null
        );
        try {
            return mapper.writeValueAsBytes(json);
        } catch (Exception exception) {
            throw new IllegalStateException("could not encode OpenRTB bid response", exception);
        }
    }

    private static Impression toImpression(ImpJson imp) {
        if (imp == null
                || imp.banner() == null
                || imp.banner().format() == null
                || imp.banner().format().size() != 1) {
            throw new IllegalArgumentException("one banner format is required per impression");
        }
        if (!CURRENCY.equals(imp.bidfloorcur())) {
            throw new IllegalArgumentException("bidfloorcur must be KRW");
        }
        if (imp.exp() == null || imp.exp() != OpenRtbMessages.RENDER_EXPIRY_SECONDS) {
            throw new IllegalArgumentException("exp must be 2");
        }
        FormatJson format = imp.banner().format().getFirst();
        return new Impression(
                imp.id(),
                format.w(),
                format.h(),
                toMilliKrw(imp.bidfloor() == null ? BigDecimal.ZERO : imp.bidfloor()),
                imp.exp()
        );
    }

    private static SeatBidJson toSeatBid(SeatBid seatBid) {
        return new SeatBidJson(seatBid.bids().stream()
                .map(DspOpenRtb26JsonCodec::toBid)
                .toList());
    }

    private static BidJson toBid(Bid bid) {
        return new BidJson(
                bid.bidId(),
                bid.impressionId(),
                fromMilliKrw(bid.cpmMilliKrw()),
                bid.noticeUrl().toString(),
                bid.lossUrl().toString(),
                bid.billingUrl().toString(),
                bid.campaignId(),
                bid.creativeId(),
                bid.expirySeconds()
        );
    }

    private static long toMilliKrw(BigDecimal cpmKrw) {
        BigDecimal normalized = cpmKrw.stripTrailingZeros();
        if (normalized.signum() < 0 || normalized.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "KRW CPM must be non-negative with at most three decimal places");
        }
        try {
            return cpmKrw.movePointRight(MONEY_SCALE).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("KRW CPM is outside the supported range", exception);
        }
    }

    private static BigDecimal fromMilliKrw(long cpmMilliKrw) {
        return BigDecimal.valueOf(cpmMilliKrw, MONEY_SCALE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BidRequestJson(
            String id,
            Integer at,
            Integer tmax,
            List<String> cur,
            List<ImpJson> imp
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImpJson(
            String id,
            BannerJson banner,
            BigDecimal bidfloor,
            String bidfloorcur,
            Integer exp
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BannerJson(List<FormatJson> format) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FormatJson(int w, int h) {
    }

    private record BidResponseJson(
            String id,
            String cur,
            List<SeatBidJson> seatbid,
            Integer nbr
    ) {
    }

    private record SeatBidJson(List<BidJson> bid) {
    }

    private record BidJson(
            String id,
            String impid,
            BigDecimal price,
            String nurl,
            String lurl,
            String burl,
            String cid,
            String crid,
            int exp
    ) {
    }
}
