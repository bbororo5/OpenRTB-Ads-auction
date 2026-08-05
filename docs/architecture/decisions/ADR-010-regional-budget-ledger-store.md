# ADR-010 리전 예산 원장 저장소

상태: 승인

근거: [ADR-001 분산 캠페인 예산 예약](ADR-001-distributed-budget-reservation.md), [DSP 기술 결정 경계](../technology/dsp.md)

## 1. 결정

리전 예산 원장은 리전마다 독립된 **Amazon RDS for PostgreSQL Multi-AZ DB 인스턴스**에 둔다.

- 리스 발급은 캠페인 가용액 차감과 리스 생성을 한 짧은 트랜잭션으로 커밋한다.
- 발급 요청 ID와 `leaseId + settlementGeneration`에 고유 제약을 두어 발급·정산을 각각 한 번만 반영한다.
- 리스 시각은 원장 트랜잭션 시각으로 결정한다. DSP는 절대 시각을 `Instant`로 받고 안전 여유만큼 일찍 로컬 단조 시계 기한을 종료한다.
- EC2 운영체제 시계는 Amazon Time Sync Service로 동기화하되 시간 오차가 권한 연장으로 이어지지 않게 한다.
- 만료 정산 대상은 원장 시각으로 고르고, 여러 DSP 작업자는 짧은 작업 임대와 `SKIP LOCKED`로 나눠 처리한다.
- 원장 장애 중 기존 로컬 리스는 계속 사용하고 새 발급·정산만 재시도한다.
- 서울과 도쿄 원장은 서로의 쓰기 성공을 기다리지 않으며 자기 리전 책임액만 소유한다.

## 2. 맥락

원장은 매 입찰이 아니라 소액·단기 리스의 발급과 정산만 처리한다. 필요한 핵심은 최고 쓰기 처리량보다 다음 불변식을 직접 보존하는 것이다.

```text
리전 미발급액
+ 미정산 DSP 리스 액면
+ 정산된 확정 지출
+ 격리액
= 리전 책임액
```

같은 캠페인의 발급은 직렬화해도 되지만 다른 캠페인과 정산 작업은 병렬이어야 한다. 트랜잭션 안에서는 외부 호출을 금지하고 조건부 갱신·리스 삽입만 수행한다.

## 3. 대안과 트레이드오프

| 후보 | 강점 | 감수할 점 | 판단 |
|---|---|---|---|
| PostgreSQL | 트랜잭션·행 잠금·고유 제약으로 금액 불변식과 멱등성을 직접 표현 | 인기 캠페인 행 경합, 행 버전과 Autovacuum 관리 | **선택** |
| DynamoDB | 조건부 쓰기와 관리형 수평 확장 | 관계형 불변식과 정산 작업 조회가 복잡하고 TTL은 정확한 실행 시각을 보장하지 않음 | 처리량 한계가 확인될 때 비교 |
| Redis Cluster | 낮은 지연과 원자 스크립트 | 비동기 복제 장애에서 승인된 쓰기 보존을 권위 원장 수준으로 설명하기 어려움 | 제외 |
| 분산 SQL | 복제·합의·수평 분할 제공 | 리전 내부 원장에 합의 비용과 운영 복잡도가 과함 | 단일 리전 기준선 실패 시 비교 |

PostgreSQL의 MVCC는 읽기와 쓰기의 불필요한 상호 차단을 줄이지만 같은 행의 동시 쓰기를 병렬화하지 않는다. 자주 바뀌는 잔액 칼럼의 인덱스를 피하고 짧은 트랜잭션, HOT 갱신과 테이블별 Autovacuum 관측으로 갱신 비용을 관리한다.

## 4. 결과

### 얻는 점

- 예산 차감과 리스 생성을 하나의 저장소 원자 경계로 설명한다.
- 애플리케이션 `if`문이 아니라 고유 제약으로 중복 금액 효과를 막는다.
- 리스 행을 내구 정산 작업으로 사용해 작업자 장애 뒤 다른 DSP가 이어받는다.
- SSP 지역 장부와 같은 운영 기술을 재사용하되 회사·책임별 데이터베이스는 분리한다.

### 감수하는 점

- 인기 캠페인의 발급은 해당 캠페인 행에서 직렬화된다.
- Multi-AZ 복제 지연과 장애 전환 중 새 리스 발급이 일시 중단된다.
- 높은 갱신률에서 WAL·죽은 튜플·Autovacuum과 인덱스 팽창을 관측해야 한다.
- 리전 전체 장애 때 해당 리전 원장을 자동 능동 전환하지 않고 책임액을 동결한다.

## 5. 검증 조건

- 같은 발급 요청을 동시에 실행해도 리스와 가용액 차감이 하나다.
- 여러 DSP가 같은 캠페인에 요청해도 발급 합계가 리전 가용액을 넘지 않는다.
- 같은 정산 세대를 반복해도 소비·반환·격리액이 한 번만 반영된다.
- 정산 작업자 종료 뒤 작업 임대가 만료되면 다른 DSP가 이어받는다.
- 저장소 시각과 DSP 시계가 어긋나도 원장 만료 뒤 신규 예약이 생기지 않는다.
- 편향 부하에서 행 잠금, 트랜잭션 지연, HOT 갱신률, 죽은 튜플과 Autovacuum 영향을 기록한다.

## 6. 근거 자료

- [PostgreSQL MVCC와 Routine Vacuuming](https://www.postgresql.org/docs/current/routine-vacuuming.html)
- [PostgreSQL Heap-Only Tuple](https://www.postgresql.org/docs/current/storage-hot.html)
- [PostgreSQL `SKIP LOCKED`](https://www.postgresql.org/docs/current/sql-select.html)
- [Amazon Time Sync Service](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configure-ec2-ntp.html)
- [DynamoDB 조건부 쓰기와 트랜잭션](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transactions.html)
- [Redis Cluster 일관성](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/)
