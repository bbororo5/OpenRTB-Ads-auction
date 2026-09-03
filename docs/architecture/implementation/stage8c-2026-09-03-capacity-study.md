# Stage 8C: 현재 구성의 입찰 경로 한계 측정

## 측정 계획

현재 5-host t4g.small 구성과 애플리케이션 정책을 유지한다. 대상은 publisher auction → SSP → gateway → DSP 경로이며 render/billing 처리량 시험이 아니다.

1. 관리자 인증으로 제어 스택 설치, 독립 스케줄 회수와 즉시 회수 검증.
2. 동일한 5대 물리 분리 배포와 관찰성 준비 확인.
3. 10 RPS × 30초 워밍업. 실패도 보존하고 정식 판정에서 분리.
4. 10 → 25 → 50 → 100 → 200 → 400 → 800 RPS를 각 60초 측정. 첫 실패에서 증가 중지.
5. 마지막 통과/첫 실패 사이를 중간값으로 최대 두 번 좁힘. 마지막 통과 부하를 한 번 재시험하고 10 RPS × 60초 복귀 검사.
6. 결과와 무관하게 workload와 assets를 회수한 후 제어 스택까지 삭제. 사용자 확인을 기다리지 않음.

측정 구간은 최대 20분이며, 40분 lease 만료 5분 전에는 신규 단계를 시작하지 않는다. 각 단계는 최대 180초, 단계 수와 최고 RPS는 코드로 제한한다. 회수 실패 시 AWS 회수기를 먼저 제거하지 않는다.

## 성공 기준과 해석

- p99 ≤50ms, HTTP 오류·잘못된 경매·미실행 iteration 모두 0.
- 프로젝트 DSP 낙찰률 20–28% 유지. 외부 fixture만 응답해서 전체 HTTP 성공으로 가려지는 상태를 거른다.
- 목표 요청 수의 99% 이상 실행. k6 VU는 각 단계 100개 사전 할당, 최대 200개로 제한하고 클라이언트 요청 timeout은 2초.
- 첫 실패 부하, 좁혀진 최저 실패 부하, 최고 관측 통과 부하와 그 재시험 결과를 구분한다.
- 낙찰률은 유한 표본의 변동이 있으며 60초 p99도 장기 보장이 아니다. 비단조 결과나 재시험 실패를 반복 실행으로 숨기지 않는다.
- 800 RPS까지 통과해도 최대 처리량을 발견한 것이 아니라 이 시험 상한까지 통과한 것이다.

총 예산 1,000,000,000,000 micros는 이 짧은 시험의 총액 부족을 피하기에 충분하다. 그러나 lease당 상한 100,000 micros와 보충 간격 1초 등 기존 공급 정책은 유지한다. CPU가 남아도 예산 공급/예약 제약 때문에 참여율이 떨어질 수 있으므로 하드웨어 한계로 단정하지 않는다.

## 증거와 도구 검증

- k6 원본 JSON은 삭제되는 컨테이너 밖으로 export한 후 로컬에 보존한다. k6 1.2.1의 flat summary 형식을 실제 실행으로 확인했다. threshold의 boolean은 성공 여부가 아니라 실패 여부임에 주의한다.
- HTTP 상태별 요청 수와 게이트웨이의 bid/notice upstream 응답별 누적 수를 기록한다. 게이트웨이 진단은 숫자만 반환하며 요청 내용·토큰은 노출하지 않는다.
- 각 부하 구간에 5대의 docker stats를 약 5초 간격으로 수집한다. 간격은 조회 시간에 따라 달라지며 정확한 연속 계측을 대신하지 않는다.
- 단계 전후의 예산 총계·lease 수/세대·미만료 lease·정산 상태와 outcome 종류별 개수를 기록한다. 원장 상태만으로 DSP 메모리의 개별 NoBid 이유를 확정하지 않는다.
- 과거와 같은 전후 호스트 상태 및 CloudWatch CPU credit도 보존한다. T4g standard의 credit 제약과 부하 발생기 자체 포화를 함께 확인한다.
- 계획/파서 테스트 4개, 기존 인프라/안전 테스트 24개, gateway fixture 테스트 4개 및 시스템 왕복 테스트 4개 통과.
- 로컬 모의 서버의 짧은 k6 실행에서는 p99 기준 실패가 발생했고 원본 JSON 및 실패 판정이 보존됨을 확인했다. 이는 측정 파이프라인 시험이며 실제 시스템 성능 결과가 아니다.

## 실행 결과

### 결론: 최대 RPS를 확정할 수 없고, 정상 상태 유지에 실패했다

RunId: `rtb-capacity-20260903a`. 배포 애플리케이션/fixture는 `ceca1d3` 상태다. 워밍업 후 로컬 AWS 증거 조회만 `426bd1d`에서 호스트별 비동기 실행으로 바꿨다. 서버 이미지, 부하 조건, 판정 기준은 변경하지 않았다. 각 요청 사이의 대기/무부하 증거 수집 시간도 존재하므로 연속 장기 부하 시험은 아니다.

| 순서 | 목표 RPS | 요청 수 | p99(ms) | HTTP 오류 | 프로젝트 DSP 낙찰 | 판정 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 최초 기준선 | 10 | 600 | 26.31 | 0 | 150 / 600 (25.0%) | 구간 집계 통과 |
| 증가 | 25 | 1,500 | 14.86 | 0 | 0 | 실패 |
| 경계 탐색 1 | 17 | 1,021 | 14.59 | 0 | 0 | 실패 |
| 경계 탐색 2 | 13 | 781 | 10.78 | 0 | 0 | 실패 |
| 기준선 재시험 | 10 | 601 | 14.44 | 0 | 0 | 실패 |
| 저부하 복귀 | 10 | 601 | 11.66 | 0 | 0 | 실패 |

모든 정식 구간은 60초이며 미실행 iteration과 잘못된 응답은 0이었다. 워밍업은 별도로 301건 중 HTTP 200 295건, 504 6건, p99 260.89ms, DSP 낙찰 64/295였다. 초기에 발생한 오류도 증거에 남겼다.

알고리즘의 `lastPassingRps=10`, `lowestFailingRps=13`은 관측 이력이지 안정적인 `[10,13)` 용량 구간이 아니다. 재시험과 복귀도 실패했고 첫 10 RPS 구간 안에서도 이미 DSP가 이탈하기 시작했다. 50 RPS 이상으로는 올리지 않았다. 하드웨어 최대 처리량과 billing 처리량은 측정하지 않았다.

### AWS에서 직접 확인한 증거

1. 첫 10 RPS 구간의 gateway 증분: bid 요청 600, DSP 200 응답 483, 204 응답 117, fetch 오류 0. 구간 평균 낙찰률 25%가 구간 내부의 이탈을 가렸다.
2. 이후 구간은 gateway의 bid 응답이 204로 나타났고 외부 fixture가 HTTP 성공을 유지했다. 따라서 p99가 낮아진 것을 성능 개선으로 해석하면 안 된다.
3. 마지막 신규 lease는 generation 415, 발급 시각 `12:52:50.653850Z`, 만료 시각 `12:52:55.653850Z`였다. 이 중단은 25 RPS 시험 전, 첫 10 RPS 시험 중 발생했다. 이후 모든 lease가 정산됐고 미만료 lease는 0이었다.
4. 원장 가용액은 `999,998,458,000 micros`로 충분했고, 격리액은 `1,542,000 micros`였다. 총 예산 부족으로 볼 근거가 없다.
5. outcome 773건에 대해 conflict 771건이 있었다. 771건 모두 기존/신규 event ID와 kind가 같았다. 종류는 LOSS→LOSS 556건, EXPIRY→EXPIRY 215건이며 79개 lease에 걸쳐 있었다.
6. 각 구간의 5-host 샘플 수집이 성공했다. 최초 10 RPS와 25 RPS의 컨테이너 CPU 표본 최대값은 SSP 25.55%/28.71%, DSP 33.45%/33.62%, k6 3.74%/3.80%였다. Docker CPU 100%는 한 코어 기준이며 연속 측정값은 아니다. 21:51→21:56 KST의 CloudWatch CPU credit은 SSP 3.11→4.72, DSP 3.19→4.90으로 증가했다. 이 자료는 CPU/발생기 포화를 주원인으로 지지하지 않지만 순간 포화를 완전히 배제하는 증거는 아니다.

### 재현된 결함과 아직 검증할 연결 고리

실제 PostgreSQL 17 + 현재 JDBC/`PostgreSqlReservationOutcomeStore`를 사용한 별도 로컬 격리 시험:

| 입력 만료 시각의 소수 초 | DB 왕복 후 | 현재 코드의 첫 기록 판정 |
| --- | --- | --- |
| `.123456` | `.123456` | `OutcomeChosen` |
| `.123456123` | `.123456` | `OutcomeConflict` |
| `.123456789` | `.123457` | `OutcomeConflict` |

`PostgreSqlReservationOutcomeStore.StoredOutcome.matches()`와 `StoredEvent.matches()`는 DB에서 읽은 만료 시각을 원래 `Instant`와 정확히 비교한다. 실제 저장 정밀도와 입력 정밀도가 다르면 같은 사건도 충돌로 처리된다. AWS의 같은 ID끼리 충돌한 증거와 일치한다.

```java
// PostgreSqlReservationOutcomeStore.StoredOutcome.matches()
&& expiresAt.equals(event.reservationExpiresAt());

// CampaignSpendingAccount.finalizeReservation()
if (targetState == ReservationState.EXPIRED && occurredAt.isBefore(reservation.expiresAt())) {
    return FinalizationOutcome.notApplied(NOT_DUE);
}
```

추가 로컬 모델 시험은 실제 DB 시험에서 확인한 아래쪽 반올림을 journal 응답에 넣어 현재 서비스/로컬 예산 코드를 실행했다. 원래 만료보다 123ns 이른 시각이 재생되면 `NOT_DUE`이고 예약은 여전히 pending이다. `ReservationOutcomeReplayer`는 반환값을 확인하지 않으며, `ReservationExpirationWorker`는 서비스가 예외 없이 완료되면 다음 표식으로 넘어간다. 이 상태를 64개 lease에서 만들자 `expiredButPending=64`, 다음 설치 `CAPACITY_EXCEEDED`가 재현됐다.

관련 파일:

- `dsp-app/src/main/java/com/bbororo/rtb/dsp/outcome/internal/PostgreSqlReservationOutcomeStore.java`: DB 왕복 후 시간 동등성 비교.
- `dsp-app/src/main/java/com/bbororo/rtb/dsp/outcome/internal/ReservationOutcomeReplayer.java`: 로컬 최종화 반환값 미확인.
- `dsp-app/src/main/java/com/bbororo/rtb/dsp/outcome/internal/ReservationExpirationService.java`, `ReservationExpirationWorker.java`: 저장/재생 후 표식 처리 완료 판단.
- `dsp-app/src/main/java/com/bbororo/rtb/dsp/spending/internal/CampaignSpendingAccount.java`: `NOT_DUE`, pending 예약이 남은 lease는 퇴거하지 않음.
- `dsp-app/src/main/java/com/bbororo/rtb/dsp/spending/internal/InMemoryLocalSpendingAuthority.java`: 기본 lease 용량 64.
- `dsp-app/src/main/java/com/bbororo/rtb/dsp/lease/internal/LeaseRefillService.java`, `LeaseMaintenanceWorker.java`: 로컬 설치 거절을 같은 refill 요청으로 재시도.

정밀도 충돌과 로컬 pending 누적 경로는 재현됐다. 그러나 AWS JVM의 실제 pending lease 수·설치 거절 사유는 직접 수집하지 않았으므로 **AWS 발급 중단의 전체 인과관계를 확정하거나 수정 효과를 검증한 것은 아니다.** 이번 시험에서는 비즈니스 코드를 고치거나 서버를 재시작해 상태를 초기화하지 않았다.

### 다음 실험 전 필요한 순서

1. 나노초 입력의 실제 DB 왕복을 회귀 테스트로 고정하고, 시간 표현/비교 규칙을 도메인 경계에서 일관되게 정한다. 시간 정밀도를 맞춘다는 이유로 만료 전에 과금/해제를 허용해서는 안 된다.
2. 내구 기록 성공과 로컬 해제 완료를 구분하고, `NOT_DUE` 등 미완료 결과의 재시도/표식 회수 규칙을 검증한다.
3. pending lease 수, 설치 거절 사유, refill 성공/실패를 수집해 같은 현상의 전체 경로를 확인한다.
4. 동일 저부하를 오래 유지해 상태 누적/회복을 검증한 다음 RPS ramp를 다시 한다. 구간 평균 외에 시간 창별 DSP 참여도도 확인한다.

### 증거와 회수

원본은 `docs/evidence/performance/2026-09-03/`에 보존했다. 주요 파일은 `rtb-capacity-20260903a-capacity.json`, 단계별 `*-summary.json`, `*-samples.json`, `*-budget.json`, `rtb-capacity-20260903a-conflict-diagnostic.json`, `rtb-capacity-20260903a-precision-probe.txt`, `rtb-capacity-20260903a-local-expiry-probe.txt` 및 재현 소스다. 원본 증거 디렉토리는 Git에 일괄 추가하지 않았다.

워크로드/lease 회수는 `2026-09-03T13:05:13.519Z`에 완료됐다. 제어 스택의 불변 StackId까지 `DELETE_COMPLETE`로 확인한 시각은 `2026-09-03T13:07:19.394Z`(22:07:19 KST)다. 실험 host·volume·VPC 0, 전용 ECR image·S3 object 0을 확인한 후 제어 스택도 삭제했다. 로컬 재현용 PostgreSQL 컨테이너도 제거했다.

`rtb-capacity-20260903a-full-teardown.json`의 `experimentExitCode=0`은 실패 부하를 포함한 측정 절차와 회수 완료를 뜻한다. 모든 부하가 통과했다는 뜻이 아니다. 최종 잔여 자원 점검은 `rtb-capacity-20260903a-final-resource-checks.json`에 보존한다. 측정 중 사용한 자원의 이미 발생한 비용까지 0이라고 주장하지 않는다.

`13:07:52.272Z` 최종 점검에서도 EC2 5대 terminated, 실험 EBS/VPC/EIP/NAT/snapshot 없음, 전용 저장소와 Lambda/EventBridge/알람/로그 그룹 없음이 확인됐다. 기존 인증용 `RtbStage8cGitHubAuth`와 내용이 비어 있는 CDK bootstrap만 유지했다. 테스트 결과 파일과 6구간×5호스트 샘플을 확인했으며 정식 요청 합계는 5,104건이다.
