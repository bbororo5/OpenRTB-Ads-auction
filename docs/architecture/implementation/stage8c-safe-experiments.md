# Stage 8C: 제한된 배포 권한과 자동 회수

상태: 구현·AWS 제어 스택 설치·GitHub 제한 역할의 사전 회수 실증과 새 경로의 실제 5-host 배포·회수까지 수행했다. 스모크 결과와 남은 검증 경계는 [2026-09-02 실제 배포 기록](stage8c-2026-09-02-verification.md)을 따른다. 로컬 실제 배포와 GitHub 제한 역할의 전체 배포 실증은 구분한다.

## 핵심 불변식

> 실험은 영속적인 실행 ID와 종료 시각을 가진다. 비용 자원과 해당 실행의 assets를 확인·회수하기 전에는 실행 ID를 해제하지 않는다.

```text
GitHub 수동 실행 (main, 명시적 비용 승인)
  ├─ AWS 독립 회수 시험: EventBridge → Lambda → canary 삭제
  ├─ 즉시 회수 시험: 별도 probe lease + S3 파일 → Lambda → 모두 삭제
  └─ 본 실험 lease 획득 (CreateStack의 이름 유일성으로 직렬화)
      → 전용 저장소로 image/template 게시 → 5-host 배포 → 준비 확인
      → 워밍업 → 정식 smoke → 결과 저장
      → finally: 회수 Lambda 호출 → 자원·assets 삭제 → lease 삭제

실행기 장애 또는 강제 종료
  → AWS의 분당 EventBridge가 동일 lease의 만료를 감지
  → 실행기와 무관하게 동일 회수 절차 재시도
```

정상 목적 달성 또는 실패 시에는 TTL을 기다리지 않고 회수를 요청한다. TTL은 실행기 유실에 대한 상한 안전장치다. 기본 요청 만료는 실행기 시작 후 40분이며, 회수기는 CloudFormation 생성 시각으로부터 45분을 넘는 요청을 수용하지 않는다. 스케줄은 분 단위이며 AWS 지연·장애가 있을 수 있어 초 단위 철거나 절대적인 비용 상한을 보장하지 않는다.

즉시 회수 요청은 lease의 `ExpiresAt`를 현재 시각으로 먼저 갱신한다. 종료 의도가 AWS에 기록된 뒤에는 호출자가 사라지거나 삭제 API가 실패해도 다음 스케줄이 원래 TTL을 기다리지 않고 이어받는다. 아직 생성 중인 lease는 갱신 가능한 상태가 될 때까지 기다리며 원래 TTL 보호도 유지한다.

## 권한을 나누는 이유

| 역할 | 허용 | 허용하지 않는 것 |
| --- | --- | --- |
| `RtbStage8cGitHub` | 기존 인증 검증 | 배포 권한 추가 없음 |
| `RtbStage8cDeploy` | 정확히 실험·lease·canary 스택, 전용 ECR/S3, 제한된 SSM 명령, 회수기 호출 | 기존 CDK 관리자 역할 AssumeRole, IAM 역할 생성, 제어 스택 변경, 회수기 코드 변경·스케줄 비활성화 |
| `RtbStage8cExecution` | 서울 리전 EC2 실험 구성, `t4g.small` 생성, 미리 만든 host 역할 PassRole | AdministratorAccess, 임의 IAM 권한 작성, Lambda·제어 스택 변경 |
| `RtbStage8cHost` | SSM 관리와 전용 ECR pull | 배포·권한 관리 |
| `RtbStage8cReaper` | 정확한 세 스택 조회·삭제, 태그가 있는 실험 instance 종료, 전용 assets 삭제 | 인스턴스 생성, IAM 권한 관리 |

EC2 네트워크 API는 작업별 태그 지원이 달라 execution 역할 일부 네트워크 권한은 서울 리전 단위다. 이를 모든 AWS 자원이 완전히 태그 격리되었다고 해석하지 않는다. GitHub main 변경 권한은 신뢰 경계이며, 현재 브랜치 보호 미설정 상태이므로 팀 운영 전에 리뷰·보호 규칙이 필요하다. 기존 CDKToolkit 역할·정책은 변경하지 않았다.

IMDSv2 강제 설정 때문에 CDK가 호스트별 Launch Template을 생성한다. 실행 역할은
현재 계정·서울 리전의 Launch Template ARN에 생성·삭제 두 작업을 허용한다.
이 권한 누락은 실제 배포에서 확인해 보완했으며, 템플릿 버전 편집 권한은 추가하지 않았다.

## 영속 자원과 실험 자원

- `RtbStage8cControl`: IAM 역할·instance profile, 회수 Lambda, 분당 EventBridge, 로그(7일), 오류 경보, 전용 ECR/S3, bootstrap 호환 버전 파라미터. 재사용 기반으로 남긴다.
- `RtbStage8cLease`: RunId·ExpiresAt 태그를 가진 WaitConditionHandle. 비용이 드는 서버는 없다. 중복 실행을 막고 실패 복구 정보를 보존한다.
- `RtbStage8cSafetyCanary`: 독립 회수 시험에만 사용하며 AWS 스케줄이 스스로 삭제해야 통과한다.
- `RtbStage8c`: 실제 5개 EC2·root EBS·VPC 등. 목적 달성/실패 즉시 회수한다.
- 전용 assets: ECR tag `<RunId>-<hash>`, S3 prefix `<RunId>/`. 다른 실행의 파일을 삭제하지 않는다. 1일 lifecycle은 최후 fallback이며 정상 회수는 즉시 삭제한다.

회수기는 instance를 종료한 뒤 실험 스택을 삭제한다. 스택·active instance·태그가 있는 EBS/VPC가 없어야 assets를 지우고 lease를 해제한다. 삭제 실패 시 lease를 유지하고 다음 tick에서 재시도한다. CF 밖으로 유출된 orphan EBS/VPC는 오류로 표시하고 사람이 확인할 수 있도록 남긴다. 이 경우 회수 성공으로 표시하지 않는다.

제어 스택에 상주 EC2는 없지만 Lambda 호출·로그·경보·저장소의 사용량 과금 가능성이 있다. 무조건 무료라고 보장하지 않는다. CloudWatch `RtbStage8cReaperErrors`는 오류를 기록하지만 **이메일/SNS 수신처는 미설정**이다. 독립 회수기의 AWS 권한 취소·AWS 서비스 장애·계정 차원의 차단까지 자동 복구하는 시스템은 아니다.

## 파일 구조

```text
infrastructure/aws-stage8c/
├─ lib/experiment-control-stack.ts   제어 스택·권한
├─ lib/experiment-synthesizer.ts     전용 assets·기존 관리자 역할 우회 방지
├─ lib/experiment-lifecycle.ts       실패를 보존하면서 finally 회수
├─ lib/stage8c-stack.ts              host profile 재사용·실행 태그·EBS 태그 전파
├─ runtime/reaper.cjs               AWS Lambda와 로컬 테스트가 공유하는 회수 로직
├─ scripts/experiment-control.ts    제어 스택 합성/설치
├─ scripts/experiment.ts            안전성 시험·실험·회수의 실행기
└─ test/experiment-safety.test.ts    수명주기·소유권·오류·IAM 회귀 테스트
.github/workflows/stage8c-experiment.yml   수동 실행 + 결과 artifact
```

SSM 실행은 `set -eu`와 원격 execution timeout을 사용한다. 앞 명령이 실패했는데 마지막 명령 성공으로 가려지는 것을 막는다. 데이터베이스 비밀번호는 실행기 오류 메시지에서 가린다.

## 실행 방법

제어 스택 설치는 최초 관리자 작업이다. GitHub 배포 역할은 이 권한을 받지 않는다.

```bash
cd infrastructure/aws-stage8c
npm ci
npm run build
npm test
npm run experiment-control -- synth
npm run experiment-control -- install --ack-cost

# 먼저 독립/즉시 회수 시험만 실행. 실제 EC2 없음.
npm run experiment -- safety-check

# 승인된 실험: 안전성 시험을 다시 수행한 뒤 배포하고 반드시 회수 시도.
npm run experiment -- run --ack-cost

# 실패한 실행을 명시적으로 다시 회수. 다른 실행의 lease면 거절.
npm run experiment -- cleanup --ack-cost --run-id=rtb-실제실행ID
```

GitHub에서는 `Stage8C bounded experiment`를 main에서 수동 실행한다. 기본 모드는 `safety-check`다. `smoke`와 비용 승인 둘 다 선택해야 EC2가 생성된다. push·PR·예약 실행은 없다. Linux ARM runner를 사용하여 ARM 이미지를 네이티브로 빌드한다.

`finally`와 workflow `always()`는 정상적인 실패·취소를 처리하지만, runner 강제 종료에도 실행된다고 보장할 수 없다. 그래서 AWS의 별도 회수기가 필요하다. 안전성 시험은 실제 배포된 Lambda의 코드 지문·스케줄·연결 대상을 확인하고, 고정된 stack ID로 `DELETE_COMPLETE`를 관찰해야 통과한다. 단순히 이름 조회가 실패했다고 삭제 성공으로 보지 않는다.

## 검증 경계

- 로컬: 성공/실패/부분 획득에도 회수, 원인 오류 보존, 다른 실행 삭제 거절, 유효 lease 보존, TTL clamp, assets 삭제 실패 시 lease 유지, canary 실패와 본 실험 회수 격리, IAM/host profile 구조.
- AWS 사전 시험: 스케줄 기반 canary 삭제 + 강제 회수의 lease/S3 파일 삭제. EC2 종료와 ECR 이미지 삭제의 실제 실증까지 대신하지는 않는다.
- 실제 실험 후: smoke 결과와 별개로 EC2·EBS·VPC 및 해당 assets의 부재를 확인해야 종료다. 이 확인에 실패하면 성공으로 보고하지 않는다.

### 2026-09-02 검증 기록

- 최종 TypeScript build와 테스트 24개 통과. GitHub Linux ARM runner에서도 동일 테스트 통과.
- `RtbStage8cControl` 설치 및 업데이트 완료. AWS 회수 Lambda 코드 지문과 연결된 분당 스케줄 확인.
- 로컬 사전 시험 `rtb-230471a4-eaa0-4b43-b4dc-f41b105be494`: 스케줄 기반 canary `DELETE_COMPLETE`, probe lease 및 S3 파일 회수 완료.
- [GitHub 실행 33636396633](https://github.com/bbororo5/OpenRTB-Ads-auction/actions/runs/33636396633): **success**, 1분 53초. `RtbStage8cDeploy` OIDC 인증, 독립 회수와 즉시 회수 모두 통과. 결과 JSON artifact 보관.
- 종료 의도 영속화와 갱신 중 회수기 거절을 추가한 [최종 실행 33637266101](https://github.com/bbororo5/OpenRTB-Ads-auction/actions/runs/33637266101): **success**, 1분 38초. 실행 커밋 `1715eed`. 독립 삭제와 즉시 회수 전체 경로를 새 버전으로 재검증했다.
- GitHub probe `rtb-probe-49fd2c2d-302b-4e95-8aea-6b7b066ce546` 회수 완료. 로컬 probe prefix의 S3 `KeyCount=0`도 별도 조회로 확인.
- IAM Access Analyzer의 배포 역할 정책 검사: findings 0개. 이는 정책 문법·일부 보안 검사이며 애플리케이션 배포의 모든 권한이 실증됐다는 뜻은 아니다.
- IAM simulation: 배포 역할의 `iam:CreateRole`, `sts:AssumeRole`, `lambda:UpdateFunctionCode`, `events:DisableRule` 모두 `implicitDeny`.
- 조회 시점의 프로젝트 EC2(pending/running/stopping/stopped)·EBS·VPC 각 0개, 전용 ECR imageIds 빈 배열, S3 object count 0, lease/canary 스택 부재 확인. 재사용 제어 스택은 유지.
- 워밍업 결과는 `warmup` label로 별도 저장한다. 템플릿 다운로드는 실행 역할에 전용 S3 prefix의 read만 추가하고, 이전 관리자 bootstrap/destroy 경로는 실행기에서 거절하도록 정리했다.

공식 근거: [EventBridge 스케줄 정밀도](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-create-rule-schedule.html), [Lambda Node.js SDK](https://docs.aws.amazon.com/lambda/latest/dg/lambda-nodejs.html), [GitHub runner](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).
