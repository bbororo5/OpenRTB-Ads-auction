# 공급자 설정 논리 복제 개발 환경

서울 원본 PostgreSQL과 도쿄 읽기 복제본 사이의 공급자 설정 복제를 재현한다. 두 저장소에는 같은 스키마와 외래 키가 있고, 서울만 쓰기 권위를 가진다.

```text
postgres-seoul (publisher)
  └─ provider_config_publication
       └─ provider_config_subscription
            └─ postgres-tokyo (subscriber)
```

## 실행

```bash
docker compose -f docker-compose.provider-config.yml up -d
```

## SSP 단독 운영 확인

`ssp` 프로필은 두 지역 SSP를 실제 PostgreSQL에 연결해 기동한다. DSP 주소는 형식만 갖춘 미구성 주소이며, 이 확인은 경매를 호출하지 않는다. 따라서 DSP 모의 서버가 필요 없다.

```bash
docker compose -f docker-compose.provider-config.yml --profile ssp up --build -d
docker compose -f docker-compose.provider-config.yml --profile ssp --profile verify-ssp \
  run --rm ssp-runtime-verifier
```

검증기는 서울(`18080`)과 도쿄(`28080`) SSP 각각의 다음 경계를 확인한다.

- `/health/live`: 프로세스 생존
- `/health/ready`: 신규 경매·렌더링 요청 수락 가능
- `/metrics`: Prometheus 형식의 SSP HTTP 운영 지표

이 단계는 실제 경매 성공을 검증하지 않는다. 경매 성공과 `nurl`·`lurl`·`burl`의 시스템 간 전달은 프로젝트 DSP 구현 뒤 통합한다.

복제 준비가 끝난 뒤 검증 컨테이너만 실행한다.

```bash
docker compose -f docker-compose.provider-config.yml --profile verify \
  run --rm --no-deps provider-config-replication-verifier
```

검증기는 서울에서 버전 1을 발행한 뒤 도쿄 수신을 확인하고, 버전 2에서 공급자를 비활성화한 뒤 도쿄가 새 head와 정책을 받았는지 확인한다.

SSP의 실제 JDBC 설정 리더까지 함께 검증하려면 DB를 올린 상태에서 다음을 실행한다.

```bash
gradle :ssp-app:providerConfigReplicationIntegrationTest
```

이 시험은 publisher 연결로 새 설정 버전을 발행하고, subscriber 연결로 구성한 `PostgreSqlProviderConfigReader`가 복제된 활성 버전과 공급자·키 상태를 읽는지 확인한다. 연결 주소는 Gradle 속성으로 바꿀 수 있으며, 애플리케이션 코드에는 지역 이름이나 DB 선택 분기가 없다.

같은 지역 DB에 독립 생성되는 SSP 청구·전달 작업 저장소는 다음으로 검증한다.

```bash
gradle :ssp-app:sspClaimStoreIntegrationTest
```

청구 저장소 시험은 시험 행을 임대·종결하므로, 같은 지역 DB를 사용하는 `ssp` 프로필을 기동하기 전에 실행한다. 실제 SSP 작업자가 시험 행을 먼저 처리하는 경쟁을 막기 위한 운영 순서다.

`ssp_billing_delivery`는 논리 복제 대상이 아니다. 서울과 도쿄 SSP는 각자 자기 지역의 청구 근거와 `burl` 전달 책임만 기록한다.

초기화부터 다시 하려면 볼륨도 제거한다.

```bash
docker compose -f docker-compose.provider-config.yml down -v
```

## 파일 역할

| 파일 | 역할 |
|---|---|
| `schema.sql` | 서울·도쿄에 동일하게 적용하는 설정 테이블, 기본 키, 외래 키 |
| `../ssp-claims/schema.sql` | 각 지역이 독립 소유하는 SSP 청구·전달 작업 테이블 |
| `init-publisher.sh` | 서울의 복제 계정과 publication 생성 |
| `bootstrap-subscription.sh` | 두 DB가 준비된 뒤 도쿄 subscription 생성 |
| `verify-replication.sh` | 서울 발행이 도쿄에 자동 반영되는지 확인 |

이 환경의 비밀번호 기본값은 로컬 개발 전용이다. 실제 배포에서는 Compose 환경 변수 대신 비밀 저장소에서 주입하고, RDS의 논리 복제 지원·네트워크·TLS 정책에 맞춰 연결 정보를 구성한다.
