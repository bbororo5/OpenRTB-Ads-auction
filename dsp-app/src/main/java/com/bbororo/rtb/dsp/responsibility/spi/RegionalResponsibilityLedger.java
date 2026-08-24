package com.bbororo.rtb.dsp.responsibility.spi;

import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.RegionalTransferActivation;
import com.bbororo.rtb.dsp.responsibility.api.ResponsibilityMessages.TransferActivation;
import java.util.concurrent.CompletionStage;

/** 준비된 이전을 대상 리전 책임액으로 한 번 활성화하는 원장 포트다. */
public interface RegionalResponsibilityLedger {

    CompletionStage<TransferActivation> activate(RegionalTransferActivation activation);
}
