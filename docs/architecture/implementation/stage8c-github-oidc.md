# Stage 8C GitHub OIDC 인증 전환

상태: **인증 기반 코드·로컬 검증·AWS 설치·GitHub OIDC 실증 완료**. 후속 배포 권한·회수 장치·실험 워크플로의 구현 및 검증 경계는 [안전한 실험 실행](stage8c-safe-experiments.md)을 따른다.

## 목적과 범위

사람의 `aws login` 세션에 반복 배포를 묶지 않는다. GitHub Actions의 실행 신원을 AWS가 검증하고 임시 자격 증명을 발급하도록 한다. AWS 장기 Access Key를 로컬·저장소·GitHub Secrets에 추가하지 않는다.

SSO 전환은 보류한다. AWS 계정 접근용 IAM Identity Center를 위해 Organizations를 새로 구성하면 신규 Free Plan의 크레딧에 영향을 줄 수 있으므로, 계정 상태를 확인하기 전에는 조직 생성·가입을 하지 않는다. OIDC에는 Organizations가 필요 없다.

```text
사람: GitHub에서 수동 실행
  → GitHub가 실행 신원을 담은 OIDC 토큰 발급
  → AWS가 저장소·main 브랜치·STS audience 검증
  → RtbStage8cGitHub 역할의 15분 임시 자격 증명 발급
  → STS GetCallerIdentity로 계정·역할 확인
```

이 단계는 인증 인수 테스트다. 배포까지 완성됐다는 뜻이 아니며, 로컬 AWS CLI 로그인 방식도 바뀌지 않는다.

## 책임과 파일

| 파일 | 책임 |
| --- | --- |
| `infrastructure/aws-stage8c/lib/github-oidc-stack.ts` | OIDC 제공자와 신뢰 정책을 선언. EC2·배포 권한은 생성하지 않음 |
| `infrastructure/aws-stage8c/scripts/github-oidc.ts` | 오프라인 합성, 대상 계정 확인, 기존 제공자 탐색, 인증 스택만 설치 |
| `.github/workflows/stage8c-oidc-check.yml` | main에서 수동 인증 검사. 임시 키 자체는 출력하지 않음 |
| `infrastructure/aws-stage8c/test/github-oidc-stack.test.ts` | 정확한 신뢰 조건, 외부 계정 차단, 권한 미부여, 실행 조건 검증 |

## 권한 경계

- 대상: 계정 `333982363617`, 리전 `ap-northeast-2`.
- 신뢰 주체: `repo:bbororo5@114351464/OpenRTB-Ads-auction@1273253542:ref:refs/heads/main`만 `StringEquals`로 허용.
- GitHub OIDC 설정 API의 `sub_claim_prefix`를 기준으로 owner/repository 불변 ID를 포함한다. `use_immutable_subject: false`만 보고 이름 기반 형식으로 추정하지 않는다. 설치 시 실제 prefix와 default subject 설정을 재확인하고 다르면 AWS 변경 전에 중단한다.
- `aud`: `sts.amazonaws.com`만 허용. PR·fork·다른 브랜치의 기본 OIDC subject는 불일치한다.
- 워크플로는 `workflow_dispatch`만 지원한다. push·PR·예약 실행으로 시작하지 않는다.
- GitHub token 권한은 해당 job의 `id-token: write`만 허용한다. checkout도 하지 않는다.
- AWS 역할에는 inline/managed permission policy가 없다. `GetCallerIdentity`는 별도 권한 부여가 필요 없다.
- Actions는 이동 가능한 버전 태그가 아니라 검증한 commit SHA로 고정한다.
- 기존 OIDC 제공자가 있다면 audience를 확인하고 ARN만 참조한다. 공유 설정은 덮어쓰지 않는다.
- 이 스택이 이미 소유한 제공자는 재설치에서도 계속 소유하여 의도치 않은 삭제를 막는다.
- 새로 만든 제공자는 인증 스택 소유다. 다른 용도로 공유하게 되면 인증 스택 삭제 전에 소유권·보존 정책을 별도로 검토한다.

`main`에 코드를 넣을 수 있는 사람은 미래 배포 역할의 권한에도 영향을 줄 수 있다. 배포 권한을 추가하기 전에 브랜치 보호와 코드 리뷰 경계를 확인해야 한다. GitHub Environment를 추가하면 기본 OIDC `sub` 형식도 달라지므로 YAML만 임의 변경하지 않는다.

## 최초 한 번의 설치

기존 관리자 인증이 필요한 이유는 AWS에 GitHub를 신뢰하라는 설정을 처음 등록해야 하기 때문이다. 이 최초 인증은 OIDC로 우회할 수 없다. 인증 코드를 채팅이나 저장소에 붙이지 않는다.

```bash
# 사용자 로컬 터미널에서 수행. 브라우저가 표시한 코드는 같은 터미널에 입력.
# --remote는 로컬 callback 대신 코드 입력을 사용하는 대안이며 400 해결 보장은 아니다.
aws login --remote --profile default
aws sts get-caller-identity --profile default

cd infrastructure/aws-stage8c
npm ci
npm run build
npm test
npm run github-oidc -- synth

# AWS 설정 변경: 인증 전용 CloudFormation 스택 설치
AWS_PROFILE=default npm run github-oidc -- install
```

`synth`는 AWS 호출과 Docker 빌드 없이 인증 템플릿만 만든다. 산출물은 git에서 제외된 `cdk.out/github-auth/`에 둔다.

`install`은 STS로 계정을 확인한 다음 제공자를 탐색한다. 자격 증명 만료·다른 계정·기존 제공자의 audience 불일치가 발생하면 CloudFormation 변경 전에 실패한다. CDKToolkit 재부트스트랩, Organizations, SSO, IAM 사용자·Access Key 생성은 수행하지 않는다.

설치 환경에는 인증된 `gh` CLI도 필요하다. GitHub 설정 API로 현재 subject를 검증하기 때문이다.

## 인증 인수 기준

AWS 설치와 main 반영 후:

```bash
gh workflow run stage8c-oidc-check.yml \
  --repo bbororo5/OpenRTB-Ads-auction --ref main
```

실행의 Summary에 `arn:aws:sts::333982363617:assumed-role/RtbStage8cGitHub/github-...`가 표시되고 job이 성공해야 한다. 실제 Run ID를 증거로 남긴다. 로컬 테스트 통과만으로 OIDC 실증 성공으로 판정하지 않는다.

### 2026-09-02 실증 결과

- AWS `RtbStage8cGitHubAuth` 설치·업데이트 완료.
- [첫 실행 33634354052](https://github.com/bbororo5/OpenRTB-Ads-auction/actions/runs/33634354052): `AssumeRoleWithWebIdentity` 거절. 정책이 이름 기반의 이전 subject 형식을 사용한 것이 원인.
- GitHub OIDC 설정 API가 반환한 `sub_claim_prefix`를 대조해 owner/repository ID를 포함하는 정확한 형식으로 수정했다. wildcard를 추가하거나 GitHub 설정을 낮추지 않았다.
- [재실행 33634579945](https://github.com/bbororo5/OpenRTB-Ads-auction/actions/runs/33634579945): **success**, `identity` job 13초. 실행 커밋 `280c49f99f0a82384963b8ae1eb63c56adda102c`. 임시 자격 증명 발급과 계정·역할 검증 모두 성공.
- TypeScript build 및 테스트 11개 통과.
- IAM policy simulation: `ec2:RunInstances`, `cloudformation:CreateStack`, `sts:AssumeRole` 모두 `implicitDeny`. 배포 권한은 부여하지 않았다.
- 프로젝트 태그로 조회한 active EC2(pending/running/stopping/stopped), EBS, VPC 각각 0개. 인증 검사는 실험 자원을 만들지 않았다.
- Actions에 Node 20 선언을 Node 24로 실행했다는 비실패 경고가 남았다. 사용한 액션의 SHA는 고정되어 있으며 이번 실행은 성공했다.

공개 저장소에는 이미 검토 중인 로컬 DSP 커밋도 있으므로 인증 작업 때문에 그 커밋을 함께 밀어 넣지 않는다. 이번 변경은 별도 커밋으로 유지하고 main 반영 경계를 확인한다. GitHub CLI의 OAuth token은 workflow push 시 `workflow` scope가 추가로 필요할 수 있다.

## 다음 단계: 배포 권한과 비용 안전장치

인증 복구 후 기존 CDKToolkit 역할·정책을 조회하고, 그 결과를 바탕으로 별도 변경한다.

2026-09-02 조회에서 기존 CDK CloudFormation execution 역할에 `AdministratorAccess`가 연결되어 있고, deploy 역할은 여러 CloudFormation 변경 작업을 `Resource: *`에 허용하는 것으로 확인했다. GitHub `main` 브랜치 보호도 설정되어 있지 않았다. 이 기존 관리자 경로를 새 OIDC 역할에 그대로 위임하지 않는다. 배포 권한과 저장소 변경 통제는 별도 설계·검증 대상이다.

1. CDK 배포·asset publishing·CloudFormation execution 역할의 실제 권한을 조사한다.
2. 배포 역할을 제한한다. OIDC 신뢰가 한 저장소로 제한됐더라도, 광범위한 CDK 역할을 AssumeRole하면 AWS 자원 권한까지 좁아지는 것은 아니다.
3. 수동 실행 → 준비 완료 확인 → 워밍업 → 스모크 → 결과 보관 → 철거를 연결한다.
4. 실패·취소 시 철거 경로, 정확한 자원 소유권 확인, 잔존 EC2·EBS·VPC 검사 및 해당 실험의 ECR asset 정리를 추가한다.
5. runner 강제 종료에는 `always()`만으로 철거를 보장할 수 없으므로 독립적인 회수 경로를 검증한 뒤 실험 배포를 활성화한다.

현재 인증 검사 역할에는 실험 자원 생성 권한이나 단계가 없으므로 철거할 실험 자원도 없다. 별도의 `RtbStage8cDeploy`와 실행 워크플로를 후속 문서에서 관리한다. 기존 인증 검사용 역할의 권한은 확대하지 않는다.

사용자 요구: 목적 달성 또는 검증 실패 후 결과를 보관하고 즉시 철거한다. runner 자체 장애에도 동작하는 독립적인 회수 장치를 검증하기 전에는 실제 실험 배포를 하지 않는다. 인증용 IAM 역할·OIDC 제공자는 재사용 기반이므로 매번 지우는 실험 자원과 구분한다.

## 공식 근거

- [GitHub OIDC를 신뢰하는 IAM 역할](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles_create_for-idp_oidc.html)
- [GitHub immutable subject 형식](https://docs.github.com/en/actions/reference/security/oidc#immutable-subject-claims)
- [AWS credentials action](https://github.com/aws-actions/configure-aws-credentials)
- [STS GetCallerIdentity 권한](https://docs.aws.amazon.com/STS/latest/APIReference/API_GetCallerIdentity.html)
- [AWS CLI remote login](https://docs.aws.amazon.com/cli/latest/reference/login/)
- [GitHub workflow 취소 동작](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-cancellation)
- [AWS Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/)
