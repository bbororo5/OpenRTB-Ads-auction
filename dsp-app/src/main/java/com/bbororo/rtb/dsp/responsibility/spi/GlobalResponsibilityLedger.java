package com.bbororo.rtb.dsp.responsibility.spi;

import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.CompletionResult;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.RegionalTransferActivation;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.RequestRegionalResponsibility;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferPreparation;
import java.util.concurrent.CompletionStage;

/** 전역 예비액의 격리와 이전 완료를 수행하는 단일 쓰기 권위 포트다. */
public interface GlobalResponsibilityLedger {

    CompletionStage<TransferPreparation> prepare(RequestRegionalResponsibility command);

    CompletionStage<CompletionResult> complete(RegionalTransferActivation activation);
}
