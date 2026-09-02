# Stage 8C 실제 배포 검증 — 2026-09-02

## 검증 범위

새 자동 회수 경로로 서울 리전의 `t4g.small` 5대에 배포한다. 준비 검사,
10 RPS·10초 워밍업, 10 RPS·30초 정식 스모크, 증거 저장, 즉시 회수를 수행한다.
이번 실행기는 로컬의 유효한 AWS 인증을 사용했다. CloudFormation은 제한된
`RtbStage8cExecution` 역할을 사용했다. GitHub OIDC 역할의 사전 안전성 검증과
이번 로컬 실제 배포를 구분하며, GitHub 역할의 전체 배포 권한이 실증됐다고 보지 않는다.

## 실패를 분해한 순서

| 실행 ID 끝자리 | 관측 | 처리 |
| --- | --- | --- |
| `20260902a` | 이미지 빌드와 병행한 로컬 HTTP E2E가 200 대신 503 | EC2 생성 전에 실행을 중단하고 게시 자산 회수 |
| `20260902b` | CloudFormation의 `ec2:CreateLaunchTemplate` AccessDenied | 생성·삭제 두 권한을 서울 리전·계정의 Launch Template ARN으로 한정해 추가. 롤백·회수 완료 |
| `20260902c` | 5대 배포 성공. 정식 300건 모두 HTTP 성공, p99 15.58ms, 프로젝트 DSP 낙찰 0건 | 스모크 실패. 자료 저장 후 5대·스택·EBS·VPC·실행 자산 회수 |
| `20260902d` | 최소 lease 100,000 적용. 300건 HTTP 성공, p99 17.65ms, 프로젝트 DSP 낙찰 0건 | 스모크 실패. 증거 저장 후 전체 회수 완료. 푸시 보류 |

전체 실행 ID는 `rtb-lease-recheck-` 접두사를 갖는다. 원본 자료는
`docs/evidence/performance/2026-09-02/`에 저장한다. c 실행의 자료는 같은 날 재실행으로
덮어쓰지 않도록 실행 ID 하위 폴더에 별도로 보존했다.

## 서로 다른 두 예산 문제

### 1. 만료된 lease가 로컬 용량을 계속 점유

`CampaignSpendingAccount.install()`이 새 lease를 넣기 전에, 만료됐고 미결 예약이
없는 lease를 회수하도록 수정했다. 미결 예약이 있는 lease는 유지한다.

c 실행에서 DB 최대 세대가 51 → 116 → 243 → 347로 증가했다.
마지막 상세 조회에는 348세대도 있었다. 이전의 65세대 정지를 넘어섰지만,
이것만으로 입찰 가능 금액까지 충분하다고 판단해서는 안 된다.

### 2. 첫 lease의 금액이 입찰 한 건보다 작음

```text
실험 캠페인: cpmMilliKrw = 2,000
CampaignCandidate.impressionAmountMicros() = cpmMilliKrw = 2,000
기존 환경 기본값: DSP_MINIMUM_LEASE_MICROS = 1,000
실제 DB: face_value_micros = 1,000

단일 lease의 unusedMicros >= 요청 금액 이어야 예약 가능
1,000 < 2,000 → 예약 불가 → 무입찰(HTTP 204)
```

여러 작은 lease의 총합은 이 조건을 충족시키지 않는다. 현재 예약은 하나의 lease에
속한다. c 실행에서 직접 DSP 호출도 6.9–12.8ms에 204를 반환했으므로,
빠른 무입찰을 모두 네트워크 타임아웃으로 설명할 수 없다.

실험 IaC에 `DSP_MINIMUM_LEASE_MICROS=100000`을 명시했다. 첫 lease 하나로
2,000 micros × 10 RPS × 5초의 예약을 감당하는 설정이다. 회귀 테스트가
합성된 실제 user-data의 캠페인 금액과 최소 lease를 비교한다.
이는 10 RPS 스모크 설정이며, 500 RPS 용량 시험의 예산 공급률을 검증한 것은 아니다.
일반 운영의 캠페인별 최소 입찰 단위와 적응형 lease 수요 정책 개선은 별도 과제다.

**이 금액 제약 수정만으로 전체 실패가 해결되지는 않았다.** d 실행에서도 DB에서
100,000 micros의 lease가 56 → 181세대로 증가했지만 정식 측정의 프로젝트 낙찰은
0/300이었다. 종료 전 직접 호출은 503(클라이언트 측 654ms)을 반환했다.
따라서 c 실행의 빠른 204, d 실행의 직접 호출 503, SSP 경유 낙찰 0을 동일 원인으로
단정하지 않는다. 후보 선택·예약·통지 발급·마감 시간 중 실제 중단 지점을 로컬에서
관측하는 것이 다음 단계다. 반복 AWS 배포나 타임아웃 완화로 이 결과를 덮지 않는다.

## 최종 종료 확인

d 실행은 14:35:58 UTC에 회수를 완료했다. 이후 AWS 별도 조회에서 다음을 확인했다.

- 프로젝트의 non-terminated EC2 0개, EBS 0개, VPC 0개.
- 실험 Launch Template 0개, workload·lease·canary 스택 부재.
- 전용 ECR `imageIds=[]`, 전용 S3 `KeyCount=0`.
- d 실행 인스턴스 5개 모두 `terminated`. c 실행 5개도 별도 조회로 종료 확인.

`docs/evidence/performance/2026-09-02/final-cleanup.json`에 최종 조회 결과를 보존한다.
배포·회수 경로는 실증됐지만 기능 스모크는 실패했으므로 변경은 소단위로 커밋하고
원격 main으로의 푸시는 보류한다. 이는 부하 한계치 측정 완료가 아니다.

## 검증 경계와 미해결 항목

- TypeScript build 및 인프라·fixture 테스트 25개 통과.
- lease 회수 관련 Java 단위 테스트를 캐시 없이 재실행해 통과.
- 로컬 전체 DSP 테스트는 이미지 빌드 병행 시 HTTP E2E 503이 있었고,
  빌드를 중단한 뒤 189개가 통과했다. 실행 부하와의 연관성은 관측했지만 원인 확정은 아니다.
- 로컬 시스템 테스트는 마지막 재실행에서 3개 중 1개 실패했다.
  `returnsTechnicalFailureDuringOutcomeDbOutageAndPersistsOneRetry`의 장애 주입 전
  첫 입찰이 503이었다. AWS 워밍업 후 결과가 통과해도 이 실패를 해결한 것으로 보지 않는다.
- 추가 로컬 진단에서 첫 503 뒤 서로 다른 요청 ID의 후속 3건이
  200(28/15/19ms)을 반환했다. 최초 입찰에 대한 실패 assertion은 그대로 유지했다.
  진단용 임시 코드만 제거했고 결과 XML은 `local-regression/cold-bid-diagnostic.xml`에 보존했다.
  이는 첫 요청과 후속 요청의 차이를 재현한 것이며 AWS 낙찰 0의 원인 확정은 아니다.
- c 실행의 관찰 저장소 준비 검사, Prometheus 5개 target up,
  Tempo span 수신, Pyroscope profile 수신을 확인했다.
  Loki 준비 상태만으로 애플리케이션 로그의 수신·조회까지 입증한 것은 아니다.
- 재사용 제어 스택은 유지한다. 상주 EC2는 없지만 Lambda·로그·경보 등 사용량 과금
  가능성까지 0이라고 보장하지 않는다.
