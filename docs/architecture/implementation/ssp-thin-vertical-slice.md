# SSP 얇은 수직 흐름 구현 계획

상태: 구현 순서 확정 · 0단계 데이터 모델 확정

목표는 [첫 E2E 인수 시나리오](../../../ssp-app/src/test/java/com/bbororo/rtb/ssp/e2e/SspAuctionBillingE2eTest.java)를 녹색으로 만드는 것이다. 먼저 공급자 설정 제어 경로를 준비하고, 컴포넌트를 각각 완성하지 않고 아래 네 개의 관찰 가능한 결과를 순서대로 만든다.

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

| 단계 | E2E에서 새로 참이 되는 사실 | 참여 컴포넌트 | 아직 의도적으로 하지 않는 것 |
|---|---|---|---|
| 0. 설정 제어 | 지역 SSP가 완결된 공급자 설정 버전을 읽을 수 있다 | 공급자 신뢰 스냅숏 제어 경로 | 계약 관리 UI·실제 비밀키 발급 |
| 1. 입장 | 신뢰된 공급자 요청만 경매에 들어간다 | 경매·렌더링 API, 경매 중복 방지 | 실제 HTTP·PostgreSQL |
| 2. 경매 | 세 DSP 입찰 중 1가격 낙찰 결과가 나온다 | 경매 조정, DSP 입찰 실행, 낙찰 결정 | 실제 DSP HTTP·OpenRTB 직렬화 |
| 3. 청구 | 증표를 받은 렌더링 완료가 청구와 전달 작업으로 확정된다 | 렌더링 증표, 렌더링 청구 | 실제 AEAD 최적화·PostgreSQL |
| 4. 전달 | 대기 작업이 DSP의 `burl` 호출로 종결된다 | DSP 통지 전달 | 실제 백그라운드 실행기·네트워크 재시도 |
| 5. 강화 | 같은 E2E가 실제 저장소·통신·암호화에서도 통과한다 | 모든 컴포넌트의 어댑터 | 새 업무 규칙 |

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
| 완료 조건 | 지역 SSP가 head가 가리키는 완결 버전 하나만 메모리 스냅숏으로 읽고, 공급자·키 상태를 조회할 수 있다 |

정확한 칼럼·기본 키·외래 키는 [데이터 접근·보존 기준](../views/data.md#6-공급자-설정-제어-모델)에 둔다.

## 1. 입장 — 신뢰된 요청만 경매에 넣기

```text
AuctionRenderApi.auction(AuctionRequest)
  → ProviderTrustSnapshot.permits(providerId, keyId)
  → AuctionDeduplicator.deduplicate(AuctionRequest)
  → StartAuction | ReuseAuctionResult | RejectChangedRequest
```

| 항목 | 내용 |
|---|---|
| 참여 컴포넌트 | 경매·렌더링 API, 경매 중복 방지 |
| 제어 경로 포트 | `ProviderTrustSnapshot.permits`, `ProviderTrustSnapshot.isActive` |
| 입력 메시지 | `AuctionRequest(providerId, providerKeyId, providerRequestId, requestFingerprint, ...)` |
| 출력 메시지 | `StartAuction`, `ReuseAuctionResult`, `RejectChangedRequest` |
| 시험용 구현 | 불변 메모리 공급자 스냅숏, 5초 인메모리 중복 방지 |
| 완료 조건 | 신뢰되지 않은 공급자는 경매 조정에 도달하지 못하고, 같은 요청은 새 경매를 만들지 않는다 |

## 2. 경매 — DSP 응답에서 낙찰 만들기

```text
AuctionCoordinator.runAuction(StartAuction)
  → DspBidExecutor.requestBids(BidRequestBatch)
  → WinnerSelector.selectWinners(EligibleBids)
  → AuctionWinners
```

| 항목 | 내용 |
|---|---|
| 참여 컴포넌트 | 경매 조정, DSP 입찰 실행, 낙찰 결정 |
| 호출 인터페이스 | `runAuction`, `requestBids`, `selectWinners` |
| 입력 메시지 | `StartAuction`, `BidRequestBatch`, `BidResponses`, `EligibleBids` |
| 출력 메시지 | `AuctionWinners` |
| 시험용 구현 | 세 DSP의 고정 입찰 응답. 프로젝트 DSP가 CPM 2,000으로 이기는 시나리오 |
| 완료 조건 | 절대 마감 안의 유효 입찰만 비교하고, 1가격·결정적 동점 규칙으로 슬롯별 낙찰자가 나온다 |

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
| 시험용 구현 | 공급자·요청·슬롯 귀속과 2초 기한을 검증하는 시험 증표, 인메모리 원자 저장소 |
| 완료 조건 | 유효하고 활성 공급자에 귀속된 증표만 `ACCEPTED`가 되며, 청구와 전달 작업 수가 함께 1개 증가한다 |

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
| 시험용 구현 | 동기 호출되는 시험용 DSP 통지 수신기와 인메모리 작업 임대 |
| 완료 조건 | 대기 작업 하나가 프로젝트 DSP의 `burl` 주소로 한 번 전달되고, 대기 수가 0이 된다 |

이 시점에 첫 E2E 전체가 녹색이 된다. 시험용 어댑터는 업무 규칙을 흉내 내지 않고, 이후 실제 어댑터로 교체할 수 있는 각 포트만 구현한다.

## 5. 강화 — 시험용 어댑터를 실제 기술로 교체하기

| 시험용 경계 | 교체할 실제 어댑터 | 관련 인터페이스 | 유지해야 하는 E2E 사실 |
|---|---|---|---|
| 메모리 공급자 스냅숏 | PostgreSQL 논리 복제 설정 사본을 읽어 원자 교체하는 스냅숏 | `ProviderTrustSnapshot` | 지역별 최신 설정 범위 안에서만 요청을 허용 |
| 고정 DSP 응답 | HTTP/JSON OpenRTB 2.6 DSP 클라이언트 | `DspBidExecutor`, `DspNotificationDelivery` | 기한 안의 유효 입찰·표준 `nurl/lurl/burl` 의미 |
| 시험 증표 | 이진 직렬화·AEAD·Base64URL 증표 | `RenderProofService` | 공급자 귀속·기한·낙찰 사실의 변조 불가 검증 |
| 인메모리 청구 저장소 | PostgreSQL 청구·전달 작업 트랜잭션과 작업 임대 | `ClaimDeliveryStore` | 청구와 작업의 원자 생성, 이전 작업자의 결과 덮어쓰기 방지 |
| 동기 `burl` 호출 | 인스턴스 내부 백그라운드 실행기와 제한 재시도 | `DspNotificationDelivery` | 5초 안의 내구 재시도와 중복 호출 허용 |

## 구현 원칙

- 한 단계는 자기 완료 조건을 시험으로 증명한 뒤 다음 단계로 넘어간다.
- 1~4단계에서는 포트와 메시지의 의미를 바꾸지 않는다. 기술 교체는 5단계의 책임이다.
- 실제 어댑터를 도입할 때만 새 기술의 실패·지연·자원 격리 기준을 추가로 결정한다.
