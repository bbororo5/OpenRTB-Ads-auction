# Stage 8C: 영속 권한과 임시 과금 자원의 분리

상태 (2026-09-06): 코드 분리·로컬 테스트·합성·IAM Access Analyzer 정책 검사 완료. **새 구조의 AWS 설치 및 GitHub 전체 생성/철거 실증은 아직 하지 않았다.** 기존 9월 2~3일 실증은 이전 구조의 이력이다.

## 결론

반복 AWS 로그인을 제거하기 위해 과금 가능한 제어 스택을 상시 유지할 필요는 없다. IAM 기반은 유지하고, 제어 스택과 실험 자원은 실행마다 생성·삭제한다. 초기 신뢰/권한 설치 및 이후 IAM 변경에는 관리자 인증이 필요하다. 로컬 Grafana SSM 접속 인증은 이번 변경 범위 밖이다.

```text
유지: RtbStage8cGitHubAuth — OIDC 제공자·기존 인증 확인 역할
유지: RtbStage8cIdentity   — IAM 역할 6개·instance profile
        │
        ├─ ControlRunner → ControlExecution → 임시 RtbStage8cControl
        │                                    Lambda·EventBridge·경보·로그·S3·ECR
        └─ Deploy → Execution → 임시 RtbStage8c / Lease / SafetyCanary
                                 5개 EC2·EBS·VPC·실험 실행권

종료: 실험 자원 → assets → lease → 증거 보관 → 제어 스택 삭제
유지되는 것은 권한 기반뿐이며, 실험 데이터나 서버를 남기지 않는다.
```

## 권한 경계

- `RtbStage8cControlRunner`: GitHub main OIDC. 정확히 Control 스택 생성·삭제, 해당 service role PassRole, 철거 전 조회만 수행한다. IAM 생성/변경·EC2 생성 권한 없음. 기존 Control 업데이트/인수는 허용하지 않는다.
- `RtbStage8cControlExecution`: CloudFormation만 사용. 정해진 Lambda·rule·bucket·repository·parameter·log·alarm만 생성/삭제하고 기존 reaper 역할만 Lambda에 전달한다. IAM 변경·실험 서버 생성 권한 없음.
- `RtbStage8cDeploy`: 기존 실험 실행 역할. 제어 스택/철거기 수정·삭제는 여전히 금지한다.
- `RtbStage8cReaper`: 기존 실험 회수에 더해, 안전 조건을 확인한 뒤 Control 스택 자체의 삭제를 요청할 수 있다. IAM 기반 스택 삭제 권한 없음.
- 상위 두 GitHub 역할은 같은 main subject를 신뢰한다. 악의적인 main 작성자로부터 철거기를 격리하는 설계는 아니다. 별도 역할은 책임/권한 분리이며, main 보호·리뷰는 별도로 필요하다. 임의 관리자 역할 AssumeRole은 부여하지 않는다.

## 수명주기와 실패 처리

1. GitHub 수동 실행, 비용 승인, 코드 검증.
2. ControlRunner OIDC → 임시 Control 생성. 기존 스택이 있으면 덮어쓰지 않고 중단.
3. Deploy OIDC → 스케줄 회수/즉시 회수 시험 → 기존 bounded smoke.
4. 성공·실패 시 실험 자원/asset/lease 회수, 증거 artifact 보관.
5. ControlRunner OIDC를 새로 발급받아 자원 목록을 검사하고 Control 삭제. 고정 StackId의 DELETE_COMPLETE를 확인한다.

정상 종료는 1시간을 기다리지 않는다. 실행기 유실 시에는 기존 lease의 만료/회수 규칙이 먼저 동작한다. 제어 스택은 **생성 시각으로부터 1시간이 지난 뒤**, workload/lease/canary가 없고 프로젝트의 활성/중지 인스턴스·EBS·VPC·전용 ECR image·S3 object가 모두 없을 때만 스스로 삭제를 요청한다. 삭제는 CloudFormation에 저장된 제한된 service role로 수행한다.

조회 실패, 다른 실행, orphan 자원, assets 잔존은 빈 목록으로 취급하지 않는다. 안전 조건이 충족되지 않으면 철거기를 유지하고 오류를 보고한다. AWS 장애·권한 취소·부분 Control 생성 실패·CloudFormation 삭제 실패까지 무조건 자동 복구하거나 비용을 0으로 보장하지 않는다. 스케줄이 삭제된 뒤 CF 삭제가 실패하는 경우에는 외부에서 점검/복구해야 한다. 이메일 알림 수신처는 아직 없다.

실험 진입은 GitHub workflow concurrency로 직렬화한다. 로컬 저수준 명령을 같은 시간에 실행하면 이 직렬화 밖이므로 금지한다. Control 설치 실패 시 다른 실행의 기존 Control을 지우지 않도록 자동 finalizer는 설치 성공한 실행에서만 동작한다. 부분 생성 실패는 CF rollback 상태와 남은 자원을 별도로 확인한다.

## 파일 책임

| 파일 | 책임 |
| --- | --- |
| `lib/experiment-identity-stack.ts` | 영속 IAM과 정확한 대상 권한 |
| `lib/experiment-control-stack.ts` | IAM 없는 임시 제어 자원; 고정 ARN으로 역할 참조 |
| `scripts/experiment-identity.ts` | 최초 관리자 설치; 기존 제어 스택이 있으면 마이그레이션 거절 |
| `scripts/control-session.ts` | 제어 스택 create-only 설치 및 잔존 검사 후 삭제 |
| `lib/control-lifecycle.ts` | 철거 가능 조건 |
| `runtime/reaper.cjs` | 독립 회수 및 실행기 유실 시 제어 스택 자기 철거 |
| `.github/workflows/stage8c-experiment.yml` | ControlRunner → Deploy → ControlRunner 인증 전환 |
| `test/experiment-separation.test.ts` | 스택·권한·철거 조건·실행기 유실 회귀 검사 |

CloudFormation Export/ImportValue로 두 스택의 수명을 결합하지 않는다. Identity가 먼저 설치되어야 하며 이름 충돌은 사전에 해결한다. 기존 인증 확인 역할의 권한은 확대하지 않는다.

## 설치와 검증 순서

```bash
cd infrastructure/aws-stage8c
npm run build
npm test
npm run experiment-identity -- synth
npm run experiment-control -- synth

# 관리자 인증이 필요한 최초 한 번의 IAM 설치. 이번 작업에서는 미실행.
AWS_PROFILE=stage8c npm run experiment-identity -- install

# 검토한 커밋이 main에 반영된 뒤, EC2 없는 생성/회수 인수 시험부터 실행.
gh workflow run stage8c-experiment.yml --ref main \
  -f mode=safety-check -f acknowledge_cost=true
```

기존 Control이 IAM을 소유하는 상태라면 업데이트로 역할을 이동하지 않는다. 먼저 기존 절차로 실험/제어를 안전하게 철거하고 Identity를 설치한다. 9월 6일 직전 조회에는 GitHubAuth와 CDKToolkit만 있었다.

검증 결과: 인프라 테스트 35개 + gateway 4개 통과, TypeScript build 및 두 템플릿 합성 성공. IAM 6개 역할의 인라인 정책을 합성 템플릿의 역할 ARN으로 해석하여 AWS Access Analyzer validate-policy를 호출했고 모두 findings 0개였다. 이는 정책 정적 검사이며 실제 CloudFormation resource provider의 필요 권한을 모두 입증하지는 않는다.

후속 AWS 인수 기준: 관리자 재로그인 없이 OIDC로 Control 생성 → 회수 시험 → Control DELETE_COMPLETE → IAM 기반 존속 확인. 그 뒤 참여형 대시보드 준비/사용자 확인 게이트를 구현하고 실제 부하 실험으로 진행한다. 현재 smoke 워크플로는 여전히 자동 부하를 실행하므로 참여형 관찰용으로 실행하지 않는다.
