package com.bbororo.rtb.dsp.proof;

import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.ComposeReservationNoticeClaims;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.IssueReservationNotices;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.NoticeUrl;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.ReservationNoticeUrls;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.SealReservationNotice;
import com.bbororo.rtb.dsp.openrtb.OpenRtbMessages.NoticeKind;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** 공통 내용 구성, 종류별 봉인, 주소 작성을 조율해 완전한 통지 주소 묶음을 만든다. */
public final class DefaultReservationNoticeIssuer implements ReservationNoticeIssuer {

    private static final List<NoticeKind> REQUIRED_KINDS = List.of(
            NoticeKind.WIN,
            NoticeKind.LOSS,
            NoticeKind.BILLING
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

        var urls = new EnumMap<NoticeKind, URI>(NoticeKind.class);
        for (NoticeKind kind : REQUIRED_KINDS) {
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
                urls.get(NoticeKind.WIN),
                urls.get(NoticeKind.LOSS),
                urls.get(NoticeKind.BILLING)
        );
    }

    private static void requireKind(NoticeKind requested, NoticeKind returned, String collaborator) {
        if (returned != requested) {
            throw new NoticeIssuanceException(
                    collaborator + " returned " + returned + " for " + requested
            );
        }
    }
}
