# Stage 8C: 게이트웨이 수정과 재검증

## 현재 상태

2026-09-03: 게이트웨이 수정·로컬 검증·AWS 정식 스모크 통과. 실험 workload·배포 assets·제어 스택까지 모두 삭제 완료했다. 워밍업의 초기 실패는 별도 미해결 과제로 남긴다.

## 수정

- `performance/fixtures/stage8c/server.mjs`: 표준 hop-by-hop 헤더 및 `Connection`이 지정한 추가 필드를 제거한다. 기존 연결의 Host/Content-Length도 전달하지 않고 fetch가 새 연결에 맞게 구성한다. 인증된 SSP ID는 필터링 후 마지막에 설정한다.
- 동일 파일: 게이트웨이 생성과 listen을 분리하여 배포 구현을 테스트에서 그대로 사용한다. 실패 시 오류 코드와 timeout 여부만 기록하며 URL·본문·인증 헤더·자격 증명은 기록하지 않는다.
- `performance/fixtures/stage8c/server.test.mjs`: h2c 헤더를 가진 연속 요청의 upstream 도달, 임의 Connection 토큰, chunked 본문, 인증 ID 위조 방지, 204/503 전달을 검증한다.
- `system-test/src/test/fixtures/node-gateway.mjs`: 배포 구현을 import하는 프로세스 진입점이다. 테스트 전용 기능은 동적 upstream 주소 설정뿐이다.
- `AuthenticatedGatewayFixture`: Java로 HTTP 전달을 재구현하던 대역을 제거하고 실제 Node 프로세스를 실행한다. 프로세스 응답 및 종료 대기는 제한 시간 안에서 수행한다.
- `NodeGatewayTransportTest`: 기본 Java HTTP 클라이언트의 sendAsync 요청이 실제 Node 게이트웨이를 통과하여 세 번 모두 upstream에 도달하는지 검증한다.

## 검증 결과

| 검증 | 결과 |
| --- | --- |
| TypeScript build | 성공 |
| IaC·수명주기·권한 테스트 | 24개 성공 |
| Node 게이트웨이/가격 fixture 테스트 | 4개 성공 |
| SSP 테스트 (캐시 없이 재실행) | 140개 성공 |
| DSP 테스트 (캐시 없이 재실행) | 189개 성공 |
| 시스템 테스트 (실제 Node + SSP/DSP + 로컬 PostgreSQL) | 4개 성공; 별도 실행과 전체 실행 모두 통과 |
| 수정된 배포 Docker 이미지 빌드 | 성공: `rtb-stage8c-support:gateway-fix` |
| 해당 이미지 내 실제 server.mjs 회귀 테스트 | Node 22, 네트워크 격리 환경에서 4개 성공 |

시스템 테스트의 기존 입찰 제한 시간이나 성공 기준은 완화하지 않았다. 기존에 관찰한 첫 요청 지연 문제의 원인까지 해결됐다고 단정하지 않는다. 이 결과는 로컬 검증이며 AWS 스모크 통과나 운영 성능 증거를 대신하지 않는다.

## AWS 실증 결과

- RunId: `rtb-gateway-fix-20260903a`
- 실행 커밋: `eed77bc62bc397751b71e87d219cb81cf582f186`
- 서울 리전, `t4g.small` 5대: loadgen / SSP / DSP / support / observer 분리.
- 로컬 AWS 로그인으로 실행기를 구동하고 제한된 CloudFormation 실행 역할로 프로비저닝했다. GitHub OIDC 전체 배포 경로의 실증은 아니다.
- 독립 스케줄 회수 canary와 즉시 회수 probe 모두 성공한 뒤 workload를 생성했다.
- 정식 스모크 기록 구간: 2026-09-03 12:03:11.733–12:03:44.474 UTC (21:03 KST).

| 지표 | 워밍업: 10 RPS × 10초 | 정식: 10 RPS × 30초 |
| --- | --- | --- |
| 실제 요청 | 100 | 301 |
| HTTP 성공 | 95 / 100 | 301 / 301 |
| p99 | 345.46ms | 20.53ms (기준 ≤50ms) |
| 프로젝트 DSP 낙찰 | 24 / 95 = 25.26% | 80 / 301 = 26.57% (기준 20–28%) |
| 기술 실패 / 잘못된 경매 | 각각 5 | 각각 0 |
| dropped iterations | 0 | 0 |
| 판정 | 실패 기록 보존 | 모든 threshold 통과 |

직전 AWS 실험은 300건 모두 HTTP 성공했지만 프로젝트 DSP 낙찰은 0건이었다. 게이트웨이의 hop-by-hop 헤더 처리 수정 후, 기존 입찰 정책·제한 시간·정식 성공 기준을 완화하지 않고 프로젝트 DSP 참여와 정식 스모크 통과를 확인했다.

### 검증 경계

- 워밍업 실패 5건의 상세 원인은 아직 확정하지 않았다. 정식 통과를 최초 요청 안정성까지 해결됐다는 뜻으로 해석하지 않는다.
- 정식 최대 지연은 62.18ms, p99.9는 54.19ms였다. 모든 요청이 50ms 이내였다는 의미는 아니다.
- 10 RPS, 약 30초의 스모크이며 시스템 한계 처리량이나 장시간 안정성 시험이 아니다.
- AWS k6는 경매 API를 검증한다. render/billing DB 왕복은 앞의 로컬 시스템 테스트 결과이며, 이번 AWS 스모크의 검증 범위와 구분한다.
- 관찰 준비 검사에서 5개 Prometheus target이 모두 up, Tempo 수신 span 226개, Pyroscope 수신 profile 1,090개를 확인했다. Loki는 ready지만 로그 수신량이나 요청별 trace 연계까지 검증한 것은 아니다.
- Pyroscope의 마지막 readiness 503은 `Segment Writer not ready: waiting for 30s after being ready`였다. 재시작 0, OOM false였고 이후 정상 준비 상태로 전환됐다.

### 회수와 증거

정식 결과와 전후 호스트 상태 저장 후 즉시 회수를 요청했다. 사용자 확인을 기다리지 않았다. 5개 instance가 terminated이고 EBS가 0개임을 별도 조회했으며, workload 스택 `DELETE_COMPLETE`를 확인했다. 실행기의 workload/asset/lease 회수 완료 시각은 12:05:17.529 UTC이다.

상위 실행 래퍼의 `finally`는 workload/lease/canary와 EC2/EBS/VPC/ECR/S3 부재를 확인한 뒤에만 제어 스택을 삭제한다. workload가 남으면 AWS 회수기를 보존하고 오류로 종료한다. 이번 래퍼는 실행 증거에 보존하며 기존 npm experiment 명령이 제어 스택까지 삭제한다고 해석하지 않는다.

제어 스택 `DELETE_COMPLETE`와 전체 실행 종료를 12:07:24.947 UTC (21:07 KST)에 확인했다. 최종 확인: 실행 중/정지 EC2 0, EBS 0, 실험 VPC 0, EIP 0, 전용 ECR/S3 삭제, Lambda·EventBridge rule·CloudWatch alarm/log group 0. 비어 있는 CDK bootstrap 기반, 인증 전용 IAM/OIDC 스택 및 기본 VPC는 유지한다. 이미 발생한 사용량과 청구 반영 지연은 별개다. 실험 DB 디스크와 서버 내 관찰 데이터도 삭제했으며 필요한 결과는 로컬 증거로 보존했다.

증거 디렉토리: `docs/evidence/performance/2026-09-03/`

- `stage8c-aws-warmup.md`, `stage8c-aws-smoke.md`
- `stage8c-aws-{pre,post}-{warmup,smoke}-hosts.md`
- `rtb-gateway-fix-20260903a-run.json`
- `rtb-gateway-fix-20260903a-full-teardown.json`
- `rtb-gateway-fix-20260903a-final-resource-checks.json`
- `rtb-gateway-fix-20260903a-runner.mjs`, `rtb-gateway-fix-20260903a-console.log`

## 반복 실행의 종료 조건

1. 유효한 관리자 인증으로 `RtbStage8cControl` 재설치.
2. EventBridge → Lambda 독립 회수 및 즉시 회수 시험 통과 후에만 EC2 생성.
3. bounded runner로 워밍업과 정식 스모크 실행. 결과와 관계없이 즉시 workload/asset 회수; 사용자 확인을 기다리지 않음.
4. EC2·EBS·실험 VPC·RunId별 ECR/S3·lease 부재 확인 후 제어 스택까지 철거.
5. 회수 실패 시 성공으로 보고하지 않음. workload가 남아 있으면 독립 회수기를 먼저 제거하지 않음.

실행기 유실 시 workload는 AWS 측 만료 회수기가 담당한다(기본 40분, 상한 45분의 lease와 분당 스케줄). 이는 정상 종료 시 즉시 철거를 대체하지 않으며 AWS 장애까지 포함한 절대적인 비용 상한 보장은 아니다.
