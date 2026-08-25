# DSP 입찰 조정 인프로세스 인수 검증

상태: 7B 완료

상위 문서: [DSP 상세 설계 로드맵](dsp-design-roadmap.md)

## 검증할 약속

`PreparedBid`가 존재하면 실제 로컬 예약 하나와 그 예약을 인증하는 `WIN`·`LOSS`·`BILLING` 증표가 존재해야 한다. 예약할 수 없는 슬롯은 다른 슬롯의 완성된 입찰을 취소하지 않는다. 예약 뒤 입찰을 완성하지 못하면 그 예약은 외부에 공개되지 않고 기존 만료 경로로 반환돼야 한다.

## 실제로 연결한 컴포넌트

```text
DefaultCampaignRuntime
→ DefaultBidCoordinator
→ DefaultSlotBidWorkflow
→ DefaultCandidateBidAttempt
→ InMemoryLocalSpendingAuthority
→ DefaultReservationNoticeIssuer
→ AES-GCM Sealer / Verifier
```

HTTP, JSON, DSP 실행권과 PostgreSQL은 이 검증 범위에 포함하지 않는다. 각각 OpenRTB 실행 조립, 입찰 실행권, Outcome 저장소의 기존 경계에서 검증한다.

## 인수 시나리오

1. 실제 캠페인 후보와 설치된 리스로 입찰을 만들고 세 URL의 토큰을 다시 검증한다. 복원한 예약·리스·캠페인·입찰 ID와 금액은 Spending이 등록한 만료 표식 및 `PreparedBid`와 같아야 한다.
2. 서로 다른 규격의 슬롯 두 개 중 한 캠페인에만 리스를 설치한다. 예약 가능한 슬롯만 `BidDecision`에 남고 다른 슬롯의 실패는 성공한 입찰을 되돌리지 않아야 한다.
3. 실제 예약 뒤 Proof 발급 실패를 주입한다. 외부 입찰은 없어야 하며 예약은 `ReservationExpirationService`를 거쳐 해제되어 리스 가용액으로 돌아와야 한다.

구현 시험은 `dsp-app/src/test/java/com/bbororo/rtb/dsp/acceptance/BidCoordinationAcceptanceTest.java`에 둔다.
