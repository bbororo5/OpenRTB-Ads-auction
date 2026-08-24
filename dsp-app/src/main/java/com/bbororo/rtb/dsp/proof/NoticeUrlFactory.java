package com.bbororo.rtb.dsp.proof;

import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.NoticeUrl;
import com.bbororo.rtb.dsp.proof.NoticeIssuanceMessages.SealedReservationNotice;

/** 봉인된 증표를 DSP가 통제하는 외부 통지 주소에 결합한다. */
public interface NoticeUrlFactory {

    NoticeUrl create(SealedReservationNotice notice);
}
