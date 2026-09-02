# Stage 8C AWS 분리 호스트 배포 자동화

상태: 인프라·시험 대역·OpenTelemetry 4-signal 스택·실행기 구현 완료 · 실제 AWS 배포 전

## 목적과 판정 경계

로컬 Stage 8C에서 한 호스트가 공유하던 부하 발생기, SSP, 프로젝트 DSP를 서로 다른 EC2로 분리한다. 관찰 백엔드도 측정 대상의 CPU·메모리를 침범하지 않도록 별도 EC2로 분리한다. 최종 운영 아키텍처를 재현하는 배포가 아니라, 다음 병목 분해를 결정하는 임시 성능 실험 셀이다.

```text
AWS VPC 10.42.0.0/24
├─ loadgen  10.42.0.10  k6
├─ ssp      10.42.0.20  SSP JVM
├─ dsp      10.42.0.30  프로젝트 DSP JVM
├─ support  10.42.0.40  인증 게이트웨이 + 외부 DSP A/B + PostgreSQL 3개
└─ observer 10.42.0.50  OTel Collector + Prometheus + Tempo + Loki + Pyroscope + Grafana
```

- loadgen·SSP·DSP는 서로 CPU·메모리를 공유하지 않는다.
- support의 세 DB는 한 EC2의 CPU·EBS를 공유한다. support가 먼저 포화되면 이 실험은 앱 한계가 아니라 DB 분리를 요구하는 증거다.
- 각 호스트의 Collector는 host metrics를 노출하고 SSP·DSP의 Java Agent metrics·traces·logs를 batch·retry한다. 별도 OTel eBPF Profiler는 모든 호스트의 CPU profile을 수집한다. Observer의 Prometheus·Tempo·Loki·Pyroscope가 네 신호를 보존하고 Grafana가 한 곳에서 조회한다.
- 앱·DB 포트는 인터넷에 열지 않고 역할 Security Group 사이에만 허용한다.
- NAT Gateway, Load Balancer, RDS를 생성하지 않는다. SSM 관리와 이미지 pull을 위해 다섯 EC2에 임시 public IPv4를 부여한다.
- T 계열은 `standard` CPU credit 모드다. 크레딧 고갈 후에는 추가 과금 대신 throttle이 걸리므로 `CPUCreditBalance`를 성능 결과와 같이 판독한다.

## 도구 선택

TypeScript AWS CDK로 자원 그래프와 검증 로직을 표현하고 CloudFormation에 상태·롤백을 위임한다. `scripts/stage8c.ts`는 CDK·AWS CLI·SSM·k6를 하나의 실험 수명주기로 조정하는 실행기다.

```text
TypeScript runner
├─ doctor / build / diff
├─ bootstrap / deploy
├─ status / smoke
├─ capacity / overload
├─ collect
└─ destroy
```

명령형 boto3 스크립트처럼 중간 실패 상태를 수동 복구하지 않는다. `synth`로 템플릿을 만들고 `diff`로 비용 발생 전 변경을 검토한 뒤, `deploy`와 `destroy`를 동일 스택 경계에서 수행한다.

## 실행 순서

반복 로그인 제거를 위한 [GitHub OIDC 전환](stage8c-github-oidc.md)은 AWS 설치·인증 실증까지 완료됐다. 후속 [제한된 배포·자동 회수](stage8c-safe-experiments.md)는 별도 제어 스택과 실행기를 사용한다. 새 배포 경로는 기존 CDKToolkit 관리자 역할을 사용하지 않는다. 로컬 진단에는 여전히 유효한 로컬 인증이 필요하다.

```bash
cd infrastructure/aws-stage8c
npm ci

npm run stage8c -- doctor --profile YOUR_PROFILE
npm run stage8c -- build
npm run stage8c -- diff --profile YOUR_PROFILE
```

`doctor`의 `FreeTierEligible=true`는 인스턴스 유형의 현재 표시일 뿐이다. 계정 생성일, 남은 크레딧, EBS, public IPv4, ECR 저장량을 합쳐 무료를 보증하지 않는다.

최초 한 번 관리자 인증으로 제어 스택을 설치한다. 비용 발생 명령은 `--ack-cost`가 없으면 실행기가 거절한다. 아래 안전성 시험은 EC2를 생성하지 않는다.

```bash
AWS_PROFILE=YOUR_PROFILE npm run experiment-control -- install --ack-cost
AWS_PROFILE=YOUR_PROFILE npm run experiment -- safety-check

# 안전성 시험 → 배포 → 워밍업 → 정식 smoke → 반드시 회수 시도
AWS_PROFILE=YOUR_PROFILE npm run experiment -- run --ack-cost
```

새 실행기는 우선 smoke를 한정된 수명주기로 수행한다. 아래 capacity/overload는 기존 저수준 명령이다. 더 긴 시험을 자동 수명주기에 연결하고 종료·회수 시간 예산을 검토하기 전에는 별도 배포를 유지하며 실행하지 않는다.

```bash
npm run stage8c -- capacity --profile YOUR_PROFILE
npm run stage8c -- overload --profile YOUR_PROFILE
npm run stage8c -- collect --profile YOUR_PROFILE --label final
```

`capacity`와 `overload`는 실행 전후에 다섯 호스트의 CPU·메모리·Docker 상태와 EC2 CPU credit·네트워크 지표를 `docs/evidence/performance/<date>/`에 남긴다. Grafana는 SSM 터널로만 열며, Metrics → Traces → Logs → Profiles 순서의 병목 분해법과 신호별 인수 조건은 [OpenTelemetry OSS 관찰성 스택](../../../observability/README.md)을 따른다.

새 실행기는 실험 종료 즉시 회수를 요청한다. 수동 재시도는 실제 실행 ID를 지정한다.

```bash
AWS_PROFILE=YOUR_PROFILE npm run experiment -- cleanup --ack-cost --run-id=rtb-실제실행ID
```

기존 `CDKToolkit`의 계정 공통 ECR·S3는 건드리지 않는다. 새 실험 assets는 `RtbStage8cControl`의 전용 저장소에 RunId별로 게시하고 회수기가 해당 실행의 이미지·파일만 삭제한다. 전용 저장소와 회수기 자체는 다음 실험을 위해 남는다.

## 프리티어 해석 주의

2025년 7월 15일 이후 생성 계정은 최대 200달러 크레딧·6개월 무료 플랜 방식이고, 이전 계정은 기존 12개월 EC2 사용량 방식이다. 이 스택의 기본 `t4g.small`은 신규 프로그램에서는 Free Tier 표시 대상이지만, 구 프로그램의 750시간 대상은 아니다. 다섯 인스턴스를 함께 켜두는 시간만큼 계산되므로 실험 시간만 배포한다.

또한 public IPv4는 현재 주소당 시간당 0.005달러다. 다섯 대를 2시간 사용하면 IPv4만 약 0.05달러이며, 여기에 EC2·EBS·ECR 요금 또는 크레딧 차감이 더해진다.

검토 근거:

- [AWS Free Tier 2025년 7월 변경](https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/)
- [EC2 계정 생성일별 Free Tier 차이](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html)
- [VPC public IPv4 가격](https://aws.amazon.com/vpc/pricing/)
- [T 계열 CPU credit 작동](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-credits-baseline-concepts.html)
- [AWS CDK 부트스트랩 자원](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html)
