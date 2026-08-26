# DSP 8C 운영 검증

상태: 자동 검증 경계 구현 · 분리 호스트 용량 합격 실행 대기

상위 문서: [DSP 설계 로드맵](dsp-design-roadmap.md), [부하·데이터·검증 기준](../../requirements/workload.md)

## 목적

8A·8B가 조립한 런타임을 정상 한 건이 아니라 실제 회사 경계, 포화, 저장소 장애와 종료 중에도 신뢰할 수 있는지 반증한다. 기능 추가보다 다음 세 질문에 답하는 단계다.

1. SSP와 DSP가 독립 코덱으로 같은 OpenRTB 가격·슬롯·통지 의미를 해석하는가?
2. 초과 부하와 느린 통지가 입찰 p99와 금액 접수 경계를 함께 무너뜨리지 않는가?
3. 저장소 장애와 종료 중에 성공을 위장하거나 금액 효과를 중복하지 않는가?

## 검증 구조

```text
Provider HTTP
  → 실제 SspRuntimeFactory
  → SSP OpenRtb26Codec
  → 인증 게이트웨이 시험 대역
  → 실제 DspRuntimeFactory
  → DSP 입찰·리스·Proof
  → SSP render 접수·billing worker
  → DSP Outcome PostgreSQL
```

`system-test` 모듈은 두 애플리케이션을 제품 의존 관계로 묶지 않고 검증에서만 함께 참조한다. 게이트웨이 대역은 SSP가 전송한 요청에 내부 인증 헤더를 부여하고 응답을 그대로 중계한다. SSP가 게이트웨이 신뢰 헤더를 직접 만들도록 제품 계약을 바꾸지 않는다.

## 자동 증거

### 전체 왕복

`Stage8cOperationalRoundTripTest`는 다음을 실제 HTTP와 세 PostgreSQL 경계로 확인한다.

- Provider 요청이 SSP 코덱과 DSP 코덱을 지나 프로젝트 DSP 입찰로 돌아온다.
- 슬롯 ID, KRW 가격과 렌더 Proof가 SSP 응답에 보존된다.
- 렌더 완료 뒤 SSP billing worker가 DSP 표준 `burl`을 호출한다.
- SSP delivery는 `DELIVERED`, DSP Outcome은 `BILLING` 한 건으로 남는다.
- 같은 렌더 Proof 재호출은 DSP 금액 효과를 늘리지 않는다.

### 포화 격리

`ArmeriaDspOpenRtbServerTest`는 `SynchronousQueue`를 쓰는 두 bounded 실행 자원의 양방향 격리를 고정한다.

| 주입 | 보호해야 할 경로 | 기대 결과 |
|---|---|---|
| 통지 작업자 전부 점유 | 입찰 | 추가 통지는 503, 입찰은 계속 처리 |
| 입찰 작업자 전부 점유 | 통지 | 추가 입찰은 503, 통지는 계속 처리 |

이 시험은 구조적 격리를 증명한다. p99와 보호 처리량은 아래 k6 시험이 별도로 판정한다.

### 장애와 종료

- 활성 캠페인 두 개 중 한 개만 초기 리스를 받으면 `DspRuntime.start()`가 실패하고 HTTP 포트를 운영 상태로 열지 않는다.
- Outcome DB TCP 연결을 끊으면 `burl`은 503으로 실패하고, 연결 복구 뒤 같은 통지를 재시도해 `BILLING` 한 건만 저장한다.
- 수락한 입찰이 진행 중일 때 서버 종료를 시작하면 HTTP 진입을 먼저 닫고, 해당 입찰이 끝나기 전 작업자를 종료하지 않는다.
- `DspRuntimeTest`는 HTTP 뒤 리스·Outcome·JDBC·DB 풀을 역순 종료하는 객체 그래프 계약을 별도로 고정한다.

## 실행

로컬 시스템 시험용 PostgreSQL을 준비한다.

```bash
docker compose -f docker-compose.provider-config.yml up -d
docker compose -f docker-compose.dsp-ledger.yml up -d dsp-ledger-seoul
docker compose -f docker-compose.dsp-money-events.yml up -d dsp-money-seoul
./gradlew :system-test:stage8cSystemTest
```

일반 단위 시험에는 포화·종료 격리 시험이 포함된다.

```bash
./gradlew :dsp-app:test :ssp-app:test :system-test:test
```

## 부하 합격 판정

기존 k6 스크립트는 초기 구현 진단용이므로 8C 합격에 사용하지 않는다. 전용 스크립트는 `constant-arrival-rate`로 도착률을 고정한다.

| 스크립트 | 판정 |
|---|---|
| `stage8c-capacity.js` | 500 RPS 10분, p99 50ms 이하, 누락·기술 실패 0, 프로젝트 DSP 낙찰률 20~28% |
| `stage8c-overload-recovery.js` | 1,000 RPS에서 최소 400 RPS 보호, 보호 요청 p99 50ms 이하, 초과분 503, 100 RPS 감소 뒤 30초 내 회복 |

과부하 시험은 503을 전체 지연에 섞어 p99를 낮추지 않는다. HTTP 200으로 보호한 요청만 `stage8c_protected_duration`과 `stage8c_protected_auctions`에 기록한다.

분리된 부하 발생기에서 전체 데이터와 외부 DSP 두 개를 준비한 뒤 다음을 함께 남겨야 최종 합격이다.

- 커밋·빌드·JVM·인스턴스 사양과 데이터 버전
- 목표/실제 요청 수와 `dropped_iterations`
- p50/p95/p99/p99.9, 503과 예상하지 않은 실패
- SSP·DSP CPU, 메모리, GC, 작업자와 DB 풀 포화
- 시험 전후 캠페인 예산, 리스별 발급·소비·반환·격리
- 장애 주입·복구 시각

## 완료 기준

자동화 구현만으로 8C를 완료 처리하지 않는다. 다음이 모두 필요하다.

1. 전체 왕복·포화·DB 장애·종료 자동 시험 통과
2. 500 RPS 10분 정상 첨두 합격
3. 500→1,000→100 RPS 보호·회복 합격
4. 금액 불변식과 중복 효과 0건 대조
5. 실행 환경과 결과를 재현할 수 있는 보고서 보존
