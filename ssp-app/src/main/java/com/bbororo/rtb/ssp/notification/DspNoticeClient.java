package com.bbororo.rtb.ssp.notification;

import com.bbororo.rtb.ssp.contract.SspMessages.DeliveryOutcome;
import java.net.URI;
import java.time.Duration;

/** DSP가 입찰 응답에 제공한 OpenRTB 통지 URL을 호출하는 출력 포트다. */
@FunctionalInterface
public interface DspNoticeClient {

    DeliveryOutcome send(URI noticeUrl, Duration timeout);
}
