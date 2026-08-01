package com.bbororo.rtb.dsp.budget;

import com.bbororo.rtb.dsp.budget.BudgetMessages.PacingPosition;

/** 캠페인 선택이 로컬 권한의 결과적 일관성 투영만 읽는 좁은 경계다. */
public interface CampaignPacingView {

    PacingPosition positionOf(String campaignId);
}
