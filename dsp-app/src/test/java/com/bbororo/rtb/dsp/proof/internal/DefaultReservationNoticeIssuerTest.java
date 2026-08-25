package com.bbororo.rtb.dsp.proof.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bbororo.rtb.dsp.spending.api.SpendingMessages.ReservationGranted;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.IssueReservationNotices;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeUrl;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeIssuanceFailed;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticesIssued;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ReservationNoticeUrls;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.SealedReservationNotice;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("예약 통지 증표 발급자(Issuer) 발급 조율 단위 테스트")
class DefaultReservationNoticeIssuerTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-08-18T15:00:00Z");
    private static final Instant EXPIRES_AT = RESERVED_AT.plusSeconds(5);

    private ReservationNoticeClaimsFactory claimsFactory;
    private ReservationNoticeSealer sealer;
    private NoticeUrlFactory urlFactory;

    @BeforeEach
    void setUp() {
        claimsFactory = new ReservationNoticeClaimsFactory();

        sealer = command -> new SealedReservationNotice(
                command.kind(),
                "mock-token-" + command.kind().name().toLowerCase()
        );

        urlFactory = sealed -> new NoticeUrl(
                sealed.kind(),
                URI.create("https://dsp.example.com/notices/" + sealed.kind().name().toLowerCase() + "?token=" + sealed.encodedToken())
        );
    }

    @Test
    @DisplayName("발급 검증: 유효한 예약 요청 시 WIN, LOSS, BILLING 3종의 통지 URL 세트가 완벽히 생성된다")
    void issuesCompleteSetOfWinLossAndBillingNoticeUrls() {
        var issuer = new DefaultReservationNoticeIssuer(claimsFactory, sealer, urlFactory);
        var command = new IssueReservationNotices(
                "ssp-korea",
                "ap-northeast-2",
                new ReservationGranted(
                        "res-999",
                        "lease-100",
                        "camp-50",
                        "bid-123",
                        15_000,
                        RESERVED_AT,
                        EXPIRES_AT
                )
        );

        ReservationNoticeUrls urls = assertInstanceOf(
                NoticesIssued.class, issuer.issue(command)).urls();

        assertNotNull(urls.winNoticeUrl());
        assertNotNull(urls.lossNoticeUrl());
        assertNotNull(urls.billingNoticeUrl());

        assertEquals(
                URI.create("https://dsp.example.com/notices/win?token=mock-token-win"),
                urls.winNoticeUrl()
        );
        assertEquals(
                URI.create("https://dsp.example.com/notices/loss?token=mock-token-loss"),
                urls.lossNoticeUrl()
        );
        assertEquals(
                URI.create("https://dsp.example.com/notices/billing?token=mock-token-billing"),
                urls.billingNoticeUrl()
        );
    }

    @Test
    @DisplayName("종류 치환 방어: Sealer가 요청과 다른 ReservationNoticeKind(WIN 요청에 LOSS 반환 등)를 반환하면 즉시 예외를 던진다")
    void sealerReturningDifferentKindReturnsTypedFailure() {
        ReservationNoticeSealer buggySealer = command -> new SealedReservationNotice(
                ReservationNoticeKind.LOSS, // always returns LOSS even when requested WIN
                "corrupted-token"
        );
        var issuer = new DefaultReservationNoticeIssuer(claimsFactory, buggySealer, urlFactory);
        var command = sampleCommand();

        assertInstanceOf(NoticeIssuanceFailed.class, issuer.issue(command));
    }

    @Test
    @DisplayName("종류 치환 방어: UrlFactory가 요청과 다른 ReservationNoticeKind를 반환하면 즉시 예외를 던진다")
    void urlFactoryReturningDifferentKindReturnsTypedFailure() {
        NoticeUrlFactory buggyUrlFactory = sealed -> new NoticeUrl(
                ReservationNoticeKind.BILLING, // always returns BILLING
                URI.create("https://dsp.example.com/notices/billing?token=foo")
        );
        var issuer = new DefaultReservationNoticeIssuer(claimsFactory, sealer, buggyUrlFactory);
        var command = sampleCommand();

        assertInstanceOf(NoticeIssuanceFailed.class, issuer.issue(command));
    }

    private static IssueReservationNotices sampleCommand() {
        return new IssueReservationNotices(
                "ssp-1",
                "ap-northeast-2",
                new ReservationGranted(
                        "res-1",
                        "lease-1",
                        "camp-1",
                        "bid-1",
                        5_000,
                        RESERVED_AT,
                        EXPIRES_AT
                )
        );
    }
}
