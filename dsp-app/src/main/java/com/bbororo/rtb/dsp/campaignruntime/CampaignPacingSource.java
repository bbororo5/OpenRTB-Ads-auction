package com.bbororo.rtb.dsp.campaignruntime;

import java.time.Instant;

/**
 * 입찰 평가 시점에 캠페인의 목표 예산 소진 대비 지연율(pacingLagPpm)을 제공하는 포트다.
 * <p>
 * 반환값 단위는 백만분율(PPM: Parts Per Million)이며, 양수일수록 목표 대비 소진이 뒤처진 상태를 의미한다.
 */
@FunctionalInterface
public interface CampaignPacingSource {

    /** 주어진 시각 기준 캠페인의 페이싱 지연율(PPM)을 반환한다. */
    long pacingLagPpm(String campaignId, Instant evaluatedAt);
}
