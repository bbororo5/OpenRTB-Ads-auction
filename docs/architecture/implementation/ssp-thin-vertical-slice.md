# SSP 얇은 수직 흐름 구현 계획

상태: 얇은 수직 흐름 0~5단계 완료 · 컴포넌트 강화 1~8단계 완료

이 문서의 0~5단계 완료는 SSP 전체 구현 완료가 아니다. 한 번 관통하는 실제 기술 흐름을 만든 상태이며, 이제 8개 컴포넌트의 정상·경계·실패 계약을 하나씩 강화한다.

목표는 [SSP E2E 인수 시나리오](../../../ssp-app/src/test/java/com/bbororo/rtb/ssp/e2e/SspAuctionBillingE2eTest.java)를 녹색으로 만드는 것이다. 단계 1~4에서는 각 컴포넌트와 경계의 단위·통합 시험으로 규칙을 검증하고, 네 결과가 모두 연결된 뒤에만 SSP 전체 E2E를 실행한다.

```text
① 신뢰된 요청 입장
        ↓
② 낙찰·증표 반환
        ↓
③ 청구·전달 작업 확정
        ↓
④ burl 전달 완료
```

5단계는 이 흐름을 만든 뒤 남은 시험용 어댑터를 실제 기술로 교체하는 작업이다.

## 전체 지도

| 단계 | 단계 완료 시 보장하는 사실 | 참여 컴포넌트 | 구현 경계 |
|---|---|---|---|
| 0. 설정 제어 | 지역 SSP가 완결된 공급자 설정 버전을 읽을 수 있다 | 공급자 신뢰 스냅숏 제어 경로 | PostgreSQL 논리 복제·10초 지역 스냅숏 |
| 1. 입장 | 신뢰된 공급자 요청만 경매에 들어간다 | 경매·렌더링 API, 경매 중복 방지 | 5초 로컬 single-flight |
| 2. 경매 | DSP 입찰 중 1가격 낙찰 결과가 나온다 | 경매 조정, DSP 입찰 실행, 낙찰 결정 | OpenRTB HTTP fan-out·절대 기한 |
| 3. 청구 | 증표를 받은 렌더링 완료가 청구와 전달 작업으로 확정된다 | 렌더링 증표, 렌더링 청구 | AES-GCM 증표·PostgreSQL 원자 기록 |
| 4. 전달 | 대기 작업이 DSP의 `burl` 호출로 종결된다 | DSP 통지 전달 | 작업 임대·백그라운드 재시도 |
| 5. 강화 | 같은 흐름이 실제 저장소·통신·암호화에서 유지된다 | 모든 컴포넌트의 어댑터 | Java 21 HTTP 런타임·전체 시험 |

## 0. 설정 제어 — 완결된 공급자 설정을 지역에 준비하기

```text
서울 PostgreSQL 설정 원본
  → 도쿄 PostgreSQL 논리 복제
  → 지역 설정 읽기
  → ProviderTrustSnapshot 원자 교체
```

| 항목 | 내용 |
|---|---|
| 데이터 모델 | `provider_config_head` → `provider_config_version` → `provider_policy` → `provider_key` |
| 관계 보장 | 원본과 도쿄 복제본 모두 외래 키로 설정 버전·공급자·키의 참조 무결성을 강제한다 |
| 발행 규칙 | 새 버전·정책·키 전체와 활성 버전 포인터 갱신을 하나의 트랜잭션으로 커밋한다 |
| 애플리케이션 포트 | `ProviderTrustSnapshot.version`, `permits`, `isActive` |
| 개발 환경 | Docker Compose의 서울 publisher와 도쿄 subscriber를 PostgreSQL 논리 복제로 연결한다. 공통 DDL, publication·subscription 초기화, 서울 발행→도쿄 수신 검증까지 포함한다. |
| 현재 구현 | 주입받은 지역 `DataSource`만 쓰는 PostgreSQL 설정 읽기, 불변 `ProviderTrustSnapshot` 조립, 더 새 완결 버전만 공개하는 원자 교체를 구현한다. 코드에 특정 리전 이름·원격 DB 선택 규칙은 없다. |
| 갱신 규칙 | 시작 뒤 0~10초 무작위 지연을 둔 뒤, 작업 완료 후 10초를 기다리는 fixed-delay 방식으로 지역 DB의 `active_version`만 확인한다. 더 새 버전일 때만 전체 스냅숏을 읽고 원자 교체하며, 실패하면 기존 스냅숏을 유지한다. |
| 시작 규칙 | `ProviderTrustControlPlane.startFromEnvironment()`은 HTTP 포트를 열기 전에 활성 스냅숏을 읽는다. 실패하면 DB 자원을 닫고 예외를 전파하므로 SSP는 시작하지 못한다. |
| 완료 조건 | 완료. 1단계에서 `SspApplication`이 이 제어 경로를 먼저 시작하고, 경매 API에는 `trustSnapshot()`만 전달한다. |

정확한 칼럼·기본 키·외래 키는 [데이터 접근·보존 기준](../views/data.md#6-공급자-설정-제어-모델)에 둔다.
로컬 구동과 복제 검증 방법은 [공급자 설정 논리 복제 개발 환경](../../../infrastructure/postgres/provider-config/README.md)에 둔다.

## 1. 입장 — 신뢰된 요청만 경매에 넣기

```text
지역 L7 진입 계층이 ADR-009에 따라 요청 소유 SSP로 전달
  → AuctionRenderApi.auction(AuctionRequest)
  → ProviderTrustSnapshot.permits(providerId, keyId)
  → AuctionDeduplicator.execute(AuctionRequest, AuctionStarter)
  → 최초 요청만 StartAuction
  → 중복 요청은 같은 CompletionStage 결과를 공유
```

| 항목 | 내용 |
|---|---|
| 참여 컴포넌트 | 경매·렌더링 API, 경매 중복 방지 |
| 제어 경로 포트 | `ProviderTrustSnapshot.permits`, `ProviderTrustSnapshot.isActive` |
| 라우팅 전제 | 지역 L7 진입 계층이 `AuctionRequestKey(providerId, providerRequestId)`로 Rendezvous Hash를 적용해 요청 소유 SSP에 전달한다. 이 단계는 그 결정을 다시 하지 않는다. |
| 입력 메시지 | `AuctionRequest`, 서버가 계산한 요청 지문 |
| 출력 메시지 | 최초 요청의 `StartAuction`, 또는 기존 경매의 동일 완료 결과 |
| 현재 구현 | 5초 인메모리 single-flight, 기본 최대 10,000개 항목, 만료 우선순위 큐 |
| 완료 조건 | 완료. 신뢰되지 않은 공급자는 경매 조정에 도달하지 않는다. 같은 키·지문은 DSP 호출, 통지와 증표 발급을 다시 실행하지 않고 최초의 완성된 `AuctionResult`를 재사용한다. 같은 키의 다른 지문은 거부하며, 호출자 한 명의 취소는 공유 실행을 취소하지 않는다. 용량이 가득 차면 기존 키는 계속 처리하고 새 키만 빠르게 실패한다. |

## 2. 경매 — DSP 응답에서 낙찰 만들기

```text
AuctionCoordinator.runAuction(StartAuction)
  → DspBidExecutor.requestBids(BidRequestBatch)
  → WinnerSelector.selectWinners(auctionId, AuctionRequest, BidResponses)
  → AuctionOutcome
```

| 항목 | 내용 |
|---|---|
| 참여 컴포넌트 | 경매 조정, DSP 입찰 실행, 낙찰 결정 |
| 호출 인터페이스 | `runAuction`, `requestBids`, `selectWinners` |
| 입력 메시지 | `StartAuction`, `BidRequestBatch`, `BidResponses` |
| 출력 메시지 | `AuctionOutcome` |
| 현재 구현 | DSP별 OpenRTB 2.6 HTTP 요청을 동시에 보내고, 응답·무응답·오류를 DSP 단위로 격리한다. 단조 시계의 절대 기한은 DSP 호출뿐 아니라 낙찰 선택·증표 발급·통지 작업 제출까지 감싼다. |
| 완료 조건 | 완료. 한 DSP의 시간 초과·오류가 다른 유효 입찰을 버리지 않는다. 절대 마감 안의 유효 입찰만 1가격·결정적 동점 규칙으로 비교하며, 전체 기한을 넘긴 내부 작업은 광고 없음이 아닌 기술 실패로 종결·중단한다. `nurl`·`lurl` 작업 제출 실패는 단발 최선 노력 계약에 따라 완성된 경매 결과를 되돌리지 않는다. |

## 3. 청구 — 증표를 내구 작업으로 바꾸기

```text
ProofIssuance
  → RenderProofService.issue(...)
  → RenderProof
  → RenderProofService.verify(RenderCompleted)
  → VerifiedRender
  → RenderClaimService.acceptRender(...)
  → ClaimDeliveryStore.recordClaimAndScheduleDelivery(...)
```

| 항목 | 내용 |
|---|---|
| 참여 컴포넌트 | 렌더링 증표, 렌더링 청구 |
| 저장소 포트 | `ClaimDeliveryStore.recordClaimAndScheduleDelivery` |
| 입력 메시지 | `ProofIssuance`, `RenderCompleted`, `VerifiedRender` |
| 출력 메시지 | `RenderProof`, `RenderAcceptance`, `BillingClaim`, `BillingDeliveryTask` |
| 현재 구현 | 버전·키 ID를 가진 이진 증표가 공급자·요청·슬롯·낙찰 가격·DSP `burl`·2초 기한을 AES-GCM으로 봉인한다. PostgreSQL 한 행이 청구 근거와 전달 작업을 함께 보존한다. |
| 완료 조건 | 완료. 유효하고 현재 활성인 공급자에 귀속된 증표만 `ACCEPTED`가 된다. 같은 증표의 재전송은 `DUPLICATE`, 다른 증표가 같은 `slotAuctionKey`를 주장하면 `REJECTED`이며 청구와 전달 작업은 정확히 1개만 생긴다. |

## 4. 전달 — 대기 작업을 `burl`로 종결하기

```text
DspNotificationDelivery.deliverDueBilling(now)
  → ClaimDeliveryStore.leaseDueDelivery(now)
  → DSP burl 호출
  → ClaimDeliveryStore.completeOrReleaseDelivery(...)
```

| 항목 | 내용 |
|---|---|
| 참여 컴포넌트 | DSP 통지 전달 |
| 저장소 포트 | `leaseDueDelivery`, `completeOrReleaseDelivery` |
| 입력 메시지 | `BillingDeliveryTask`, `DeliveryLease` |
| 출력 메시지 | `DeliveryOutcome.DELIVERED` |
| 현재 구현 | PostgreSQL `FOR UPDATE SKIP LOCKED` 작업 임대, 세대번호, 제한된 병렬 실행기와 Java 21 HTTP 통지 클라이언트 |
| 완료 조건 | 완료. HTTP 성공은 종결한다. `408`·`429`·`5xx`·통신 실패는 50ms부터 최대 500ms까지 지수 지연해 재시도하되, 각 호출 제한시간과 다음 재시도는 5초 기한을 넘지 않는다. 오래된 작업 세대의 결과는 현재 작업을 덮어쓰지 않는다. |

이 시점에 네 결과를 연결한 SSP 전체 E2E가 통과한다.

## 5. 강화 — 시험용 어댑터를 실제 기술로 교체하기

| 기존 시험 경계 | 실제 어댑터 | 관련 인터페이스 | 상태 |
|---|---|---|---|
| 메모리 공급자 스냅숏 | PostgreSQL 논리 복제 사본을 읽어 원자 교체 | `ProviderTrustSnapshot` | 완료 |
| 고정 DSP 응답 | HTTP/JSON OpenRTB 2.6 fan-out·통지 클라이언트 | `DspBidExecutor`, `DspNotificationDelivery` | 완료 |
| 시험·런타임 공통 증표 | 버전형 이진 AES-GCM·Base64URL 증표 | `RenderProofService` | 완료 |
| 인메모리 청구 저장소 | PostgreSQL 원자 기록·작업 임대 | `ClaimDeliveryStore` | 완료 |
| 동기 `burl` 호출 | 백그라운드 실행기와 제한 재시도 | `DspNotificationDelivery` | 완료 |
| Java 내부 진입 | 공급자 경매·렌더링 HTTP/JSON 서버 | `AuctionRenderApi` | 완료 |

## 다음 작업: 컴포넌트별 강화

공통 계약 정리를 먼저 끝낸 뒤, 외부 I/O가 없는 핵심 정책에서 바깥 경계 순으로 진행한다.

| 순서 | 대상 | 핵심 완료 조건 | 상태 |
|---|---|---|---|
| 준비 | 공통 메시지 계약 | 식별자·가격·기한·중복 의미와 외부 표현 경계 일치 | 완료 |
| 1 | 낙찰 결정 | 유효성·최저가·1가격·응답 순서와 무관한 분산 동점 규칙 | 완료 |
| 2 | 렌더링 증표 | 발급·변조·슬롯·리전 귀속·재사용·키 교체·기한 계약 | 완료 |
| 3 | DSP 입찰 실행 | DSP별 시간 초과·불량 응답·연결 실패 격리 | 완료 |
| 4 | 렌더링 청구 | 슬롯별 멱등성과 저장 실패 응답 계약 | 완료 |
| 5 | DSP 통지 전달 | 임대·재시도·기한·늦은 작업자 결과 방어 | 완료 |
| 6 | 경매 중복 방지 | 만료·충돌·동시 최초 실행, 최종 응답·증표 재사용과 메모리 상한 | 완료 |
| 7 | 경매 조정 | 절대 기한과 부분 실패 아래 전체 결과 조립 | 완료 |
| 8 | 경매·렌더링 API | 외부 오류 표현·요청 제한·실행 경계 | 완료 |

8단계는 경매·렌더링 요청에 `POST application/json`을 강제하고, 경매 64KiB·렌더링 8KiB의 본문 상한과 공유 동시 처리 128개를 적용한다. 같은 키의 내용 충돌은 `409`, 본문 초과는 `413`, 진입·중복 상태 포화는 `503`, 전체 경매 기한 초과는 `504`로 구분한다. 제한을 넘긴 요청은 API 실행 전에 실패한다.

## 구현 원칙

- 한 단계는 자기 완료 조건을 시험으로 증명한 뒤 다음 단계로 넘어간다.
- 1~4단계에서는 포트와 메시지의 의미를 바꾸지 않는다. 기술 교체는 5단계의 책임이다.
- 실제 어댑터를 도입할 때만 새 기술의 실패·지연·자원 격리 기준을 추가로 결정한다.
