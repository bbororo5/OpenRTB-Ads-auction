# Stage8C 로컬 관찰 실행 안내

## 시작과 종료

저장소 루트에서:

```sh
cd infrastructure/aws-stage8c
npm ci
npm run local -- doctor
npm run local -- up
npm run local -- status
npm run local -- smoke
npm run local -- observe
npm run local -- down
```

Docker Desktop과 Node.js가 필요하다. Compose `include`를 지원하는 버전을 사용한다.
검증 환경은 Node 26 / Compose 5.1.3이었다. Docker에 약 7.75GiB가 할당된 환경에서 검증했다.
이미지 다운로드에는 인터넷이 필요하지만 AWS CLI나 로그인은 필요하지 않다.

`up`은 소스를 빌드하고 DB·앱 준비를 확인한다. 관찰 백엔드는 추가 초기화 시간이 필요할 수 있으므로
`status`에서 확인한다. 첫 빌드는 다운로드 때문에 이후 캐시 빌드보다 오래 걸린다.
`up`으로 만든 수동 환경은 자동 종료하지 않는다. 작업 후 반드시 `down`을 실행한다.
기동 중 실패해도 수동 환경을 정리하려면 `down`을 사용한다.

한 번에 검증하고 자동으로 종료하려면:

```sh
npm run local -- verify
```

고유 프로젝트·포트·임시 볼륨을 만든다. 성공·실패·SIGINT·SIGTERM에서 그 환경만 제거한다.
강제 SIGKILL, 시스템 종료, Docker daemon 중단에서는 정리 코드가 실행될 수 없다.
그 경우 `.local-stage8c/rtb-local-verify-*/session.json`의 정확한 프로젝트를 확인해 잔여물을 정리한다.
다른 프로젝트를 포함한 `docker system prune`이나 일괄 볼륨 삭제는 사용하지 않는다.

## 무엇을 어디서 보는가

- Grafana: <http://127.0.0.1:3000/d/rtb-observation> — 읽기 전용.
- 환경 선택: `otel-collector`. AWS의 `stage8c-hosts`와 구분한다.
- Collector UP: 수집 대상 접근 성공이지 경매 정상 판정이 아니다.
- CPU: Docker Linux VM의 공유 자원이며 SSP 전용 CPU가 아니다.
- JVM: 서비스별 heap/non-heap 사용량이며 컨테이너 RSS와 다르다.
- 서버 p99: rolling histogram 추정치이며 k6 전체 실행 p99와 다르다.
- 데이터 없음: 해당 시계열이 없는 상태다. 장애 0건이라고 단정하지 않는다.

독립 `verify` 환경의 포트는 매번 바뀌며 종료 후 브라우저에서 접근할 수 없다.
종료 후에는 아래 정적 자료를 사용한다.

## 증거를 읽는 순서

명령 마지막에 출력되는 실행 디렉토리는 `.local-stage8c/<project>/runs/<run>/`이다.

1. `result.json`, `summary.json`: k6 종료 코드·HTTP 상태·오류율·p99 확인.
2. `manifest.json`: 요청 기록·선택 trace·지표가 확보됐는지 확인.
3. `review.html`: 실패 또는 느린 요청 하나를 선택하고 같은 trace ID의 span 확인.
4. `requests.json`, `traces.json`: 정상 비교 요청과 서비스·DB 호출 구간 비교.
5. `metrics.json`, `pre-runtime.json`, `post-runtime.json`: 같은 시간대 자원·GC·오류와 대조.
6. `acceptance.json`: `verify`의 환경/증거 판정과 업무 판정을 따로 확인.

HTML은 외부 요청 없이 열 수 있다. 실제 실행 시각·소스·부하 설정은 `run.json`에도 있다.
최대 10개 trace만 저장한다. 전체 로그·프로파일 백업이 아니다.
로그 수집 검증은 합성 로그로 수행하며 실제 앱 로그가 발생했다는 증거와 구분한다.

첫 질문은 **“어떤 요청이 실패했고, 같은 요청의 어느 구간에서 시간이 쓰였나?”**다.
메모리가 증가했다는 사실만으로 누수나 지연 원인을 확정하지 않는다.
원인을 찾은 후에만 한 가지 변경을 하고 같은 준비 조건·부하·관측 설정으로 비교한다.

## 종료 코드와 복구

- `0`: 해당 명령의 검증 통과.
- k6 `99`: 기존 임계값 중 하나 이상 실패. 증거를 먼저 확인한다.
- 다른 nonzero: 준비·수집·도구·종료 실패 가능. 오류와 `failure.json`/`manifest.json`을 확인한다.
- `verify`에서는 업무 시험 실패라도 증거 보존 뒤 자동 정리한다. 정상으로 숨기지 않는다.
- 수동 `down`은 DB와 관찰 데이터 볼륨을 보존한다. 재기동해도 키와 캠페인을 자동 변경하지 않는다.
- 캠페인 만료·checksum 불일치 시 기존 자료를 보존하고 새 `verify` 환경으로 검증한다. 기존 DB를 조용히 재시드하지 않는다.
- 포트 충돌 시 충돌한 프로세스를 먼저 식별한다. 다른 서비스를 자동 종료하지 않는다.

선택적 프로파일러는 `npm run local -- up --profiles`로 켠다. privileged Linux VM 관찰이며
macOS 네이티브 프로세스 관찰이 아니다. 컨테이너 실행만으로 profile 수신 성공을 주장하지 않는다.
이번 인수 검증에서는 이 옵션을 사용하지 않았다.

관련 자료: [실제 검증 결과](stage8c-local-verification.md), [증거 해석 방법](stage8c-evidence-review.md).
