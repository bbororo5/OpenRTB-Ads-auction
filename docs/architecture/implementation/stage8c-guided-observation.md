# 참여형 관찰 실험

2026-09-20: 구현·로컬 검증 완료. AWS observe 모드와 승인 workflow의 end-to-end 실증은 아직 하지 않았다. 기존 자동 smoke와 달리 관찰자의 명시적 승인 전에는 부하를 시작하지 않는다.

## 실행 순서

1. GitHub 인증과 **로컬 Grafana 터널용 AWS 인증**을 먼저 확인한다. SSM plugin도 필요하다. 화면에 접속할 수 없는 상태에서 유료 서버를 먼저 만들지 않는다.
2. 검토한 커밋을 main에 반영한 뒤 `Stage8C bounded experiment`를 `mode=observe`, 비용 승인으로 시작한다.
3. Control 생성/안전 시험/5-host 배포/관찰성 상태 검사 후 `OBSERVATION_GATE`가 나온다. 화면 확인 대기 시간은 최대 5분이다.
4. `stage8c grafana-tunnel --profile stage8c`로 SSM 터널을 열고 `http://127.0.0.1:3000`을 연다. Grafana datasource는 Prometheus·Tempo·Loki·Pyroscope다. 실시간 데이터 유입과 화면의 시간 범위를 확인하고, 어떤 요청/자원 지표를 볼지 설명한다.
5. 사용자가 화면을 보고 준비됐다고 말한 뒤에만 `screen-ready`를 승인한다. AI가 배포 완료를 화면 확인으로 대신 간주하지 않는다.
6. 별도 숨은 warmup 없이 **10 RPS × 60초 한 번** 실행한다. 이것은 cold-start가 포함된 첫 관찰 자료이며, 기존 warmed smoke와 직접 비교하지 않는다. k6 결과·host 표본·gateway/DB 스냅샷을 수집한다.
7. `OBSERVATION_RESULT`와 `review-done` 대기가 나온다. 사용자가 관찰을 먼저 말하고 AI는 한 번에 질문 하나만 한다. 결과 검토는 최대 5분이다.
8. 관찰 종료 시 `review-done` 승인 → 즉시 철거. 응답 없음/시간 만료/인프라 오류도 finally 철거로 진행한다. 결과가 SLO를 위반하면 관찰 시간을 제공하되 최종 실행은 실패로 남긴다.

```bash
# 배포: GitHub OIDC이므로 로컬 AWS 인증을 사용하지 않음
gh workflow run stage8c-experiment.yml --ref main \
  -f mode=observe -f acknowledge_cost=true

# 화면 연결: 로컬 AWS 인증 필요, root 대신 제한된 관찰 역할이 후속 과제
cd infrastructure/aws-stage8c
npm run stage8c -- grafana-tunnel --profile stage8c

# 사용자의 명시적 화면 확인 후. 아래 ID는 실제 실행 ID로 교체.
gh workflow run stage8c-observation-approve.yml --ref main \
  -f run_id=rtb-gh-ACTUAL_RUN_ID-1 -f phase=screen-ready

# 관찰 종료 후
gh workflow run stage8c-observation-approve.yml --ref main \
  -f run_id=rtb-gh-ACTUAL_RUN_ID-1 -f phase=review-done
```

## 안전 경계

- 기존 40분 실험 lease와 독립 회수기는 유지한다. 대기 종료 시각은 `min(지금+5분, lease만료-5분)`이며 승인으로 연장하지 않는다.
- RunId·phase·새 nonce가 일치하는 승인만 허용한다. 열리지 않은 단계, 이전 실행의 승인, 만료된 승인은 부하를 시작할 수 없다. 승인 job은 기존 main 신뢰 경계 안에서 실행된다.
- 승인 신호는 기존 전용 S3의 `<RunId>/observation/` 아래에 저장되어 실험 assets와 함께 삭제된다. 키나 토큰을 로컬로 전달하지 않는다. IAM 권한 확대 없음.
- 승인 workflow는 실험 workflow와 다른 concurrency group을 사용한다. 실험 뒤에 대기해서 서로 막히는 구조를 피한다.
- 승인 확인은 최대 5분 동안 5초 간격의 S3 조회다. 애플리케이션 hot path/DB polling과 별개의 운영 제어이며 S3 요청 비용은 발생할 수 있다. 읽기 권한/네트워크 오류는 무한 대기하지 않고 실패 처리한다.
- 사용자 확인은 workflow 실행 요청이지 즉시 시작 보장이 아니다. GitHub runner 대기가 길면 승인 시간창을 놓칠 수 있으며, 이때는 부하 없이 철거한다.
- 프로파일/trace/log 저장소 전체 백업을 추가한 것은 아니다. 철거 후 남는 자료는 기존 수집기와 실행 결과 파일이며, 전체 Grafana 세션을 재생할 수 있다고 약속하지 않는다.
- 이번 단계에서는 자동 부하 증가나 원인 수정 없이 관찰부터 한다.

검증: TypeScript build, 인프라 40개 + gateway 4개 테스트 통과. 승인 부재/만료/잘못된 실행/nonce/단계, 조회 중 시간 초과, 취소/읽기 실패, 타임아웃 후 cleanup, workflow 동시성 분리를 검사했다. AWS 승인 전달과 브라우저 화면은 실제 배포에서 추가 검증해야 한다.
