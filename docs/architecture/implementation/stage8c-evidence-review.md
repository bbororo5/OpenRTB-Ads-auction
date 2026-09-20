# Stage 8C: 같은 증거로 관찰 → 가설 → 수정 → 재시험

## 이번 실습에서 확인한 문제

2026-09-20 run `35510949232`, revision `dd2b5f0`의 10 RPS × 60초 cold-start 실행:

| 관찰 | 출처 | 확인한 사실 | 아직 모르는 것 |
|---|---|---|---|
| 요청 601건, HTTP 200 595건 / 504 6건 | k6 summary | 요청 실패 발생 | 실패한 요청의 정확한 시각·지연 구간 |
| p99 185.477134ms | k6 summary | 목표 ≤50ms 미달 | 원인이 초기화·DB·DSP·다른 대기 중 무엇인지 |
| project DSP 낙찰 157/595 = 26.3866% | k6 summary | 전체 실행의 20~28% 기준 통과 | 개별 구간의 변화 |
| gateway bids: 598건, upstream 200 588 / 204 8 / 503 2 | post-budget snapshot | 입찰 경로에 응답 코드 혼재 | SSP의 504 6건과 어느 요청이 대응하는지 |
| gateway notices: 587건, upstream 204 587 | post-budget snapshot | DSP HTTP 204에는 정상 통지 응답이 섞임 | 전체 DSP 204 그래프만으로 입찰 포기 비율을 판단할 수 없음 |

소스: GitHub artifact `stage8c-35510949232-1`의 `2026-09-20/` 디렉토리.
관찰 중 조회한 Prometheus 메모리/GC 값은 이 artifact에 보존되지 않았다.
기존 artifact는 요청 journal·trace 원본을 보존하지 않았으므로 이번 6건의 원인을 복원했다고 주장하지 않는다.
EC2 5대 terminated, workload/control DELETE_COMPLETE 확인. 새 배포 없이 아래 준비를 수행했다.

## 다음 실행의 증거 계약

관찰 모드만 `--request-evidence`를 전달한다. runner는 **10 RPS / 60s** 이외의 요청 기록 실행을 거절한다.
기존 smoke/capacity 램프의 계측 조건은 바꾸지 않는다.

1. k6는 요청마다 W3C `traceparent`를 생성하고 시각, 상태, `http_req_duration`, 계약 유효성, 낙찰 여부를 console 파일에 기록한다.
2. 요청·응답 본문, provider key, render proof는 저장하지 않는다. trace ID는 metric label에 넣지 않는다.
3. journal은 SSM stdout 제한보다 작은 청크로 읽고 전체 요청 수를 summary와 대조한다. 잘린 파일을 정상으로 인정하지 않는다.
4. 실패 우선·느린 요청·빠른 정상 비교 요청으로 최대 10개 trace를 보존한다. Tempo가 200을 반환해도 span이 없거나 ID가 다르면 미확보로 기록한다.
5. 메모리·GC·CPU·HTTP 누적 카운터·서버 p99의 실행 구간 시계열을 보존한다. 빈 결과는 0이 아니라 자료 누락이다.
6. `review.html`과 `review.md`는 같은 journal/summary를 바탕으로 만든다. HTML은 외부 리소스를 요청하지 않아 철거 후에도 읽을 수 있다.

출력(`stage8c-aws-<label>-` 접두사):

- `summary.json`, `result.json`: k6 전체 실행 결과. 진단 수집 전에 먼저 보존.
- `requests.json`: 모든 완료 요청. summary 건수와 일치해야 한다.
- `review.html`, `review.md`: 사용자와 AI가 함께 읽는 보고서. 실패·느린 요청의 시각/상태/지연/trace ID, 선택 trace의 서비스별 span 시간과 부모 ID.
- `traces.json`: 최대 10개 원본 trace 또는 미확보 사유. 전체 분산 trace 연결의 완전성을 보장하는 것은 아니다.
- `metrics.json`: 선택한 5개 쿼리와 UTC 범위, 응답 또는 오류.
- `evidence-manifest.json`: 선택 자료 확보 여부. `complete`는 모든 요청의 모든 trace/log/profile을 의미하지 않는다.
- `evidence-error.json`: journal 회수 실패 등. 진단 실패를 테스트 통과로 바꾸거나 자동 재부하하지 않는다.

SSM/API 오류와 수집량에는 상한이 있다. 기존 240초 stage 제한, 40분 lease, 독립 회수기 및 finally cleanup은 그대로 유지한다.
증거 수집에 실패해도 임의로 서버 수명을 연장하지 않는다. GitHub artifact 보존은 현재 7일이다. 학습에 사용할 자료는 만료 전에 내려받아야 한다.
trace에는 앱이 생성한 속성이 포함될 수 있으므로 원본 artifact를 검토 없이 공개 커밋하지 않는다.

## 화면과 결과를 혼동하지 않기

| 질문 | 함께 볼 자료 |
|---|---|
| 사용자 요청이 실패했는가? | k6 summary/요청 journal이 기준. Grafana 5xx 카운터는 서버측 보조 신호 |
| 언제 실패했는가? | requests.json/review의 정확한 UTC 시각. metric export/집계 간격만큼 시점 오차가 있을 수 있음 |
| p99 기준을 통과했는가? | k6 전체 실행 p99. Grafana의 rolling histogram p99로 대체하지 않음 |
| DSP가 입찰했는가? | 입찰 경로와 통지 경로를 구분한 trace/gateway 자료. 전체 HTTP 204로 판단하지 않음 |
| 어디서 지연됐는가? | 선택 요청 trace와 정상 비교 trace. 부모/자식 시간을 합산하지 않음 |
| 메모리 증가가 회수되는가? | 같은 시간 범위의 메모리와 GC 누적 횟수. 사용량만으로 누수 확정 금지 |

Grafana에는 서버 5xx 누적 카운터, 경로별 서버 p99 추정, GC 횟수를 추가했다.
현재 Grafana는 **k6 전체 결과를 실시간으로 표시하는 대시보드가 아니다**. 정확한 k6 수치는 테스트 후 생성되는 공통 보고서를 함께 열어 확인한다.
`http_route`가 없는 계측에서는 경로 구분이 불가능하며 이를 임의로 입찰 경로로 해석하지 않는다.

## 한 사이클의 진행 및 종료 기준

1. 배포 전 사용자 참여를 확인한다. 이번에는 부하 전 화면에서 패널의 출처/범위를 설명한다.
2. 화면 확인 승인 후 한 번만 부하를 준다. 종료 후 공통 보고서를 내려받아 함께 연다.
3. 서버는 관찰 종료 또는 기존 대기 제한에 따라 철거한다. 분석은 저장 자료로 계속한다.
4. 사용자가 실패 요청 하나를 선택한다. 해당 trace가 없으면 증거 공백으로 인정하고 원인을 지어내지 않는다.
5. 정상 비교 요청과 다른 span을 찾아 가설/대안/반증 조건을 기록한다.
6. 확인 실험을 먼저 정하고 필요한 최소 코드만 수정한다. 아직은 앱 병목을 확정하거나 수정하지 않았다.
7. 같은 부하·cold-start·진단 설정으로 재시험한다. 성공률, p99, 계약/낙찰 기준과 선택 trace를 비교한다.
8. 한 번 통과한 것만으로 한계치나 근본 원인을 확정하지 않는다. 개선/불변/악화를 각각 기록한다.

요청 journal과 sampled trace 강제는 계측 오버헤드를 추가한다. 전/후 비교 모두 같은 진단 설정을 사용한다.
기존 `dd2b5f0` 실행과 계측 조건이 다르므로 직접 성능 개선의 근거로 사용하지 않는다.

검증: `npm run build`, `npm test`, `node --import tsx scripts/check-request-evidence.ts`.
로컬 통합 검증은 pinned k6 1.2.1로 모의 서버 20건 중 의도된 504 4건을 포함해 journal/traceparent 대응을 확인했다.
AWS의 실제 trace 전파·회수, 새 Grafana 패널 렌더링은 다음 제한된 배포에서 추가 검증해야 한다.

공식 API: [k6 console output](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#console-output), [k6 randomBytes](https://grafana.com/docs/k6/latest/javascript-api/k6-crypto/randombytes/), [Tempo trace API](https://grafana.com/docs/tempo/latest/api_docs/).
