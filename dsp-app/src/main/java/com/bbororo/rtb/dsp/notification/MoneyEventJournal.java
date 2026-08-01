package com.bbororo.rtb.dsp.notification;

import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.EventAppendResult;
import com.bbororo.rtb.dsp.notification.NoticeProcessingMessages.MonetaryNoticeEvent;
import java.util.concurrent.CompletionStage;

/** 지역 금액 사건 기록에 최초·중복·충돌을 내구 판정하는 저장소 포트다. */
public interface MoneyEventJournal {

    CompletionStage<EventAppendResult> append(MonetaryNoticeEvent event);
}
