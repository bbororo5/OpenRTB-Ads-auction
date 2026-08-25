# DSP 런타임 8A·8B 조립

상태: 입찰 HTTP 수직 흐름·운영 어댑터·독립 진입점 완료

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

## 8B 운영 조립

```text
캠페인 JSON 파일 SHA-256·버전 검증
  → 활성 캠페인 로컬 예산 계정 등록
    → 캠페인 인덱스 설치
      → Proof 활성 키·검증 키 링 조립
        → 리전 원장·Outcome DB 풀과 스키마 검사
          → Outcome 만료 작업자 시작
            → 활성 캠페인 초기 리스 공급 완료
              → 리스 보충·정산 주기 시작
                → 입찰·통지 HTTP 서버 공개
```

- `GET /notices/win|loss|billing?token=...`은 게이트웨이가 인증한 SSP 헤더를 요구하며 별도 bounded 통지 작업자에서 실행한다.
- 실행 중 캠페인은 불변이다. 갱신은 새 버전·체크섬을 명시해 `CampaignSnapshotInstaller`로 원자 교체하는 제어 경로로 남긴다.
- 키 링은 활성 키 하나로만 발급하고 이전 키를 포함한 전체 링으로 검증한다.
- 금액 DB 스키마는 앱이 임의로 변경하지 않고 `infrastructure/postgres` 배포 SQL이 소유한다. 앱은 필수 테이블을 시작 전 검사한다.
- HTTP를 먼저 닫고 리스·Outcome·JDBC·DB 풀을 역순으로 종료한다.

### 필수 운영 입력

| 범주 | 환경 변수 |
|---|---|
| 실행 정체 | `DSP_REGION_ID`, `DSP_INSTANCE_ID`, `DSP_PUBLIC_BASE_URL` |
| 캠페인 | `DSP_CAMPAIGN_SNAPSHOT_PATH`, `DSP_CAMPAIGN_VERSION`, `DSP_CAMPAIGN_SHA256` |
| Proof 키 링 | `DSP_NOTICE_TOKEN_ACTIVE_KEY_ID`, `DSP_NOTICE_TOKEN_KEYS` (`key-id=base64` 목록) |
| 리전 원장 | `DSP_LEDGER_JDBC_URL` |
| Outcome 저장소 | `DSP_OUTCOME_JDBC_URL` |
| DB 기본 인증 | `DSP_STORE_USERNAME`, `DSP_STORE_PASSWORD` |

포트·작업자 수·시간·리스 크기 정책은 `DspRuntimeSettings`와 `DspOperationalSettings`의 기본값을 사용하되 환경 변수로 재정의할 수 있다. 스냅숏 체크섬·키 길이·리스 상한·HTTP 경로 충돌은 포트를 열기 전에 거절한다.

`DspOperationalRuntimeIntegrationTest`는 실제 두 PostgreSQL을 사용해 초기 리스 발급, HTTP 입찰, 응답의 AES-GCM `burl` 호출과 `BILLING` Outcome 기록을 확인한다.

## 8C로 넘긴 경계

- SSP `OpenRtb26Codec`과 두 애플리케이션 실행 진입점을 함께 올린 전체 왕복
- 입찰·통지 작업자 포화와 p99 보호 측정
- 원장·Outcome DB 장애, 부분 초기 리스, 종료 중 진행 작업의 장애 주입
