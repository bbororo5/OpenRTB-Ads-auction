# 문서 안내

문서는 작성 순서가 아니라 독자의 질문에 따라 탐색한다.

| 알고 싶은 것 | 문서 |
|---|---|
| 무엇을 만드는가 | [제품·도메인 요구사항](requirements/product.md) |
| 어느 부하와 품질을 만족해야 하는가 | [아키텍처 중요 요구사항](requirements/quality.md), [부하·데이터·검증 기준](requirements/workload.md) |
| 현재 시스템은 어떻게 구성되는가 | [공식 아키텍처](architecture/README.md) |
| DSP 내부 책임을 어떤 협력에서 도출하는가 | [DSP 협력과 메시지](architecture/views/dsp-collaboration.md) |
| DSP 컴포넌트 경계와 인터페이스는 무엇인가 | [DSP 애플리케이션 컴포넌트](architecture/views/dsp-components.md) |
| SSP에서 어떤 기술을 확정했고 무엇이 남았는가 | [SSP 기술 기준선](architecture/technology/ssp.md) |
| SSP를 어떤 순서로 구현하는가 | [SSP 얇은 수직 흐름 구현 계획](architecture/implementation/ssp-thin-vertical-slice.md) |
| 무엇이 구조를 압박했고 왜 선택했는가 | [아키텍처 동인](architecture/drivers.md), [ADR 목록](architecture/README.md#4-adr-대조-결과) |
| 어떤 관점으로 위험을 검토했는가 | [설계 근거](architecture/rationale/) |

```text
docs/
├── README.md
├── requirements/
│   ├── product.md
│   ├── quality.md
│   └── workload.md
└── architecture/
    ├── README.md
    ├── drivers.md
    ├── technology/
    │   └── ssp.md
    ├── implementation/
    │   └── ssp-thin-vertical-slice.md
    ├── views/
    │   ├── landscape.md
    │   ├── ssp-context.md
    │   ├── dsp-context.md
    │   ├── ssp-containers.md
    │   ├── ssp-components.md
    │   ├── dsp-collaboration.md
    │   ├── dsp-components.md
    │   ├── dsp-containers.md
    │   ├── data.md
    │   ├── ssp-deployment.md
    │   └── dsp-deployment.md
    ├── decisions/
    │   └── ADR-001~008
    └── rationale/
        ├── data.md
        ├── domain.md
        ├── evolution.md
        ├── failure.md
        └── trust.md
```

`requirements/`는 시스템이 만족할 계약, `views/`는 현재 구조, `decisions/`는 되돌리기 어려운 선택, `rationale/`는 동인을 발견한 근거를 소유한다. 작성 과정과 과거 구조는 Git 기록에서 확인한다.
