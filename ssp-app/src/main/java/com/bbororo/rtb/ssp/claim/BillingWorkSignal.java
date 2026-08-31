package com.bbororo.rtb.ssp.claim;

/** 과금 작업의 내구 저장이 끝난 뒤 같은 프로세스의 전달기를 깨우는 포트다. */
@FunctionalInterface
public interface BillingWorkSignal {

    void signal();
}
