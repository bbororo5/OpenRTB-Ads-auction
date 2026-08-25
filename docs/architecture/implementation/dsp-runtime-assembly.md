# DSP 런타임 8A 조립

상태: 입찰 HTTP 수직 흐름 기준선 완료

## 완성된 흐름

```text
ArmeriaDspOpenRtbServer
  → DspOpenRtbHttpAdapter
    → DefaultDspOpenRtbApi
      → BidRequestExecutor
        → BidExecutionGate
          → BidCoordinator
            → SlotBidWorkflow
```

- HTTP 수신 순간의 단조 시각으로 `AuctionDeadline`을 만들어 본문 집계·JSON 파싱에 쓴 시간도 `tmax`에 포함한다.
- `BiddingComponentFactory`가 중복 방지·지문·조정·슬롯 워크플로우를 컴포넌트 내부에서 조립한다.
- `DefaultDspOpenRtbApi`는 성공 입찰을 `seatbid`, 사유 없는 무입찰을 204, 실행권 거절을 `nbr`로 변환한다.
- `DspRuntimeSettings`는 서버 상한과 입찰 시간·기억 정책을 환경 문자열에서 읽고 조립 전에 검증한다.
- `DspRuntimeFactory`는 설정과 네 공개 포트(`CampaignCandidateSource`, `ReservationAuthority`, `ReservationNoticeIssuer`, `ReservationOutcomeProcessor`)를 받아 입찰 런타임을 조립한다.

## 검증 증거

`DspRuntimeHttpE2eTest`는 실제 `DefaultCampaignRuntime`, `InMemoryLocalSpendingAuthority`, AES-GCM Proof를 조립한다. 임의 포트의 Armeria 서버에 OpenRTB JSON을 전송하고 HTTP 200 응답에서 요청 ID, 슬롯 ID, KRW 가격, 통지 URL과 2초 `exp`를 확인한다.

## 8B로 넘긴 경계

현재 팩토리는 외부에서 준비된 네 컴포넌트 포트를 받는다. 아래 운영 소유권이 없으므로 `DspApplication.main`은 아직 추가하지 않는다.

- 캠페인 스냅숏의 초기 적재·갱신 어댑터
- 리스 초기 공급과 보충·정산 작업자
- 증표 활성 키·검증 키 소스
- Outcome PostgreSQL 데이터 소스와 마이그레이션 생명주기
- `nurl`/`lurl`/`burl` HTTP 라울트와 입찰 작업자와의 실행 자원 격리 측정

이를 모두 연결한 뒤에만 `DspRuntimeFactory.createFromEnvironment()`를 인자 없는 형태로 열고, `DspApplication.main`에는 조립·시작·종료 훅만 둔다.
