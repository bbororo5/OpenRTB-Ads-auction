package com.bbororo.rtb.dsp.spending;

import com.bbororo.rtb.dsp.spending.SpendingMessages.LeaseSupplySnapshot;
import java.util.List;

/** 리스 보충 작업이 로컬 캠페인의 권한 현황을 일괄 조회하는 읽기 경계다. */
public interface LocalLeaseSupplyView {

    List<LeaseSupplySnapshot> supplySnapshots();
}
