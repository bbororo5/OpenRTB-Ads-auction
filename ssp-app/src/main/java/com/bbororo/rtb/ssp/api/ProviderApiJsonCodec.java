package com.bbororo.rtb.ssp.api;

import com.bbororo.rtb.ssp.contract.KrwCpm;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionRequest;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionResult;
import com.bbororo.rtb.ssp.contract.SspMessages.AuctionSlot;
import com.bbororo.rtb.ssp.contract.SspMessages.RenderProof;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 공급자 전용 HTTP/JSON 표현을 SSP 내부 메시지와 분리한다. */
public final class ProviderApiJsonCodec {

    private final ObjectMapper mapper;

    public ProviderApiJsonCodec() {
        this(new ObjectMapper());
    }

    ProviderApiJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public AuctionRequest decodeAuctionRequest(byte[] body) {
        try {
            AuctionRequestJson request = mapper.readValue(body, AuctionRequestJson.class);
            return new AuctionRequest(
                    request.providerId(),
                    request.providerKeyId(),
                    request.providerRequestId(),
                    request.tmaxMillis(),
                    request.slots().stream()
                            .map(slot -> new AuctionSlot(
                                    slot.impId(),
                                    slot.width(),
                                    slot.height(),
                                    KrwCpm.toMilliKrw(slot.floorCpmKrw())
                            ))
                            .toList()
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid provider auction request", exception);
        }
    }

    public RenderProof decodeRenderProof(byte[] body) {
        try {
            return new RenderProof(mapper.readValue(body, RenderCompletedJson.class).renderProof());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid provider render completion", exception);
        }
    }

    public byte[] encodeAuctionResult(AuctionResult result) {
        try {
            AuctionResultJson response = new AuctionResultJson(
                    result.auctionId(),
                    result.renderCompletionUrl().toString(),
                    result.slots().stream()
                            .map(slot -> new SlotResultJson(
                                    slot.winningBid().impId(),
                                    slot.winningBid().dspId(),
                                    KrwCpm.fromMilliKrw(slot.winningBid().cpmMilliKrw()),
                                    slot.renderProof().encodedValue()
                            ))
                            .toList()
            );
            return mapper.writeValueAsBytes(response);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode provider auction result", exception);
        }
    }

    public byte[] encodeError(String code) {
        try {
            return mapper.writeValueAsBytes(new ErrorJson(code));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode provider API error", exception);
        }
    }

    private record AuctionRequestJson(
            String providerId,
            String providerKeyId,
            String providerRequestId,
            int tmaxMillis,
            List<SlotJson> slots
    ) {
    }

    private record SlotJson(String impId, int width, int height, BigDecimal floorCpmKrw) {
    }

    private record RenderCompletedJson(String renderProof) {
    }

    private record AuctionResultJson(String auctionId, String renderUrl, List<SlotResultJson> slots) {
    }

    private record SlotResultJson(
            String impId,
            String dspId,
            BigDecimal cpmKrw,
            String renderProof
    ) {
    }

    private record ErrorJson(String code) {
    }
}
