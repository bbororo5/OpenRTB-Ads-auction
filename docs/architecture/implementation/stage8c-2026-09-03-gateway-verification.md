# Stage 8C: 게이트웨이 수정과 재검증

## 현재 상태

2026-09-03: 게이트웨이 수정과 로컬 검증 완료. AWS 재배포는 관리자 인증 만료로 아직 시작하지 않았다. 전날 실험과 2026-09-03 잔여 자원 철거 이후 새 AWS 자원은 생성하지 않았다.

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

## 다음 AWS 실행의 종료 조건

1. 유효한 관리자 인증으로 `RtbStage8cControl` 재설치.
2. EventBridge → Lambda 독립 회수 및 즉시 회수 시험 통과 후에만 EC2 생성.
3. bounded runner로 워밍업과 정식 스모크 실행. 결과와 관계없이 즉시 workload/asset 회수; 사용자 확인을 기다리지 않음.
4. EC2·EBS·실험 VPC·RunId별 ECR/S3·lease 부재 확인 후 제어 스택까지 철거.
5. 회수 실패 시 성공으로 보고하지 않음. workload가 남아 있으면 독립 회수기를 먼저 제거하지 않음.

실행기 유실 시 workload는 AWS 측 만료 회수기가 담당한다(기본 40분, 상한 45분의 lease와 분당 스케줄). 이는 정상 종료 시 즉시 철거를 대체하지 않으며 AWS 장애까지 포함한 절대적인 비용 상한 보장은 아니다.
