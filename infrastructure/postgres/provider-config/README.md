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

복제 준비가 끝난 뒤 검증 컨테이너만 실행한다.

```bash
docker compose -f docker-compose.provider-config.yml --profile verify \
  run --rm --no-deps provider-config-replication-verifier
```

검증기는 서울에서 버전 1을 발행한 뒤 도쿄 수신을 확인하고, 버전 2에서 공급자를 비활성화한 뒤 도쿄가 새 head와 정책을 받았는지 확인한다.

초기화부터 다시 하려면 볼륨도 제거한다.

```bash
docker compose -f docker-compose.provider-config.yml down -v
```

## 파일 역할

| 파일 | 역할 |
|---|---|
| `schema.sql` | 서울·도쿄에 동일하게 적용하는 설정 테이블, 기본 키, 외래 키 |
| `init-publisher.sh` | 서울의 복제 계정과 publication 생성 |
| `bootstrap-subscription.sh` | 두 DB가 준비된 뒤 도쿄 subscription 생성 |
| `verify-replication.sh` | 서울 발행이 도쿄에 자동 반영되는지 확인 |

이 환경의 비밀번호 기본값은 로컬 개발 전용이다. 실제 배포에서는 Compose 환경 변수 대신 비밀 저장소에서 주입하고, RDS의 논리 복제 지원·네트워크·TLS 정책에 맞춰 연결 정보를 구성한다.
