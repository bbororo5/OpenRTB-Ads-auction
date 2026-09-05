# 2026-09-03 용량 시험: 공개 검토한 증거

RunId: `rtb-capacity-20260903a`.

이번 커밋에는 내용을 검토한 다음 10개 자료만 포함한다. 전체 원본 로그와 실행 스크립트는 로컬에 별도로 보존한다.

- `rtb-capacity-20260903a-*-observation.json` 6개: 각 60초 구간의 요청 수, p99, HTTP 상태, DSP 낙찰률과 판정. 실행 순서는 ramp-10 → ramp-25 → refine-1-17 → refine-2-13 → repeat-10 → recovery-10이다. k6 원본 summary를 파싱한 집계이며 시계열 원본은 아니다.
- `rtb-capacity-20260903a-conflict-diagnostic.json`: AWS DB에서 조회한 동일 이벤트 ID·종류의 충돌 집계.
- `rtb-capacity-20260903a-lease-diagnostic.json`: AWS DB의 마지막 lease 발급 시각, 정산 상태, outcome 집계와 조회 당시 DB 세션 상태.
- `rtb-capacity-20260903a-precision-probe.txt`: 실제 로컬 PostgreSQL/JDBC 왕복에서 재현한 시간 정밀도 충돌.
- `rtb-capacity-20260903a-local-expiry-probe.txt`: 아래쪽 시간 반올림을 모델링해 로컬 예산 코드에서 재현한 pending 누적과 설치 거절. AWS 메모리에서 직접 측정한 결과가 아니다.

첫 10 RPS의 집계는 통과했지만 같은 부하의 재시험·복귀 검사는 실패했다. 따라서 10 RPS를 안정적인 처리 한계로 해석하지 않는다. 로컬 재현은 AWS의 전체 인과관계나 수정 효과를 확정하지 않는다.

실험 해석은 [측정 보고서](../../../architecture/implementation/stage8c-2026-09-03-capacity-study.md), 관찰 순서는 [훈련 안내](../../../architecture/implementation/stage8c-observation-training.md)를 참고한다.
