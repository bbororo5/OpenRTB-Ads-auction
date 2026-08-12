package com.bbororo.rtb.dsp.notification;

/** 요청 처리 중 외부 통신 없이 현재 발급 키를 제공한다. */
@FunctionalInterface
public interface NoticeTokenKeySource {

    NoticeTokenKey activeKey();
}
