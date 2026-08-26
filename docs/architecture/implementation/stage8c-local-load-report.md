# Stage 8C 로컬 부하 검증 보고서

실행 시각: 2026-08-26 23:45–2026-08-27 00:06 KST  
소스 기준: `d27e534`  
판정: 로컬 진단 완료 · 분리 호스트 합격 시험 필요

## 목적과 한계

Stage 8C 자동화가 정의한 500 RPS 용량 시험과 500→1,000→100 RPS 과부하·회복 시험을 실제 SSP, 프로젝트 DSP, 세 PostgreSQL 저장소와 외부 DSP 두 개를 연결해 실행했다.

이번 실행에서는 k6, SSP, 프로젝트 DSP, 인증 게이트웨이, 외부 DSP 두 개와 PostgreSQL 세 개가 한 Mac에서 CPU·메모리·loopback 네트워크를 공유했다. 따라서 이 결과는 운영 합격 증거가 아니라 병목과 시험 설정을 찾는 로컬 진단이다.

## 환경

| 항목 | 값 |
|---|---|
| CPU | Apple M5, 10 cores |
| 메모리 | 16 GiB |
| JVM | Eclipse Temurin 21.0.11 |
| k6 | 2.1.0, darwin/arm64 |
| PostgreSQL | 17 계열 Docker 컨테이너 3개 |
| 힙 | SSP·DSP 각각 `-Xms1g -Xmx1g` (수정 후 진단) |

```text
k6
 └─ SSP :18080
     ├─ 인증 게이트웨이 :18083 ── 프로젝트 DSP :18084
     ├─ 외부 DSP A :18081
     └─ 외부 DSP B :18082
          ├─ Provider/SSP DB :15432
          ├─ DSP Ledger DB   :35432
          └─ DSP Outcome DB  :35532
```

외부 DSP 응답은 경매 ID의 SHA-256 첫 바이트를 네 버킷으로 나눴다. A가 두 버킷, B가 한 버킷에서 프로젝트보다 높은 가격을 내고 나머지 버킷에서는 낮은 가격을 내므로, 프로젝트 DSP의 기대 낙찰률은 25%다.

## 최초 500 RPS·10분 결과

실행:

```bash
BASE_URL=http://127.0.0.1:18080 \
RPS=500 DURATION=10m PRE_ALLOCATED_VUS=1000 MAX_VUS=2000 \
k6 run performance/k6/stage8c-capacity.js
```

| 지표 | 결과 | 기준 | 판정 |
|---|---:|---:|---|
| 요청 | 300,000 | 500 RPS·10분 | 충족 |
| dropped iterations | 0 | 0 | 충족 |
| p99 | 32.46 ms | ≤ 50 ms | 충족 |
| HTTP 실패 | 195 | 0 | 실패 |
| 유효하지 않은 경매 | 599 | 0 | 실패 |
| 프로젝트 낙찰률 | 12.80% | 20–28% | 실패 |

### 발견한 제품 결함

프로젝트 DSP가 받은 LOSS는 이미 예약을 반환했지만, 예약 만료 큐가 2초 뒤 같은 예약을 EXPIRY 후보로 다시 저장했다. 최초 실행 종료 시 다음 증거가 남았다.

```text
LOSS outcomes       117,488
EXPIRY outcomes      37,998
conflict rows       113,820
```

단일 만료 worker가 이미 종결된 LOSS까지 저널링하면서 처리량이 밀렸고, 캠페인별 로컬 예약 상한에 도달한 프로젝트 DSP가 후반부에 NO_BID로 전환했다. 이것이 낙찰률이 기대값의 절반으로 내려간 직접 원인이다.

## 수정과 회귀 검증

`ReservationStateView` 조회 포트를 추가했다. 만료 작업은 로컬 예약이 아직 pending일 때만 durable EXPIRY를 기록한다. 상태 변경 포트인 `ReservationFinalizer`와 조회 책임을 분리했으며, 실제 만료는 계속 DB 기록 후 로컬 상태에 재생하므로 durable-first 순서는 유지한다.

검증 결과:

```text
DSP tests             187 passed
SSP tests             135 passed
Stage 8C system tests   3 passed
post-fix conflicts      0
```

수정 후 500 RPS 1분 진단에서 프로젝트 낙찰률은 각각 23.66%, 26.28%로 회복했다. 그러나 같은 호스트에서 전체 토폴로지를 함께 실행한 p99는 110.98 ms와 178.95 ms였고, 504도 각각 25건과 74건 발생했다. 기능 병목은 제거됐지만 이 배치로는 50 ms 운영 기준을 판정할 수 없다.

## 과부하·회복 진단

500→1,000→100 RPS를 60초·60초·30초로 실행했다.

| 지표 | 결과 | 기준 | 판정 |
|---|---:|---:|---|
| overload 보호 성공 | 53,145 | ≥ 24,000 | 충족 |
| recovery 성공 | 3,001 | ≥ 3,000 | 충족 |
| recovery p99 | 4.62 ms | ≤ 50 ms | 충족 |
| overload 명시적 503 | 0 | > 0 | 실패 |
| 예상 밖 실패 | 6,924 | 0 | 실패 |
| normal p99 | 129.76 ms | ≤ 50 ms | 실패 |
| overload 보호 p99 | 222.39 ms | ≤ 50 ms | 실패 |

이 실행에서는 `PROVIDER_MAX_IN_FLIGHT=4096`로 설정해 SSP admission gate를 사실상 해제한 설정 오류가 있었다. 기본 128로 되돌린 500 RPS 30초 진단에서는 503 207건과 504 583건이 함께 발생했다. 단일 호스트 자원 경쟁 때문에 정상 부하도 gate에 닿았으므로, 이 값으로 최종 합격이나 적정 admission 용량을 판정하지 않는다.

과부하 종료 후 100 RPS 구간은 3,001건 모두 성공했고 p99 4.62 ms로 회복했다. 구조적으로 회복 불능이나 누적 큐 고착은 관찰되지 않았다.

## 금액 불변식

수정 후 모든 로컬 진단에서 `monetary_event_conflict`는 0을 유지했다. 마지막 대조에서 원장 책임액도 보존됐다.

```text
responsibility = 1,000,000,000,000
available      =   999,000,000,000
outstanding    =     1,000,000,000
committed      =                 0
quarantined    =                 0
합계           = 1,000,000,000,000
```

렌더 완료를 발생시키지 않은 부하이므로 BILLING committed 금액이 0인 것은 예상 결과다.

## 최종 판정과 다음 실행

8C는 아직 완료가 아니다. 다음 배치에서 역할을 물리적으로 분리해야 한다.

1. 부하 발생기: k6 전용 호스트
2. 대상 호스트: SSP와 프로젝트 DSP를 각각 독립 프로세스 또는 컨테이너로 배치
3. 시험 대역: 인증 게이트웨이와 외부 DSP 두 개를 대상 JVM과 다른 자원에 배치
4. 데이터 계층: Provider/SSP, Ledger, Outcome PostgreSQL의 CPU·IO 지표 수집
5. 500 RPS 10분 합격 후 같은 배포에서 admission 값을 조정해 1,000 RPS 과부하 시험 실행

분리 호스트에서도 504가 남으면 SSP deadline budget, JVM 정지 시간, 회사별 DSP 채널 지연을 같은 타임라인으로 대조한다. 503은 임의로 만들지 않고 500 RPS 정상 구간을 침범하지 않는 가장 작은 admission 용량에서 검증한다.
