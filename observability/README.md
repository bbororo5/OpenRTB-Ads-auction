# OpenTelemetry OSS 관찰성 스택

## 목적과 신호 경계

이 디렉터리는 Stage 8C 분리 호스트 과부하 시험에서 “느리다”를 CPU, JVM, HTTP, JDBC, 저장소 중 어디의 문제인지 분해하기 위한 관찰 파이프라인이다. 자동 수집을 기준선으로 삼고 도메인 수동 계측은 측정 결과가 요구할 때 추가한다.

```text
SSP / DSP JVM ─ Java Agent ─┐
모든 호스트 ─ host metrics ├─ host-local OTel Collector ─┬─ metrics ─▶ Prometheus
JVM logs ───────────────────┤                              ├─ traces  ─▶ Tempo
                            │                              └─ logs    ─▶ Loki
모든 호스트 ─ eBPF profiler┴────── OTLP Profiles ─────────── profiles ─▶ Pyroscope
                                                                             │
                    Grafana Explore ◀─────────────────────────────────────────┘
```

| OTel 개념 | 수집·전파 | 저장·조회 | 성숙도와 해석 |
|---|---|---|---|
| Metrics | Java Agent + Collector `host_metrics` | Prometheus | 안정 신호 |
| Traces | Java Agent + Collector OTLP | Tempo | 안정 신호, parent-based 10% sampling |
| Logs | Java Agent logging bridge + Collector OTLP | Loki | 안정 신호, `trace_id`에서 Tempo로 이동 |
| Profiles | OTel eBPF Profiler 전용 Collector 배포판 | Pyroscope | Alpha 신호, 97 Hz 지속 CPU 프로파일 |
| Baggage | `tracecontext,baggage` propagator | 독립 저장소 없음 | 요청 컨텍스트이며 저장 신호가 아님 |
| Events | Logs 데이터 모델 | Loki | 별도 백엔드가 아니라 특수한 LogRecord로 발전 중 |

Profiles는 아직 일반 Collector의 안정 파이프라인과 같은 수준이 아니다. 그래서 `otel-collector`와 `otel-ebpf-profiler`를 프로세스 수준에서 분리한다. 프로파일러 장애가 metrics·traces·logs 수집을 중단시키지 않으며, 버전은 검증한 `0.159.0`으로 고정한다.

자동 계측만으로 경매 결과, no-bid 사유, 과금 불변식을 알 수는 없다. 이 값들은 병목 위치를 파악한 뒤 필요한 지점에만 수동 계측한다. Baggage에는 개인정보·토큰을 넣지 않는다.

## 디렉터리와 책임

```text
observability/
├─ collector/agent.yaml       # metrics·traces·logs 수신, 가공, 전달
├─ profiler/host.yaml         # eBPF CPU profile 수집과 OTLP 전달
├─ prometheus/
│  ├─ local.yaml              # 로컬 Collector scrape
│  └─ aws-stage8c.yaml        # 다섯 AWS 호스트 scrape
├─ tempo/tempo.yaml           # trace 24시간 보존
├─ loki/loki.yaml             # log 24시간 보존, OTel structured metadata
├─ grafana/provisioning/      # 네 데이터 소스와 signal 간 이동 규칙
├─ compose.yml                # 로컬 실행 토폴로지
├─ Dockerfile                 # CDK가 EC2로 전달하는 설정 자산
└─ verify.sh                  # 정적·실행 중 수직 인수 검증
```

Grafana의 Tempo 데이터 소스는 trace의 `service.name`과 `host.name`을 Pyroscope label에 대응시킨다. Loki는 OTel `trace_id` structured metadata를 Tempo 링크로 사용한다. 따라서 동일 시간대에 metrics → trace → log/profile 순으로 병목을 좁힐 수 있다.

## 로컬 수직 검증

설정 파일과 컨테이너 자산만 검증한다.

```bash
./observability/verify.sh
```

안정 신호 파이프라인을 실행하고 테스트 trace와 log가 실제 저장소에 도착하는지 검증한다.

```bash
docker compose -f observability/compose.yml up -d
./observability/verify.sh --running
```

Linux eBPF 프로파일러까지 기동하고 Pyroscope의 실제 OTLP profile 수신을 검증한다. Docker Desktop에서는 macOS 프로세스가 아니라 Linux VM 안의 프로세스를 관찰한다.

```bash
docker compose -f observability/compose.yml --profile linux-profiles up -d
./observability/verify.sh --running --profiles
```

- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`
- Tempo: `http://127.0.0.1:3200`
- Loki: `http://127.0.0.1:3100`
- Pyroscope: `http://127.0.0.1:4040`
- 로컬 OTLP: `127.0.0.1:4317` 또는 `127.0.0.1:4318`

로컬 서비스 포트는 loopback에만 바인딩한다. 종료할 때 데이터를 보존하려면 `docker compose -f observability/compose.yml down`을 사용하고, 실험 데이터를 지울 때만 `-v`를 추가한다.

## AWS Stage 8C 배포 토폴로지

각 호스트는 애플리케이션과 같은 머신의 Collector로 OTLP를 보낸다. Observer는 저장·조회만 담당하며 앱 CPU·메모리와 분리된다.

```text
10.42.0.10 loadgen  ─┐  :9464 metrics scrape
10.42.0.20 SSP      ─┤  :4317 traces ───────────┐
10.42.0.30 DSP      ─┼─ :3100 logs ─────────────┼─▶ 10.42.0.50 Observer
10.42.0.40 support  ─┤  :4040 profiles ─────────┘   ├─ Prometheus :9090
10.42.0.50 observer ─┘                               ├─ Tempo      :3200
                                                    ├─ Loki       :3100
                                                    ├─ Pyroscope  :4040
                                                    └─ Grafana    :3000
```

- `4317`, `3100`, `4040`: 실험 역할 Security Group에서 Observer로만 허용
- `9464`: Observer에서 각 실험 역할로만 허용
- 앱 포트와 Grafana·저장소 포트: 인터넷 ingress 없음
- metrics export/scrape: 5초
- traces sampling: 10%; metrics·logs·profiles는 별도 확률 축소 없음
- metrics·traces·logs·profiles 보존 목표: 24시간 실험 창
- Grafana 접근: AWS Session Manager 로컬 터널만 사용

실제 자원을 만들기 전에 검증과 CloudFormation 합성을 수행한다.

```bash
cd infrastructure/aws-stage8c
npm run stage8c -- build
npm run stage8c -- diff --profile YOUR_PROFILE
```

배포 후 모든 호스트의 Collector와 profiler, 네 저장소를 확인하고 Grafana 터널을 연다.

```bash
npm run stage8c -- status --profile YOUR_PROFILE
npm run stage8c -- observability --profile YOUR_PROFILE
npm run stage8c -- grafana-tunnel --profile YOUR_PROFILE
```

## 과부하 시험 관찰 순서

1. 부하 전 `collect`로 CPU credit, 메모리, 컨테이너 상태를 기준선으로 남긴다.
2. Grafana에서 Prometheus의 호스트 CPU·메모리와 JVM GC·thread·HTTP/JDBC 지표를 먼저 본다.
3. 지연이 상승한 시간 범위를 Tempo로 좁히고 SSP → DSP → JDBC span 중 늘어난 구간을 찾는다.
4. 같은 `trace_id`의 Loki 로그로 오류·재시도·포화 거절을 확인한다.
5. 같은 서비스·호스트·시간 범위의 Pyroscope flame graph에서 실제 CPU 함수 비용을 확인한다.
6. profiler를 끈 동일 부하를 한 번 더 실행해 관찰 오버헤드를 결과에 함께 기록한다.

이 순서는 RED/USE 방식과 실무의 상관 분석 패턴을 따른다. 한 신호만 보고 원인을 단정하지 않고, 요청 결과 → 자원 포화 → 분산 경로 → 실행 코드 순으로 가설을 좁힌다.

## 배포 후 합격 조건

1. 다섯 Collector와 다섯 eBPF profiler health endpoint가 정상이다.
2. Prometheus targets에서 다섯 호스트가 모두 `up`이다.
3. SSP와 DSP가 Tempo에 나타나고 실제 HTTP/JDBC span이 한 trace로 연결된다.
4. SSP와 DSP 로그가 Loki에 나타나며 trace가 있는 로그에서 Tempo로 이동할 수 있다.
5. Pyroscope가 다섯 호스트의 OTLP profiles를 받고 SSP·DSP CPU flame graph를 조회할 수 있다.
6. Java Agent·profiler를 켠 상태와 끈 상태의 동일 부하를 비교해 p99와 최대 처리량 오버헤드를 기록한다.

설정 검증은 1차 방어선이고, 3~6번은 실제 요청과 부하가 있어야만 판정 가능한 인수 조건이다. 실제 AWS 배포·부하 측정이 끝나기 전에는 Stage 8C 완료로 표시하지 않는다.
