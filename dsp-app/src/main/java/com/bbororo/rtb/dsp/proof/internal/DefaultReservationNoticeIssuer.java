package com.bbororo.rtb.dsp.proof.internal;

import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ComposeReservationNoticeClaims;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.IssueReservationNotices;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.NoticeUrl;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.ReservationNoticeUrls;
import com.bbororo.rtb.dsp.proof.api.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeIssuer;
import com.bbororo.rtb.dsp.proof.api.ReservationNoticeKind;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** 공통 내용 구성, 종류별 봉인, 주소 작성을 조율해 완전한 통지 주소 묶음을 만든다. */
public final class DefaultReservationNoticeIssuer implements ReservationNoticeIssuer {

    private static final List<ReservationNoticeKind> REQUIRED_KINDS = List.of(
            ReservationNoticeKind.WIN,
            ReservationNoticeKind.LOSS,
            ReservationNoticeKind.BILLING
    );

    private final ReservationNoticeClaimsFactory claimsFactory;
    private final ReservationNoticeSealer sealer;
    private final NoticeUrlFactory urlFactory;

    public DefaultReservationNoticeIssuer(
            ReservationNoticeClaimsFactory claimsFactory,
            ReservationNoticeSealer sealer,
            NoticeUrlFactory urlFactory
    ) {
        this.claimsFactory = Objects.requireNonNull(claimsFactory, "claimsFactory");
        this.sealer = Objects.requireNonNull(sealer, "sealer");
        this.urlFactory = Objects.requireNonNull(urlFactory, "urlFactory");
    }

    @Override
    public ReservationNoticeUrls issue(IssueReservationNotices command) {
        Objects.requireNonNull(command, "command");
        var claims = claimsFactory.compose(new ComposeReservationNoticeClaims(
                command.authenticatedSspId(),
                command.regionId(),
                command.reservation()
        ));

        var urls = new EnumMap<ReservationNoticeKind, URI>(ReservationNoticeKind.class);
        for (ReservationNoticeKind kind : REQUIRED_KINDS) {
            var sealed = Objects.requireNonNull(
                    sealer.seal(new SealReservationNotice(kind, claims)),
                    "sealer result"
            );
            requireKind(kind, sealed.kind(), "sealer");

            NoticeUrl url = Objects.requireNonNull(
                    urlFactory.create(sealed),
                    "urlFactory result"
            );
            requireKind(kind, url.kind(), "urlFactory");
            urls.put(kind, url.value());
        }

        return new ReservationNoticeUrls(
                urls.get(ReservationNoticeKind.WIN),
                urls.get(ReservationNoticeKind.LOSS),
                urls.get(ReservationNoticeKind.BILLING)
        );
    }

    private static void requireKind(ReservationNoticeKind requested, ReservationNoticeKind returned, String collaborator) {
        if (returned != requested) {
            throw new NoticeIssuanceException(
                    collaborator + " returned " + returned + " for " + requested
            );
        }
    }
}
