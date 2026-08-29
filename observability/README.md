# 최소 OpenTelemetry OSS 스택

## 결정한 범위

이 디렉터리는 Stage 8C가 자동 계측으로 JVM·HTTP·JDBC·호스트 병목을 구분하는 데 필요한 최소 파이프라인만 제공한다.

```text
SSP / DSP JVM
└─ OpenTelemetry Java Agent
   └─ host-local OpenTelemetry Collector
      ├─ metrics ─▶ Prometheus
      └─ traces  ─▶ Tempo
                       │
                       ▼
                     Grafana
```

| 포함 | 현재 제외 |
|---|---|
| Java Agent 자동 계측 | 애플리케이션 로그 수집 |
| JVM·HTTP·JDBC metrics와 traces | Loki 또는 ELK |
| Collector host metrics | Alertmanager |
| Prometheus 24시간 보존 | Pyroscope 지속 프로파일링 |
| Tempo monolithic 24시간 보존 | 도메인 수동 계측 |
| Grafana Explore | 공개 Grafana endpoint |

로그는 컨테이너 stdout에 남고 `OTEL_LOGS_EXPORTER=none`으로 유지된다. Profiles는 병목이 좁혀졌을 때 JFR로 별도 수집한다. 자동 계측만으로는 경매 결과·no-bid 사유·과금 불변식을 알 수 없으므로 이들은 후속 도메인 계측 범위다.

## 로컬 수직 검증

설정 파일만 검증한다.

```bash
./observability/verify.sh
```

실제 파이프라인을 기동하고 metrics scrape와 trace ingest까지 검증한다.

```bash
docker compose -f observability/compose.yml up -d
./observability/verify.sh --running
```

- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`
- Tempo: `http://127.0.0.1:3200`
- 로컬 OTLP: `127.0.0.1:4317` 또는 `127.0.0.1:4318`

로컬 스택은 모든 포트를 loopback에만 바인딩한다. 종료할 때 데이터를 보존하려면 `docker compose -f observability/compose.yml down`을 사용하고, 실험 데이터를 함께 지울 때만 `-v`를 추가한다.

## AWS Stage 8C 배포 설정

AWS에서는 각 호스트의 Collector가 로컬 Java Agent를 받아 retry와 batch를 담당한다. Observer는 실험 대상의 CPU·메모리와 분리된다.

```text
10.42.0.10 loadgen  ─┐
10.42.0.20 SSP      ─┤
10.42.0.30 DSP      ─┼─ OTLP traces ─▶ 10.42.0.50 Observer
10.42.0.40 support  ─┤                    ├─ Tempo :4317/:3200
10.42.0.50 observer ─┘                    ├─ Prometheus :9090
       ▲                                 └─ Grafana :3000
       └──────── Prometheus scrape :9464 ────────────────┘
```

- `4317`: 실험 역할 Security Group에서 Observer로만 허용
- `9464`: Observer에서 각 실험 역할로만 허용
- `3000`, `9090`, `3200`: 인터넷 ingress 없음
- trace sampling: parent-based 10%
- metric export/scrape: 5초
- metrics/traces retention: 24시간

실제 자원을 만들기 전 로컬 검증과 CloudFormation 합성을 수행한다.

```bash
cd infrastructure/aws-stage8c
npm run stage8c -- build
npm run stage8c -- diff --profile YOUR_PROFILE
```

배포한 이후의 검증과 Grafana 접근은 다음 명령으로 준비되어 있다.

```bash
npm run stage8c -- status --profile YOUR_PROFILE
npm run stage8c -- observability --profile YOUR_PROFILE
npm run stage8c -- grafana-tunnel --profile YOUR_PROFILE
```

터널이 열린 동안 로컬 브라우저에서 `http://127.0.0.1:3000`으로 접근한다. `grafana-tunnel`에는 AWS Session Manager plugin이 필요하다.

## 배포 후 최소 합격 조건

1. 다섯 Collector health endpoint가 정상이다.
2. Prometheus targets에서 다섯 호스트가 모두 `up`이다.
3. SSP와 DSP service가 Grafana Explore의 Tempo에 나타난다.
4. SSP 요청 trace와 DSP/JDBC 하위 span이 동일 trace로 연결된다.
5. Java Agent를 켠 상태와 끈 상태의 동일 부하를 비교해 p99와 최대 처리량 오버헤드를 기록한다.

4번은 자동 계측 라이브러리 호환성과 context propagation을 실제 요청으로 확인하는 인수 조건이다. 설정 파일과 CDK 합성만으로는 보장할 수 없다.

