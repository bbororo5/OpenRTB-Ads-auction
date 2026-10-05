# 로컬 관찰 환경 작업 커밋

계획 30개 소작업과 실제 검증에서 발견한 보완 8개를 합쳐 38개 커밋이다.
10월 1~6일별 개수는 5 / 5 / 6 / 5 / 7 / 10개다.
새 커밋의 AuthorDate와 CommitDate만 요청에 따라 배정했고 기존 이력은 재작성하지 않았다.
실제 작업·검증일은 2026-10-06 KST이며, 실행 증거의 시각은 그대로다.

| 순서 | SHA | 배정 날짜 | 변경 |
|---:|---|---|---|
| 1 | 47f2c8c | 2026-10-01 | feat(local): add runtime preflight checks |
| 2 | 3b2f3ac | 2026-10-01 | feat(local): provision isolated Stage8C databases |
| 3 | 52f4fd9 | 2026-10-01 | feat(local): generate reproducible runtime configuration |
| 4 | 8f4c008 | 2026-10-01 | feat(local): connect SSP DSP and support services |
| 5 | b254e88 | 2026-10-01 | feat(local): add runtime lifecycle commands |
| 6 | c56801f | 2026-10-02 | feat(local): integrate shared observability services |
| 7 | dbb201a | 2026-10-02 | feat(local): configure Java agent telemetry identity |
| 8 | 1eb0896 | 2026-10-02 | test(observability): verify local metrics ingestion |
| 9 | 080ca39 | 2026-10-02 | test(observability): verify local trace and log ingestion |
| 10 | 1c67fb7 | 2026-10-02 | feat(local): report telemetry and profiling readiness |
| 11 | e8d3430 | 2026-10-03 | fix(grafana): select scrape jobs by environment |
| 12 | 62a244e | 2026-10-03 | fix(grafana): clarify shared host CPU semantics |
| 13 | 01ba7ef | 2026-10-03 | fix(grafana): scope application panels to selected targets |
| 14 | fcceaa4 | 2026-10-03 | docs(grafana): distinguish server and client measurements |
| 15 | 8487067 | 2026-10-03 | test(grafana): verify local and AWS query compatibility |
| 16 | 6066a1d | 2026-10-03 | fix(local): stream source archives for non-ASCII workspace builds |
| 17 | 8692368 | 2026-10-04 | feat(local): add bounded smoke and observation runs |
| 18 | 4f68ba3 | 2026-10-04 | refactor(evidence): separate reusable request analysis |
| 19 | 0000d23 | 2026-10-04 | feat(local): persist correlated request journals |
| 20 | 2e620a6 | 2026-10-04 | feat(local): export selected traces and metric windows |
| 21 | 5997df7 | 2026-10-04 | feat(local): generate offline evidence reports |
| 22 | b479816 | 2026-10-05 | feat(local): capture bounded runtime diagnostics |
| 23 | 487478e | 2026-10-05 | fix(local): preserve evidence on test failures |
| 24 | 1d124dc | 2026-10-05 | feat(local): add isolated verification sessions |
| 25 | 7b35b6f | 2026-10-05 | fix(local): guarantee bounded verification cleanup |
| 26 | 443a4f2 | 2026-10-05 | test(local): cover interrupted and degraded sessions |
| 27 | fa1e558 | 2026-10-05 | fix(local): isolate public image pulls from desktop credential waits |
| 28 | 0a24859 | 2026-10-05 | fix(local): retain Docker plugins and exclude macOS archive metadata |
| 29 | b3cfc04 | 2026-10-06 | test(local): add end-to-end acceptance checks |
| 30 | 8195163 | 2026-10-06 | test(local): verify repeatability and data preservation |
| 31 | ddb8640 | 2026-10-06 | test(local): validate signal cleanup and updated dashboard contract |
| 32 | 9bec663 | 2026-10-06 | fix(local): retain startup evidence and accommodate backend readiness |
| 33 | f1d6fe1 | 2026-10-06 | fix(evidence): embed run metadata and anchor spans to earliest start |
| 34 | f665537 | 2026-10-06 | refactor(local): separate artifact persistence from capture orchestration |
| 35 | a758ae3 | 2026-10-06 | docs(local): record verified resource usage and results |
| 36 | aa01e2c | 2026-10-06 | docs(local): add operator runbook and evidence walkthrough |
| 37 | 494a88d | 2026-10-06 | fix(local): verify network and volume removal after isolated runs |
| 38 | 이 문서를 추가한 커밋 | 2026-10-06 | docs(architecture): define local and AWS verification boundaries |

마지막 커밋은 로컬/AWS 책임 분리, README 진입점, 최종 검증 결과와 이 목록을 묶는다.

