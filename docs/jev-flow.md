# JEV 적용 흐름과 팀 공유 설명

## 한 문장으로 설명

Lily는 서버가 수집한 저장소 특성과 검증된 배포 후보를 바탕으로 JEV에 제한된 선택 질문을 보내고, 선택 결과를 배포 계획으로 저장한 뒤 같은 계획을 실행하도록 구성했다.

## 역할 구분

- JEV: 정해진 후보 중 분류·판단. 저장소의 AWS/GCP 의존성, 비용과 지연 시간의 균형을 판단한다.
- 서버 규칙: 배포 가능 여부, 예산, P95 상한, 지역, 기능, 데이터 위치, 측정값 유효 기간을 검사한다.
- Groq: 기존 코드 수정 흐름에서 수정안 생성을 담당한다. 클라우드 선택 흐름에서는 코드를 수정하지 않는다.

## AWS/GCP 선택 과정

1. 저장소 URL, 브랜치, 하위 폴더를 받고 분석할 커밋 SHA를 고정한다.
2. 선택한 앱 폴더의 의존성 파일에서 알려진 SDK와 프레임워크 신호를 추출한다. 전체 소스나 README를 모델에 보내는 방식은 아니다.
3. 일반적인 이식 가능한 앱은 규칙으로 처리한다. AWS/GCP SDK 의존성이 있으면 JEV의 `repository_fit` 질문으로 `aws`, `gcp`, `portable`, `unknown` 중 적합성을 분류한다.
4. 실제 카탈로그와 작업기 설정을 사용해 배포 불가능한 후보를 먼저 제외한다. SDK를 발견했다는 이유만으로 필수 서비스라고 확정하지 않는다.
5. 수동 선택, 후보가 하나인 경우, 비용 또는 속도 우선, 한 후보가 비용·속도 모두 우세한 경우에는 규칙으로 선택한다. 균형 정책에서 비용과 속도 사이의 선택이 남을 때 JEV의 `cloud` 질문을 사용한다.
6. JEV 결과의 후보명과 신뢰도를 검사한다. 기본 신뢰도 기준은 0.8이다. 실패하거나 기준보다 낮으면 규칙으로 계산하며, 유효한 보류 판단은 보류한다.
7. 결정과 커밋을 DynamoDB에 `planId`로 저장한다. 실행할 때 입력 일치 여부와 최신 후보 유효성을 다시 검사하고, 확정한 제공자의 작업기에 요청한다. 실행 단계에서 JEV를 다시 호출해 제공자를 바꾸지 않는다.

예를 들어 BigQuery SDK는 GCP 적합성을 판단할 근거가 될 수 있다. 다만 GCP 작업기가 없거나 예산·지연 시간 조건을 충족하지 못하면 자동 배포 후보가 되지 않는다. “머신러닝이면 GCP” 같은 일반적인 인상만으로 제공자를 결정하지 않는다.

## 호출 비용과 중복 배포 방지

- cloud 흐름은 공유 JEV 인스턴스를 사용한다. 동일 입력 결과를 캐시하고 동시에 들어온 동일 질문은 한 번의 호출 결과를 공유한다.
- 캐시는 프로세스 내부에 있으므로 서버 재시작이나 여러 서버 사이의 계획 일치를 보장하지 않는다. 이 보장은 DynamoDB의 저장된 계획이 담당한다.
- 실행 API는 `requestId`와 `planId`를 받는다. 같은 요청을 재전송해도 작업기에 배포를 다시 요청하지 않는다.
- 요청 상태와 앱 상태를 DynamoDB 트랜잭션으로 선점하여 여러 서버의 동시 요청을 방지한다.
- 작업기가 접수했는지 알 수 없는 통신 실패는 자동 재배포하지 않고 확인이 필요한 상태로 남긴다. 운영자가 실제 빌드 ID를 확인해 연결하는 복구 API를 제공한다.
- 원본 환경변수와 비공개 저장소 토큰을 계획에 저장하지 않는다. 실행 입력의 일치 여부는 HMAC으로 확인한다.

## 코드 위치

| 위치 | 역할 |
| --- | --- |
| [CloudRepository.java](../src/main/java/com/lily/builder/cloud/CloudRepository.java) | 커밋·앱 폴더·의존성 분석, 저장소 적합성 질문 |
| [CloudPolicy.java](../src/main/java/com/lily/builder/cloud/CloudPolicy.java) | 필수 조건 검사, 비용·속도 선택, JEV 결과 검증 |
| [CloudConfiguration.java](../src/main/java/com/lily/builder/cloud/CloudConfiguration.java) | 공유 JEV와 캐시 설정 |
| [CloudPlans.java](../src/main/java/com/lily/builder/cloud/CloudPlans.java) | 계획 저장, 입력 일치·만료 검사 |
| [DynamoCloudState.java](../src/main/java/com/lily/builder/cloud/DynamoCloudState.java) | 영속 상태와 트랜잭션 |
| [CloudDispatches.java](../src/main/java/com/lily/builder/cloud/CloudDispatches.java) | 중복 방지, 실행·상태 갱신·복구 |
| [CloudController.java](../src/main/java/com/lily/builder/cloud/CloudController.java) | 내부 API 진입점 |
| lily-jev의 CachedJev / HttpJev | 동일 질문 캐시·동시 호출 병합 / JEV HTTP 호출 |

## 기존 장애 진단 작업과의 관계

기존 코드 수정 흐름의 `JevDefectGate`는 로그가 코드 결함에 해당하는지 판단한다. 수정안 생성은 Groq 흐름으로 이어진다. 이번 클라우드 선택 기능과 질문·목적이 다르다.

별도 `feature/ai-diagnosis` 작업은 observer에서 근거를 수집하고 캐시·동시 요청 병합을 적용한 뒤, builder가 근거에 있는 원인 후보를 JEV로 분류하는 기능이다. 진단 결과는 근거와 권고를 제공하며 자동으로 인프라를 변경하지 않는다. 이 진단 브랜치는 이번 클라우드 선택 main 병합에 포함하지 않았다.

## 실제 적용에 필요한 연결

이번 변경은 lily-builder와 lily-jev의 클라우드 선택 로직에 반영한다. 실제 운영 사용에는 다음 설정과 연동이 필요하다.

- `JEV_API_KEY`, 내부 API 인증용 `CLOUD_API_TOKEN`
- 실제 가격·P95·기능·측정 시각을 반환하는 `CLOUD_CATALOG_URL`
- AWS/GCP 작업기 URL·지역·인증과 실제 배포 환경
- 별도 DynamoDB 테이블 `CLOUD_STATE_TABLE` 및 접근 권한
- 프론트가 저장소 분석 응답의 `planId`를 보존하고 실행 시 `requestId`, `planId`, `build`를 전달하는 연결

현재 실제 가격·P95 카탈로그와 GCP 환경은 제공받지 않았다. 측정값을 임의로 채우지 않았으며, 코드 병합이 운영 배포 완료를 뜻하지 않는다. 상세 계약은 [cloud-placement.md](cloud-placement.md)를 참고한다.

## 검증 기록

- builder 전체 테스트: 232개 통과, 4개 제외, 실패 0개.
- lily-jev 테스트: 12개 통과.
- DynamoDB Local에서 다른 저장소 인스턴스의 계획 읽기, 동시 요청 선점, 상태 갱신, 종료 후 새 요청 접수를 확인했다.
- 이번 검증은 실제 클라우드 배포나 유료 JEV 호출을 실행한 검증은 아니다.
