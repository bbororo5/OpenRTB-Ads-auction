# DSP 지역 금액 사건 저장소

## 목적

각 리전에서 접수한 `lurl`·`burl`과 예약 만료를 최초 종결 사건으로 기록한다. 리전 예산 원장과 물리적으로 분리하며 리스 정산은 이 기록을 재생한다.

## 실행

```bash
docker compose -f docker-compose.dsp-money-events.yml up -d --wait
```

서울은 `localhost:35532`, 도쿄는 `localhost:45532`를 사용한다.

## 검증

```bash
./gradlew :dsp-app:moneyEventStoreIntegrationTest
```

최초·중복·충돌 판정과 리스 액면의 확정·반환·격리 집계를 확인한다.
