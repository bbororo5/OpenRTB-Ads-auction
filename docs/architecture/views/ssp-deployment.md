# SSP 다중 리전 배포

상태: 실행·데이터 배치 확정·기술 미정

SSP 컨테이너 인스턴스와 기반 시설의 배치만 보여준다. 기술 제품과 실제 인스턴스 수는 정하지 않는다.

```mermaid
C4Deployment
    title SSP — 서울·도쿄 능동 리전과 다중 AZ 배포

    Deployment_Node(ssp_environment, "SSP 운영 환경", "독립 배포·운영 경계") {
        Deployment_Node(ssp_edge, "전역 진입", "분산 트래픽 기반 시설", "새 요청을 가까운 정상 리전으로 전달한다.")

        Deployment_Node(region_1, "서울 · ap-northeast-2", "능동") {
            Deployment_Node(az_1a, "AZ A", "독립 장애 영역") {
                Container(app_1a, "SSP 애플리케이션 인스턴스", "기술 미정", "경매·렌더링·통지를 처리한다.")
            }
            Deployment_Node(az_1b, "AZ B", "독립 장애 영역") {
                Container(app_1b, "SSP 애플리케이션 인스턴스", "기술 미정", "경매·렌더링·통지를 처리한다.")
            }
            ContainerDb(store_1, "SSP 서울 지역 저장소", "RDS PostgreSQL Multi-AZ", "서울 청구 기록과 공급자 설정의 단일 쓰기 원본을 보존한다.")
        }

        Deployment_Node(region_2, "도쿄 · ap-northeast-1", "능동") {
            Deployment_Node(az_2a, "AZ A", "독립 장애 영역") {
                Container(app_2a, "SSP 애플리케이션 인스턴스", "기술 미정", "경매·렌더링·통지를 처리한다.")
            }
            Deployment_Node(az_2b, "AZ B", "독립 장애 영역") {
                Container(app_2b, "SSP 애플리케이션 인스턴스", "기술 미정", "경매·렌더링·통지를 처리한다.")
            }
            ContainerDb(store_2, "SSP 도쿄 지역 저장소", "RDS PostgreSQL Multi-AZ", "도쿄 청구 기록과 복제된 공급자 설정 사본을 보존한다.")
        }
    }

    Rel(ssp_edge, app_1a, "새 요청 전달")
    Rel(ssp_edge, app_1b, "새 요청 전달")
    Rel(ssp_edge, app_2a, "새 요청 전달")
    Rel(ssp_edge, app_2b, "새 요청 전달")
    Rel(app_1a, store_1, "성공 전 기록")
    Rel(app_1b, store_1, "성공 전 기록")
    Rel(app_2a, store_2, "성공 전 기록")
    Rel(app_2b, store_2, "성공 전 기록")
    Rel(store_1, store_2, "공급자 설정만 비동기 논리 복제")
    UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

- 전역 진입은 컨테이너가 아니라 배포 기반 시설이다. 공급자는 전역 주소 하나만 호출하며, 이 계층이 요청 출발 네트워크와 정상 상태로 SSP 셀을 선택한다.
- 두 리전은 평상시 모두 요청을 처리한다.
- 각 리전은 최소 두 AZ에 SSP 애플리케이션 인스턴스를 둔다.
- 경매와 렌더링 통지는 우선 같은 프로세스·진입 실행 자원을 사용한다. 측정된 경합이 50ms 목표를 침범할 때만 실행 자원 격리를 도입한다.
- 정상 경로는 서울 SSP→서울 DSP, 도쿄 SSP→도쿄 DSP처럼 같은 지역에 머문다. 외부 경쟁 DSP의 배치 구조는 SSP의 보장 범위가 아니다.
- 낙찰 결과는 인증된 렌더링 증표와 발급 리전 전용 렌더링 URL로 클라이언트가 운반하므로 경매 경로에서 저장하지 않는다. 발급 리전은 자기 청구 사건을 여러 AZ에 내구 기록하고 `burl` 전달을 재개한다. 다른 SSP 리전은 해당 사건을 대신 처리하지 않는다.
- 공급자 설정은 서울 지역 저장소의 단일 쓰기 원본에서 도쿄 지역 저장소로 논리 복제한다. 각 SSP 인스턴스는 자기 리전 사본을 검증한 뒤 불변 메모리 스냅숏으로 교체한다. 이 복제 지연은 경매 경로에 들어가지 않는다.
