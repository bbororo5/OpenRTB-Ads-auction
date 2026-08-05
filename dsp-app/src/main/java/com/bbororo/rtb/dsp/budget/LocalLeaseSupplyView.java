package com.bbororo.rtb.dsp.budget;

import com.bbororo.rtb.dsp.budget.BudgetMessages.LeaseSupplySnapshot;
import java.util.List;

/** 리스 보충 작업이 로컬 캠페인의 권한 현황을 일괄 조회하는 읽기 경계다. */
public interface LocalLeaseSupplyView {

    List<LeaseSupplySnapshot> supplySnapshots();
}
