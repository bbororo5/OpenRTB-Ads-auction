# 프로젝트 DSP 다중 리전 배포

상태: 실행·데이터 배치와 전역 책임·지역 금액 저장 기술 확정

프로젝트 DSP 컨테이너 인스턴스와 기반 시설의 배치만 보여준다. 전역 책임 원장과 각 리전 예산 원장은 독립된 물리 저장소 배포 경계다. 리전 예산 원장의 제품과 실제 애플리케이션 인스턴스 수는 정하지 않는다.

```mermaid
C4Deployment
    title 프로젝트 DSP — 서울·도쿄 능동 리전과 다중 AZ 배포

    Deployment_Node(dsp_environment, "프로젝트 DSP 운영 환경", "독립 배포·운영 경계") {
        Deployment_Node(dsp_edge, "전역 진입", "분산 트래픽 기반 시설", "건강한 능동 리전으로 새 연결을 전달한다.")

        Deployment_Node(region_1, "서울 · ap-northeast-2", "능동") {
            Deployment_Node(az_1a, "AZ A", "독립 장애 영역") {
                Container(gateway_1a, "DSP 게이트웨이 인스턴스", "기술 미정", "요청을 분산하고 과부하를 차단한다.")
                Container(app_1a, "DSP 애플리케이션 인스턴스", "기술 미정", "로컬 입찰과 예산 배경 처리를 실행한다.")
            }
            Deployment_Node(az_1b, "AZ B", "독립 장애 영역") {
                Container(gateway_1b, "DSP 게이트웨이 인스턴스", "기술 미정", "요청을 분산하고 과부하를 차단한다.")
                Container(app_1b, "DSP 애플리케이션 인스턴스", "기술 미정", "로컬 입찰과 예산 배경 처리를 실행한다.")
            }
            ContainerDb(campaign_1, "캠페인 데이터 복제본", "저장 기술 미정", "시험 전에 확정한 같은 버전을 제공한다.")
            ContainerDb(regional_1, "서울 예산 원장", "RDS PostgreSQL Multi-AZ", "서울 책임액의 유일한 작성자다.")
            ContainerDb(regional_2_dr, "도쿄 예산 원장 복구 사본", "PostgreSQL 읽기 복구 사본", "도쿄 세부 기록의 읽기 전용 비동기 사본이다.")
            ContainerDb(money_1, "서울 금액 사건 기록", "RDS PostgreSQL Multi-AZ", "서울에서 접수한 통지의 내부 판정과 리스 정산 근거를 보존한다.")
            ContainerDb(global_1, "전역 책임 원장", "RDS PostgreSQL Multi-AZ", "전역 예비액과 책임 이전의 단일 쓰기 권위다.")
        }

        Deployment_Node(region_2, "도쿄 · ap-northeast-1", "능동") {
            Deployment_Node(az_2a, "AZ A", "독립 장애 영역") {
                Container(gateway_2a, "DSP 게이트웨이 인스턴스", "기술 미정", "요청을 분산하고 과부하를 차단한다.")
                Container(app_2a, "DSP 애플리케이션 인스턴스", "기술 미정", "로컬 입찰과 예산 배경 처리를 실행한다.")
            }
            Deployment_Node(az_2b, "AZ B", "독립 장애 영역") {
                Container(gateway_2b, "DSP 게이트웨이 인스턴스", "기술 미정", "요청을 분산하고 과부하를 차단한다.")
                Container(app_2b, "DSP 애플리케이션 인스턴스", "기술 미정", "로컬 입찰과 예산 배경 처리를 실행한다.")
            }
            ContainerDb(campaign_2, "캠페인 데이터 복제본", "저장 기술 미정", "시험 전에 확정한 같은 버전을 제공한다.")
            ContainerDb(regional_2, "도쿄 예산 원장", "RDS PostgreSQL Multi-AZ", "도쿄 책임액의 유일한 작성자다.")
            ContainerDb(regional_1_dr, "서울 예산 원장 복구 사본", "PostgreSQL 읽기 복구 사본", "서울 세부 기록의 읽기 전용 비동기 사본이다.")
            ContainerDb(money_2, "도쿄 금액 사건 기록", "RDS PostgreSQL Multi-AZ", "도쿄에서 접수한 통지의 내부 판정과 리스 정산 근거를 보존한다.")
            ContainerDb(global_2, "전역 책임 원장 복구 사본", "RDS PostgreSQL 읽기 복제본", "자동 승격하지 않는 비동기 복구 사본이다.")
        }
    }

    Rel(dsp_edge, gateway_1a, "새 연결 전달")
    Rel(dsp_edge, gateway_1b, "새 연결 전달")
    Rel(dsp_edge, gateway_2a, "새 연결 전달")
    Rel(dsp_edge, gateway_2b, "새 연결 전달")
    Rel(gateway_1a, app_1a, "요청 분산")
    Rel(gateway_1b, app_1b, "요청 분산")
    Rel(gateway_2a, app_2a, "요청 분산")
    Rel(gateway_2b, app_2b, "요청 분산")
    Rel(app_1a, campaign_1, "시작 전 자료 적재")
    Rel(app_1a, regional_1, "리스 발급·페이싱·격리·정산")
    Rel(app_1a, global_1, "책임 봉투 이전")
    Rel(app_1a, money_1, "금액 사건 접수")
    Rel(app_2a, campaign_2, "시작 전 자료 적재")
    Rel(app_2a, regional_2, "리스 발급·페이싱·격리·정산")
    Rel(app_2a, global_1, "책임 봉투 이전", "홈 리전 호출")
    Rel(app_2a, money_2, "금액 사건 접수")
    Rel(regional_1, regional_1_dr, "세부 기록 복구 사본", "비동기")
    Rel(regional_2, regional_2_dr, "세부 기록 복구 사본", "비동기")
    Rel(money_1, money_2, "비동기 내부 집계")
    Rel(money_2, money_1, "비동기 내부 집계")
    Rel(global_1, global_2, "복구용 비동기 복제")

    UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

- 전역 진입은 컨테이너가 아니라 배포 기반 시설이다.
- 두 리전은 자기 책임액과 로컬 권한으로 독립 입찰한다.
- 두 리전의 DSP는 시작 전에 같은 캠페인 버전과 체크섬을 확인하며 시험 중 갱신하지 않는다.
- 리전별 페이싱은 자기 책임액의 리스 발급으로 수행하며 개별 리스 상태를 리전 간 교환하지 않는다.
- 전역 책임 원장과 각 리전 예산 원장은 클러스터·장애·합의 범위를 공유하지 않는다.
- 전역 책임 원장은 서울을 홈으로 표시한 단일 쓰기 권위다. 도쿄 복구 사본은 지역 활성화 증거 대조 없이 자동 승격하지 않는다.
- 게이트웨이와 DSP 애플리케이션 인스턴스는 각각 리전 풀을 이루며 도식의 선은 인스턴스 간 고정 결합을 뜻하지 않는다.
- 입찰과 예산 처리는 같은 애플리케이션 프로세스 안에서 실행 자원을 격리한다.
- AZ B의 DSP 애플리케이션도 같은 지역 저장소 관계를 가지며 중복 선은 생략했다.
- 리전 예산 원장의 점선 의미는 비동기 복구이며 상대 리전의 책임액을 수정하지 않는다.
