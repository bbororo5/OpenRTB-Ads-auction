# 참여형 관찰 실험

2026-09-20: 구현·로컬 검증 완료. AWS observe 모드와 승인 workflow의 end-to-end 실증은 아직 하지 않았다. 기존 자동 smoke와 달리 관찰자의 명시적 승인 전에는 부하를 시작하지 않는다.

## 실행 순서

1. Mac의 Tailscale 연결, tailnet HTTPS 활성화, `Stage8C observation identity check (no AWS)` 성공을 먼저 확인한다. 기본 경로에는 로컬 AWS 인증/SSM plugin이 필요 없다. 화면에 접속할 준비가 안 된 상태에서 유료 서버를 먼저 만들지 않는다.
2. 검토한 커밋을 main에 반영한 뒤 `Stage8C bounded experiment`를 `mode=observe`, 비용 승인으로 시작한다.
3. Control 생성/안전 시험/5-host 배포/관찰성 상태 검사 후 `OBSERVATION_GATE`가 나온다. 화면 확인 대기 시간은 최대 5분이다.
4. 로그의 `OBSERVATION_URL https://rtb-observe-….taild7dd00.ts.net`을 Mac 브라우저에서 연다. 사설 HTTPS만 허용하며 Grafana의 원본 3000 포트는 localhost에 유지한다. Grafana datasource는 Prometheus·Tempo·Loki·Pyroscope다. 실시간 데이터 유입과 화면의 시간 범위를 확인하고, 어떤 요청/자원 지표를 볼지 설명한다.
5. 사용자가 화면을 보고 준비됐다고 말한 뒤에만 `screen-ready`를 승인한다. AI가 배포 완료를 화면 확인으로 대신 간주하지 않는다.
6. 별도 숨은 warmup 없이 **10 RPS × 60초 한 번** 실행한다. 이것은 cold-start가 포함된 첫 관찰 자료이며, 기존 warmed smoke와 직접 비교하지 않는다. k6 결과·host 표본·gateway/DB 스냅샷을 수집한다.
7. `OBSERVATION_RESULT`와 `review-done` 대기가 나온다. 사용자가 관찰을 먼저 말하고 AI는 한 번에 질문 하나만 한다. 결과 검토는 최대 5분이다.
8. 관찰 종료 시 `review-done` 승인 → 즉시 철거. 응답 없음/시간 만료/인프라 오류도 finally 철거로 진행한다. 결과가 SLO를 위반하면 관찰 시간을 제공하되 최종 실행은 실패로 남긴다.

```bash
# 배포: GitHub OIDC이므로 로컬 AWS 인증을 사용하지 않음
gh workflow run stage8c-experiment.yml --ref main \
  -f mode=observe -f acknowledge_cost=true

# AWS 자원 생성 없이 신뢰 관계만 검사
gh workflow run stage8c-observation-check.yml --ref main

# 화면 연결: Mac Tailscale 연결 후 실제 OBSERVATION_URL을 브라우저에서 연다.
# 기존 grafana-tunnel은 수동 진단용으로만 남아 있으며 로컬 AWS 인증이 필요하다.

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

## 사설 관찰 연결 (2026-09-20 추가)

```text
GitHub main OIDC → Tailscale 단기 API token → 10분 만료·1회용·ephemeral 등록키
                                            ↓ RSA-OAEP SHA256 암호화
기존 AWS SSM → observer의 /run 임시 파일 → Tailscale userspace 컨테이너
Mac Tailscale → private HTTPS:443 → observer localhost:3000 → Grafana Viewer
관찰 종료/오류 → logout·컨테이너 제거 → (실패해도) AWS 철거
```

- `lib/observation-identity.ts`: 공개 client ID/audience, OIDC 교환, 등록키 생성/폐기. 장기 secret을 GitHub나 로컬에 저장하지 않는다. 토큰은 메모리에만 두고 GitHub 로그에 마스킹한다. HTTP 오류 본문은 출력하지 않는다.
- `lib/observation-access.ts`: 공식 Tailscale v1.102.4 이미지의 manifest digest 고정. 공개키는 인증된 기존 SSM 경로로 받아 검증하고, 평문 키 대신 암호문만 SSM 명령에 넣는다. Node crypto/OpenSSL의 표준 RSA-OAEP SHA256을 사용하며 별도 암호 알고리즘은 만들지 않는다. 호스트 root/SSM 관리자에 대한 방어는 아니며 명령 이력의 평문 유출을 줄이는 경계다.
- 비밀 파일은 observer의 `/run/rtb-observe-<run hash>/`에 0700/0600으로만 저장하고 인증 직후 제거한다. daemon 상태/인증서 cache는 컨테이너 메모리에만 둔다. host networking은 localhost Grafana 프록시를 위한 것이며 TUN/NET_ADMIN/privileged/공인 ingress는 추가하지 않는다.
- `lib/observation-remote.ts`: 기존 SSM 권한만 사용. 원격 실행에 제한 시간을 두고 명령/오류 출력에서 credential 내용을 노출하지 않는다.
- `scripts/experiment.ts`: 연결 성공 후에만 screen-ready 대기 시작. logout 실패도 AWS cleanup을 막지 않는다. runner 강제 소실 시 AWS reaper가 비용 자원을 회수하고, offline ephemeral 노드는 Tailscale에서 나중에 정리된다. 즉시 tailnet 목록 삭제까지 보장하는 것은 아니다.
- tailnet 정책은 본인 → `tag:rtb-observer` TCP443만 허용한다. 태그 장비에서 개인 기기로 향하는 접근, observer SSH/DB/3000, 인터넷 Funnel은 허용하지 않는다.
- HTTPS 활성화는 관리자 1회 설정이다. 발급받는 도메인은 공개 Certificate Transparency 로그에 남으므로 민감한 이름을 hostname으로 쓰지 않는다. 무료 플랜/ephemeral 사용량 한도를 넘기기 위해 유료 전환하지 않는다.
- 신뢰 관계 생성, 로컬 테스트, 실제 OIDC 교환, 실제 AWS 접속은 서로 다른 검증 단계다. 로컬 테스트만으로 실제 Grafana 연결 성공을 선언하지 않는다.

공식 근거: [workload federation](https://tailscale.com/docs/features/workload-identity-federation), [Serve](https://tailscale.com/docs/features/tailscale-serve), [ephemeral nodes](https://tailscale.com/docs/features/ephemeral-nodes).

### 연결 준비 검증 결과

- 2026-09-20 로컬 및 GitHub: TypeScript 검사, 인프라 47개 + gateway 4개 테스트 통과.
- 공식 pinned 이미지의 ARM64 컨테이너를 네트워크 차단/읽기 전용 rootfs/전체 capability 제거 상태에서 실제 기동했다. userspace daemon socket과 `NeedsLogin` 상태를 확인한 후 `--rm`으로 테스트 컨테이너를 제거했다. 실제 tailnet 로그인 검증과는 구분한다.
- [GitHub run 35496989000](https://github.com/bbororo5/OpenRTB-Ads-auction/actions/runs/35496989000): `f5a400e` 기준 SUCCESS. OIDC 교환 → 600초 1회용 ephemeral/tagged auth key 생성 → 폐기까지 성공. AWS API 호출 및 tailnet 장비 등록 없음.
- 아직 미검증: tailnet HTTPS 활성화, EC2에서 암호문 복호화/등록/Serve, Mac 브라우저 접속, 실제 배포 후 logout과 AWS 철거. HTTPS 인증서의 도메인 공개에 대한 사용자 승인 전에는 인증서 기능을 활성화하지 않는다.
