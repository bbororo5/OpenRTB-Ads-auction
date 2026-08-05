# DSP 리전 예산 원장

## 목적

서울·도쿄가 서로의 실행 상태에 의존하지 않는 독립 PostgreSQL 원장을 실행한다. 각 원장은 자기 리전의 캠페인 책임액과 DSP 리스만 보존한다.

## 실행

```bash
docker compose -f docker-compose.dsp-ledger.yml up -d --wait
```

서울은 `localhost:35432`, 도쿄는 `localhost:45432`를 사용한다. 둘 다 로컬 개발용 `rtb` 데이터베이스이며 운영 자격 증명이나 리전 간 복제를 가정하지 않는다.

## 검증

```bash
./gradlew :dsp-app:regionalLedgerIntegrationTest
```

검증은 같은 발급 요청의 한 번뿐인 차감과 만료 리스의 한 번뿐인 정산을 확인한다.
