# Stage 8C AWS 분리 호스트 배포 자동화

상태: 인프라·시험 대역·최소 OpenTelemetry 스택·실행기 구현 완료 · 실제 AWS 배포 전

## 목적과 판정 경계

로컬 Stage 8C에서 한 호스트가 공유하던 부하 발생기, SSP, 프로젝트 DSP를 서로 다른 EC2로 분리한다. 관찰 백엔드도 측정 대상의 CPU·메모리를 침범하지 않도록 별도 EC2로 분리한다. 최종 운영 아키텍처를 재현하는 배포가 아니라, 다음 병목 분해를 결정하는 임시 성능 실험 셀이다.

```text
AWS VPC 10.42.0.0/24
├─ loadgen  10.42.0.10  k6
├─ ssp      10.42.0.20  SSP JVM
├─ dsp      10.42.0.30  프로젝트 DSP JVM
├─ support  10.42.0.40  인증 게이트웨이 + 외부 DSP A/B + PostgreSQL 3개
└─ observer 10.42.0.50  OTel Collector + Prometheus + Tempo + Grafana
```

- loadgen·SSP·DSP는 서로 CPU·메모리를 공유하지 않는다.
- support의 세 DB는 한 EC2의 CPU·EBS를 공유한다. support가 먼저 포화되면 이 실험은 앱 한계가 아니라 DB 분리를 요구하는 증거다.
- 각 호스트의 Collector는 host metrics를 노출하고 SSP·DSP의 Java Agent telemetry를 batch·retry한다. Observer의 Prometheus가 이를 scrape하고 Tempo가 trace를 보존한다.
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

```bash
cd infrastructure/aws-stage8c
npm ci

npm run stage8c -- doctor --profile YOUR_PROFILE
npm run stage8c -- build
npm run stage8c -- diff --profile YOUR_PROFILE
```

`doctor`의 `FreeTierEligible=true`는 인스턴스 유형의 현재 표시일 뿐이다. 계정 생성일, 남은 크레딧, EBS, public IPv4, ECR 저장량을 합쳐 무료를 보증하지 않는다.

최초 한 번만 부트스트랩한다. 비용 발생 명령은 `--ack-cost`가 없으면 실행기가 거절한다.

```bash
npm run stage8c -- bootstrap --profile YOUR_PROFILE --ack-cost
npm run stage8c -- deploy --profile YOUR_PROFILE --ack-cost
npm run stage8c -- status --profile YOUR_PROFILE
npm run stage8c -- smoke --profile YOUR_PROFILE
```

smoke 통과 후에만 합격 시험을 실행한다.

```bash
npm run stage8c -- capacity --profile YOUR_PROFILE
npm run stage8c -- overload --profile YOUR_PROFILE
npm run stage8c -- collect --profile YOUR_PROFILE --label final
```

`capacity`와 `overload`는 실행 전후에 다섯 호스트의 CPU·메모리·Docker 상태와 EC2 CPU credit·네트워크 지표를 `docs/evidence/performance/<date>/`에 남긴다.

실험 종료 즉시 스택을 회수한다.

```bash
npm run stage8c -- destroy --profile YOUR_PROFILE --ack-cost
```

CDK bootstrap의 ECR·S3 자원은 `RtbStage8c` 스택이 아니라 `CDKToolkit`에 남는다. 다른 CDK 배포에도 쓰는 계정 공통 자원이므로 실행기가 자동 삭제하지 않는다.

## 프리티어 해석 주의

2025년 7월 15일 이후 생성 계정은 최대 200달러 크레딧·6개월 무료 플랜 방식이고, 이전 계정은 기존 12개월 EC2 사용량 방식이다. 이 스택의 기본 `t4g.small`은 신규 프로그램에서는 Free Tier 표시 대상이지만, 구 프로그램의 750시간 대상은 아니다. 다섯 인스턴스를 함께 켜두는 시간만큼 계산되므로 실험 시간만 배포한다.

또한 public IPv4는 현재 주소당 시간당 0.005달러다. 다섯 대를 2시간 사용하면 IPv4만 약 0.05달러이며, 여기에 EC2·EBS·ECR 요금 또는 크레딧 차감이 더해진다.

검토 근거:

- [AWS Free Tier 2025년 7월 변경](https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/)
- [EC2 계정 생성일별 Free Tier 차이](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html)
- [VPC public IPv4 가격](https://aws.amazon.com/vpc/pricing/)
- [T 계열 CPU credit 작동](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-credits-baseline-concepts.html)
- [AWS CDK 부트스트랩 자원](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html)
