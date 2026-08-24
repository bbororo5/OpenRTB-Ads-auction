package com.bbororo.rtb.dsp.responsibility;

import com.bbororo.rtb.dsp.responsibility.ResponsibilityMessages.RequestRegionalResponsibility;
import com.bbororo.rtb.dsp.responsibility.ResponsibilityMessages.ResponsibilityTransferResult;
import java.util.concurrent.CompletionStage;

/** 전역 격리와 지역 활성화를 조정하되 입찰 경로를 기다리게 하지 않는다. */
public interface ResponsibilityTransfer {

    CompletionStage<ResponsibilityTransferResult> request(RequestRegionalResponsibility command);
}
