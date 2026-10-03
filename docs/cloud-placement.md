# 저장소 분석과 AWS/GCP 배포 선택

## 구현 범위

`feature/cloud-placement`는 builder에 선택 API를 추가한다. 기본 정책은 **비용·속도 균형**이다.

1. GitHub의 커밋을 고정하고, 실제 배포가 빌드할 앱 폴더의 의존성 파일을 읽는다.
2. 알려진 ML·데이터·웹·클라우드 SDK 의존성을 추출한다. AWS·GCP SDK나 관리형 서비스 단서가 있을 때만 JEV가 클라우드 연관성을 판단한다.
3. 서버가 수집한 같은 작업 규모의 비용·P95와 배포 가능 여부로 후보를 제한한다.
4. 규칙으로 정할 수 없는 경우(`balanced`이고 비용·P95가 엇갈리는 후보가 둘 이상)에만 JEV가 선택한다. 불확실하거나 호출에 실패하면 수치 기반 규칙을 사용한다.
5. 실행 직전에 관측값을 다시 확인하고 선택한 worker의 기존 `/api/builds`로 전달한다.
6. 같은 provider의 worker에서 상태를 조회한다.

JEV 캐시는 반복/동시 호출 비용을 줄이는 용도다. 승인된 선택은 DynamoDB의 `planId`에 별도로 저장한다.
미리보기와 실행 사이에 서버가 재시작되거나 다른 replica가 요청을 받아도 저장된 정책·커밋·provider를 사용한다.
실행 시 JEV를 다시 호출하지 않으며, 선택된 후보의 최신 관측값이 필수 조건을 만족하는지만 다시 검사한다.
계획은 15분 유효하다. 만료되거나 배포 설정이 달라지면 새 미리보기를 요청한다.

현재 GCP 환경은 없다. **GCP worker·실제 가격/관측 데이터 수집기·프론트 호출은 별도 연결이 필요하다.**
이 브랜치는 GKE 리소스를 생성하지 않으며 기존 `/api/builds` 요청을 자동으로 이 경로로 바꾸지 않는다.
Groq의 코드 수정 기능도 이 API와 별개다. JEV는 저장소 특성과 배포 위치를 판단한다.

## 저장소를 어떻게 읽는가

지원 파일: `package.json`, `requirements.txt`, `pyproject.toml`, `pom.xml`,
`build.gradle`, `build.gradle.kts`, `go.mod`, `Cargo.toml`, `Gemfile`.

- 실제 배포가 빌드할 앱 폴더 바로 아래의 파일만 분석한다. `rootDir`를 비우면 배포(`BuildService.source`)·`/api/detect`와
  같은 방법으로 폴더를 찾는다. 하위 폴더 하나에 있는 앱이면 그 폴더, 백엔드+프론트 묶음이면 백엔드 폴더다. 응답의 `repository.dir`로 알려 준다.
- 빌드할 앱이 없거나 폴더가 여러 개라 하나로 정하지 못하면 422 `build_folder_unresolved`다. 화면이 폴더를 지정하게 한다.
- manifest당 64 KiB 제한. 접근 실패나 크기 초과는 분석 실패로 처리한다.
- manifest 원문, README, `.env`, 토큰, 환경변수, 임의 소스 코드는 JEV로 전송하지 않는다.
- 서버에서 정해 둔 의존성 이름만 추출해 전달한다. 저장소 코드를 실행하지 않는다.
- `pom.xml`의 groupId/artifactId는 `group:artifact`로 읽는다. 줄 전체가 `#`·`//` 주석이거나 XML 주석인 부분은 읽지 않는다.
- Java AWS SDK v2 모듈(`software.amazon.awssdk:s3`·`sqs`·`sns`·`bedrock`·`bedrockruntime`)은 npm 패키지와 같은 서비스 단서다.
- 이는 의존성 문자열의 정적 분석이다. 실제 사용 여부·GPU 필요 여부·학습량·데이터 위치를 증명하지 않는다.
- 지원 manifest가 없으면 분석 응답에 한계를 표시하고 배포는 `repository_manifest_required`로 보류한다.

| 근거 예시 | 해석 |
|---|---|
| PyTorch, TensorFlow | ML 의존성. 이것만으로 GCP를 선호하지 않는다 |
| pandas, Spark | 데이터 처리 의존성. 실제 데이터량·위치 확인이 더 필요하다 |
| BigQuery, Vertex AI SDK | GCP 서비스와 연관될 가능성. 실제 서비스 사용 여부는 별도 확인 |
| SageMaker, AWS SDK | AWS 서비스와 연관될 가능성. 특정 클라우드를 강제하지 않는다 |
| Spring Boot, Next.js | 웹 앱 특성. 이것만으로 AWS를 선호하지 않는다 |

`repository.affinity`는 `aws | gcp | portable | unknown`, `source`는 `jev | rules`다.
실제 선택인 `decision.provider`와 구분한다. GCP 연관성이 있어도 GCP 실행기가 없으면 GCP로 배포하지 않는다.

| 의존성 단서 | 연관성 |
|---|---|
| AWS·GCP SDK나 관리형 서비스 단서가 있음 | JEV가 판단. 신뢰도가 기준(기본 0.8) 미만이거나 유효하지 않으면 `unknown` |
| 웹·DB·ML·데이터 같은 일반 의존성만 있음 | JEV에 묻지 않고 규칙으로 `portable` |
| Azure·기업 계정 단서만 있음 | JEV에 묻지 않고 규칙으로 `unknown` (통합 검토 필요) |
| 아는 의존성이 없음 | `unknown` |

`sourceCommit`으로 분석한 SHA를 worker에 전달한다. worker도 이 브랜치의 BuildRequest/GitHubSource를
포함한 버전이어야 한다. catalog의 `pinned-source-v1`은 해당 버전을 배포한 뒤에만 등록한다.

## 선택 규칙

- 실행기 URL·지역이 등록되어 있고, 요청 profile·허용 지역·필요 기능이 일치해야 한다.
- 월 예상 비용이 예산 이하이고 P95가 상한 이하여야 한다.
- 데이터는 기본 300초 이내여야 한다. 미래 시각 30초 초과, 누락/잘못된 값, 같은 profile의 중복 provider는 보류한다.
- `cost`: 비용 최소, 동률이면 P95 최소. JEV에 묻지 않는다(`priority_rules`).
- `latency`: P95 최소, 동률이면 비용 최소. JEV에 묻지 않는다(`priority_rules`).
- `balanced` **기본값**: 비용과 P95를 동등하게 고려하고 저장소 연관성을 참고한다.
  - 한 후보가 비용과 P95 모두 앞서면 그 후보다. JEV에 묻지 않는다(`dominant_candidate`).
  - 비용·P95가 서로 엇갈리는 후보(파레토 앞선)가 둘 이상일 때만 JEV에 묻는다. 선택지는 그 후보들과 `hold`뿐이라
    모델이 둘 다 열등한 후보를 고를 수 없다.
  - JEV 실패·확신 부족 시 `비용 / 후보 최소 비용 + P95 / 후보 최소 P95`가 최소인 후보를 선택한다(`jev_fallback`).
    분모는 각각 최소 $0.01, 1ms다. 완전 동률이면 provider 이름순으로 정한다.
- JEV가 높은 신뢰도로 `hold`를 반환하면 보류한다. 후보가 하나이거나 수동 provider를 지정하면 규칙으로 선택한다.
- 실행 직전 조건이 바뀌면 `changed_before_dispatch`로 보류한다. 다른 provider로 몰래 바꾸지 않는다.
- catalog를 설정하지 않았으면 `catalog_unconfigured`, 읽지 못했으면 `catalog_unavailable`로 보류한다.
  정상 응답에 후보가 없을 때의 `no_eligible_cloud`와 구분한다. 실행 직전 재확인에서 읽지 못해도 같은 사유다.

예산은 예상값에 대한 필터이며 실제 청구액 상한 기능은 아니다. 저장소 분석으로 가격이나 성능을 만들어내지 않는다.

## API

모든 경로는 내부 서버 전용이며 `Authorization: Bearer <CLOUD_API_TOKEN>`이 필요하다.
키 미설정/32자 미만이면 503, 인증 실패는 401이다. 본문은 최대 256 KiB다.

| Method | Path | 용도 |
|---|---|---|
| POST | `/api/cloud/plans` | policy만 받아 관측값으로 선택. 저장소 분석·배포 없음 |
| POST | `/api/cloud/repository` | `{policy, build}`를 받아 저장소 분석과 선택 결과 반환. 배포 없음 |
| POST | `/api/cloud/builds` | `{planId, requestId, build}`로 저장된 계획 재확인·빌드 시작 |
| GET | `/api/cloud/requests/{requestId}` | 영속 실행 기록 및 worker 종료 상태 확인 |
| POST | `/api/cloud/requests/{requestId}/reconcile` | 운영자 전용. 유실된 접수 응답의 buildId 복구 |
| GET | `/api/cloud/builds/{aws\|gcp}/{id}` | 선택한 실행기의 상태 조회 |

`/repository` 미리보기 요청 예시 (한도와 profile은 운영 환경에 맞춰 설정):

```json
{
  "policy": {
    "provider": "auto",
    "priority": "balanced",
    "profile": "api-small",
    "maxMonthlyCostUsd": 100,
    "maxP95Ms": 200,
    "regions": ["ap-northeast-2", "asia-northeast3"],
    "capabilities": ["container"]
  },
  "build": {
    "repoUrl": "https://github.com/SoftBank-team-lily/lily-blog-sample",
    "branch": "main",
    "appName": "sample",
    "database": "postgres"
  }
}
```

`/plans`에는 위 `policy` 객체 자체를 전달한다. 비용·지연 시간 상한, profile, 허용 지역은 필수다.
private 저장소는 `build.token`으로 전달한다. 모델에는 보내지 않고 저장소 읽기와 선택된 worker의 빌드에만 사용한다.

### 미리보기 → 실행 계약

선택 가능한 미리보기 응답은 `{planId, expiresAt, repository, decision}`이다. 보류/manifest 미지원에는 planId를 발급하지 않는다.
`POST /api/cloud/plans`는 기존의 정책 계산 전용 API이며 실행 가능한 planId를 발급하지 않는다.
`build.cloudProvider`가 명시되면 정책 provider와 일치해야 하며, auto 정책도 해당 provider로 제한한다.
실행 요청의 `build`는 미리보기와 같은 값이어야 한다. GitHub 토큰은 교체할 수 있으며 실행은 분석한 SHA에 고정된다.

```json
{
  "planId": "미리보기에서 받은 UUID",
  "requestId": "새 배포마다 한 번 생성한 UUID",
  "build": {
    "repoUrl": "https://github.com/SoftBank-team-lily/lily-blog-sample",
    "branch": "main",
    "appName": "sample",
    "database": "postgres"
  }
}
```

- 기존 `{policy, build}`만 보내는 `/api/cloud/builds` 요청은 이제 400이다. 이 실험 API 호출자는 새 계약으로 변경해야 한다.
- 환경변수/폴더/포트/브랜치/기타 빌드 설정 변경은 409 `plan_request_mismatch`, 만료는 `plan_expired`다.
- GitHub 토큰·환경변수 원문은 계획/요청 테이블에 저장하지 않는다. build 설정의 HMAC만 저장한다.
  HMAC 키는 CLOUD_API_TOKEN이므로 토큰 교체 후에는 새 미리보기를 받아야 한다.
- 신규 실행은 202 `{requestId, planId, decision, repository, build, statusPath, ...}`를 반환한다.
- 같은 requestId/planId 재전송은 기존 buildId를 반환(200)하며 worker POST를 반복하지 않는다.
  같은 requestId에 다른 planId를 쓰면 `request_id_conflict`다. 같은 ID 재전송의 build 본문으로 기존 실행을 수정하지 않는다.
- 같은 앱의 이전 실행이 진행 중이면 새 requestId도 409 `build_in_progress`다.
- 이전 worker 상태가 SUCCEEDED/FAILED/ROLLED_BACK/CANCELLED이면 새 requestId의 배포를 즉시 허용한다. 10분 대기가 없다.
- 최초 접수 이후 provider 변경은 `provider_change_requires_migration`으로 막는다. 새로운 미리보기에서 기존 provider를 명시한다.
- worker가 명확히 거절한 요청은 REJECTED로 남긴다. 수정 후 새로운 requestId로 다시 요청한다.
- 시간 초과·응답 유실·접수 후 DB 저장 실패는 DISPATCHING 상태를 보존한다. 자동 만료·다른 provider 재시도는 없다.
- 테이블/권한/연결 장애는 503 `cloud_state_unavailable`이다. 영속 기록 없이 worker를 시작하는 우회 경로는 없다.

### 접수 응답 유실 복구

운영자가 worker 이력에서 실제 요청의 buildId를 확인한 후
`POST /api/cloud/requests/{requestId}/reconcile`에 `{ "buildId": "확인한 빌드 ID" }`를 보낸다.
worker의 appName·commit·createdAt(요청 기록 시각 이후)을 대조한 뒤 연결한다. 이후 `/requests/{requestId}`로 상태를 갱신한다.
동일한 앱/커밋으로 과거에 만든 빌드가 아닌 해당 실행의 ID인지 운영자가 확인해야 한다. 두 서버의 시각 동기화가 필요하다.
worker 접수 자체가 없었다면 API가 자동으로 다시 보내지 않는다. 진행 중 작업이 없는지 확인한 후 운영자가 상태를 정리해야 한다.
원격 POST와 DynamoDB는 하나의 트랜잭션이 아니므로 이 불확실한 상태를 완전히 없애려면 worker 자체의 요청 ID 처리가 추가로 필요하다.

상태 응답은 id/appName/status/url/stage/stageName/createdAt/updatedAt/commit만 반환한다.
전체 로그·AI 수정 진행 이벤트·앱 삭제/롤백/DB 이전은 이 프록시에 아직 연결하지 않았다.

## 기존 데이터·서비스·운영 조건

참고한 [클라우드 철학 비교 글](https://wikidocs.net/359127)의 질문을 다음 입력과 근거로 구체화했다.
특정 클라우드가 항상 유리하다는 가중치는 추가하지 않았다.

| 비교 관점 | 이번 구현 |
|---|---|
| 기존 데이터 위치 | 운영자가 `context.dataProvider`를 지정. 필요하면 같은 클라우드 배포를 필수로 설정 |
| 서비스 조합과 데이터/AI 통합 | S3·Bedrock·BigQuery·Vertex AI·저장소·메시징 SDK 단서를 분석 |
| 기업 계정·서비스 연동 | MSAL·Azure SDK 감지 시 통합 검토 항목 반환. 자동 Azure 배포는 지원하지 않음 |
| 단일 클라우드 운영 부담 | `existingProvider`와 `singleCloud`로 기존 클라우드 유지 조건 지정 |
| 필요한 관리형 서비스 | `requiredServices`를 catalog의 검증된 접근 기능과 대조 |

`policy`에 선택적으로 다음 객체를 추가할 수 있다. 기존 요청은 그대로 유효하다.

```json
{
  "context": {
    "dataProvider": "gcp",
    "keepDataLocal": true,
    "existingProvider": "gcp",
    "singleCloud": true,
    "requiredServices": ["gcp-bigquery"]
  }
}
```

위 예시는 GCP 데이터, 단일 GCP 운영, BigQuery 접근이 **확인된 프로젝트의 예시**다.
현재 Lily의 실제 환경 설정값이 아니다. GCP 실행기가 없는 현재 환경에 적용하면 보류된다.

| 필드 | 값과 처리 |
|---|---|
| `dataProvider` | `aws`, `gcp`, `azure`, `onprem`, `unknown`. 생략하면 미확인 |
| `keepDataLocal` | true면 데이터와 같은 provider만 허용. 위치 미확인 시 `data_location_required`로 보류 |
| `existingProvider` | `aws`, `gcp`, `azure`, `none`, `unknown`. 기존 운영 클라우드 |
| `singleCloud` | true면 existingProvider만 허용. 미확인/none이면 `existing_cloud_required`로 보류 |
| `requiredServices` | 실제 필요한 접근 기능 이름 목록. catalog capabilities에 모두 있어야 함 |

필수 조건을 어긴 후보의 제외 이유는 `data_locality_required`, `single_cloud_required`,
`required_service_unavailable`다. 서로 충돌하는 조건은 자동 완화하지 않는다.
`azure` 단일 운영이나 `onprem` 데이터 위치 유지가 필수이면 현재 AWS/GCP 후보는 모두 제외된다.

`keepDataLocal`은 **provider 일치 조건**이다. 같은 국가·지역·네트워크나 법적 데이터 거주 요건을 보장하지 않는다.
지역은 기존 `regions` 조건으로 제한하고 실제 네트워크·데이터 경로는 운영 설정에서 확인한다.
두 boolean의 기본값은 false다. 필수가 아닌 데이터 위치와 기존 provider는 JEV의 판단 참고 정보이며,
JEV 실패 시에는 기존 비용·P95 균형 계산을 유지한다. 임의의 네트워크 비용이나 운영 숙련도 점수를 만들지 않는다.

### SDK 단서와 확인된 운영 사실의 구분

`repository.serviceHints`는 발견된 의존성에서 만든 확인 대상이다. 예: `aws-s3`, `aws-bedrock`,
`gcp-bigquery`, `gcp-vertex-ai`, `gcp-storage`, `gcp-pubsub`, `aws-sagemaker`, `entra-integration`.
`repository.reviewItems`에는 데이터 위치·운영 환경·관리형 서비스 접근·네트워크 비용 등의 확인 항목이 담긴다.
Azure/기업 계정 SDK를 발견한 경우 이식성이 확인되기 전까지 `portable` 응답을 `unknown`으로 낮춘다.

서비스 단서는 자동으로 requiredServices가 되지 않는다. 선택적 SDK, 사용하지 않는 의존성, 외부 서비스 접근일 수 있다.
운영자가 사용 여부를 확인한 뒤 필수 조건으로 등록한다. 예를 들어 AWS 실행기에서 BigQuery를 사용할 수도 있으므로
`gcp-bigquery`라는 접근 기능 이름만으로 GCP 배포를 강제하지 않는다. 필요하면 keepDataLocal도 명시한다.
catalog의 해당 기능은 네트워크·권한·서비스 연결이 확인된 worker/profile에만 등록한다.
선택 API가 직접 연결을 탐색하거나 계정 권한을 검증하는 것은 아니다.

비용 추정에는 데이터 이동이 포함되어야 한다. [AWS 데이터 전송 계획 지침](https://docs.aws.amazon.com/wellarchitected/latest/cost-optimization-pillar/plan-for-data-transfer.html)과
[Google Cloud의 클라우드 간 연결 지침](https://docs.cloud.google.com/architecture/patterns-for-connecting-other-csps-with-gcp)을 참고해
측정 위치·데이터 경로·전송량을 catalog 산정 조건에 포함한다. 코드가 서비스 가격을 하드코딩하지는 않는다.

## 환경변수

| 변수 | 설정 위치/용도 |
|---|---|
| `CLOUD_STATE_TABLE` | 계획/요청/앱 상태용 별도 DynamoDB 테이블. 미설정이면 계획 저장·실행 503 |
| `CLOUD_API_TOKEN` | 선택 API 인증용 서버 비밀값, 최소 32자. 프론트의 서버 코드에서만 사용 |
| `JEV_API_KEY` | 저장소 연관성·최종 후보 선택. 없으면 규칙 기반 연관성·선택 |
| `CLOUD_JEV_MODEL` | JEV 모델 이름. 비우면 lily-jev 기본값 `jev-latest`(버전 미고정) |
| `CLOUD_JEV_MIN_CONFIDENCE` | 연관성·선택 답을 받아들일 최소 확신도. 기본 0.8, 0 초과 1 이하가 아니면 시작하지 않음 |
| `CLOUD_JEV_CACHE_SECONDS` | 같은 상태의 JEV 답을 다시 쓰는 시간. 기본 900, 0이면 끔 |
| `CLOUD_CATALOG_URL` | 운영자가 제공하는 관측 snapshot GET API 전체 URL |
| `CLOUD_CATALOG_TOKEN` | catalog Bearer 토큰, 필요할 때 설정 |
| `CLOUD_MAX_AGE_SECONDS` | 기본 300, 허용 1~3600 |
| `CLOUD_AWS_URL`, `CLOUD_AWS_REGION` | AWS worker base URL와 실제 지역 |
| `CLOUD_AWS_TOKEN` | AWS worker 앞단 인증이 요구할 때 설정 |
| `CLOUD_GCP_URL`, `CLOUD_GCP_REGION` | GCP worker base URL와 실제 지역. 미구축이면 비워 둠 |
| `CLOUD_GCP_TOKEN` | GCP worker 앞단 인증이 요구할 때 설정 |

worker URL은 브라우저 입력으로 받지 않는다. worker는 자체 `/api/builds`를 처리하는 builder이며,
선택 API를 다시 호출하는 URL을 넣으면 안 된다. 운영 네트워크 접근 제어와 HTTPS/서비스 인증을 적용한다.
선택 API 토큰은 기존 worker의 `/api/builds`를 보호하지 않으므로 worker 앞단 접근 제어는 따로 필요하다.

## 상태 테이블 준비

- 테이블 파티션 키는 `pk`(String), 정렬 키는 없다. `PLAN#uuid`, `REQUEST#uuid`, `APP#appName` 항목을 사용한다.
- 기존 `lily-builds` 테이블과 분리한다. 기존 이력 조회가 전체 테이블을 scan하기 때문이다.
- region/로컬 endpoint는 기존 `lily.builder.dynamodb` 설정을 공유한다. 운영에서는 IAM 역할 인증을 사용한다.
- 테이블을 자동 생성하지 않는다. 인프라에서 테이블과 해당 테이블의 GetItem/PutItem/TransactWriteItems 권한을 준비한다.
- `expiresAt` TTL은 계획에만 적용할 수 있다. API에서도 만료를 검사하므로 TTL 삭제 지연과 무관하다.
- 요청/앱 항목은 자동 삭제하지 않는다. 중복 방지와 provider 유지 근거이므로 임의 TTL을 걸지 않는다.
- 같은 앱 이름은 플랫폼 전체에서 유일해야 한다. frontend 서버가 프로젝트 소유권과 planId 연결을 검증해야 한다.

## Catalog 계약

서버가 GET으로 읽는 응답은 `{ "candidates": [...] }`다. 최대 128 KiB, 최대 100개.
가격/지연 시간 수집기는 이 브랜치에 포함되지 않는다. 다음은 **실제 시장 가격이 아닌 형식 예시**다.
`observedAt`은 수집 시각으로 갱신해야 한다.

```json
{
  "candidates": [{
    "provider": "aws",
    "region": "ap-northeast-2",
    "profile": "api-small",
    "monthlyCostUsd": 40,
    "p95Ms": 80,
    "available": true,
    "capabilities": ["container", "postgres", "pinned-source-v1"],
    "observedAt": "2026-10-03T06:00:00Z",
    "evidenceId": "aws-api-small-001"
  }]
}
```

같은 profile은 CPU/RAM/인스턴스 수, 요청량, 측정 위치·기간, DB와 네트워크 비용 가정을 같게 맞춘다.
월 예상 총액에는 비교 대상에 포함된 compute·DB·네트워크 비용을 일관되게 포함한다.
`evidenceId`로 원본 가격표·계산식·관측 기록을 추적할 수 있어야 한다.
요청자의 profile을 선택된 worker에서 실제 적용하는 자원 설정과 맞춰야 한다. 현재 worker는 BuildRequest의 profile을
받지 않으므로 서로 다른 규모를 광고하지 말고 worker 기본 자원 설정에 해당하는 profile만 등록한다.

provider당 실행기는 한 지역을 지원한다. GPU/BigQuery 연결 가능 여부 등은 준비된 기능만 capabilities에 넣는다.
요청에서 `capabilities:["gpu"]`를 요구할 수 있지만 코드가 GPU를 자동 추론하거나 GPU 노드를 만들지는 않는다.
`database:auto`는 보수적으로 postgres와 mysql 모두를 요구한다. DB 종류를 알면 명시해 후보를 좁힌다.

## GCP 준비와 프론트 연결 순서

1. 현재는 GCP 변수와 catalog GCP 후보를 비워 둔다. 저장소 affinity가 gcp여도 AWS 또는 보류 결과가 정상이다.
2. 팀원의 GCP 데이터 플레인 연결 코드를 함께 병합했다. 선택 실행기는 BuildRequest에 `cloudProvider=AWS|GCP`를 명시한다.
   하나의 builder가 두 클라우드를 처리하도록 구성했다면 CLOUD_AWS_URL/CLOUD_GCP_URL에 같은 builder URL을 쓸 수 있다.
   선택 API가 아닌 기존 `/api/builds`로 전달되므로 재귀 호출은 없다. Cloud Run 어댑터는 없다.
3. GCP 대상 worker에 `GCP_CICD_URL`, `GCP_REGISTRY`, `GCP_REGISTRY_AUTH_SECRET`(기본 `gcp-pull`)을 설정한다.
   Kaniko가 사용할 유효한 dockerconfigjson Secret을 준비한다. GCP 설정이 없으면 AWS로 대신 배포하지 않는다.
4. DB/버스팅/주소 전환을 사용하는 경우 `GCP_PROVISIONER_URL`, `GCP_PROVISIONER_API_TOKEN`, GCP ingress/origin/터널 설정도 준비한다.
   클러스터 권한·네트워크·자원 설정과 실제 기능은 운영 환경에서 검증해야 한다. 메타데이터 저장은 기존 DynamoDB를 사용한다.
5. 실제 관측/가격 수집기를 연결하고 확인된 기능만 catalog에 게시한다. `available`은 실행기 상태를 반영해야 한다.
6. frontend 서버가 사용자 로그인·프로젝트 소유권을 확인한 뒤 이 내부 API를 호출한다.
7. 사용자에게 repository 신호·분석 한계·decision 후보/제외 이유를 보여 준다.
8. 미리보기의 planId/만료 시각과 실행용 requestId를 프로젝트 소유권에 연결해 저장한다. 재전송 시 같은 requestId를 사용한다.
   최초 배포 결과의 provider/region/build ID/commit/decision/evidenceId를 프로젝트 DB에 저장하고,
   상태 조회도 선택된 provider로 보낸다. 기존 앱의 재배포는 저장된 provider에 고정한다.

controller는 이 API로 기록된 앱 상태를 확인하지만 사용자별 소유권과 기존 경로로 배포된 앱은 알지 못한다. frontend 연결 시
소유권 검사와 최초 배포 여부 검사가 필요하다. ONPREM_ONLY·standby·DB import 요청은 이 API에서 거절한다.
기존 앱/DB의 AWS↔GCP 이동은 데이터 복제·전환·롤백 절차를 갖춘 별도 작업으로 구현해야 한다.

## 테스트

JDK 21과 형제 폴더 `../lily-jev` 또는 프로젝트 설정에 맞는 composite build 경로가 필요하다.
`CachedJev`와 모델 이름 설정이 있는 lily-jev(`feature/jev-reuse`의 `afe6e85` 이후)가 있어야 컴파일된다.
이미지 빌드는 Dockerfile의 `JEV_REF` 커밋을 받으므로 lily-jev 커밋을 GitHub에 올린 뒤에 빌드한다.

```sh
./gradlew test --tests 'com.lily.builder.cloud.*'
```

실제 JEV 테스트는 `RUN_JEV_LIVE=true`, `JEV_API_KEY`를 안전하게 환경변수로 주입한 경우에만 실행한다.
공개 `lily-blog-sample`의 manifest를 실제 읽는 테스트와 합성 가격/P95로 선택하는 테스트가 포함된다.
이 테스트는 클라우드 리소스를 생성하거나 실제 배포를 시작하지 않는다.

자동 테스트 범위: 정책 필터·균형 기본값·잘못된 모델 응답·JSON 직렬화·토큰·본문 제한·실행 전 재확인,
worker HTTP 전달/상태 조회/재시도 방지, 저장소 rootDir·SHA 고정·모델 입력 비밀값 제외,
배포와 같은 앱 폴더 분석, JEV 호출 조건(우선순위·우세 후보·SDK 단서), 미리보기·배포의 같은 결론,
catalog 미설정·장애 사유, 같은 앱 중복 전송, Java AWS SDK·Maven·주석 처리.
GCP 실제 배포·실제 비용 절감·실사용 성능은 GCP 환경과 관측 수집기를 연결한 뒤 별도 검증해야 한다.

### 2026-10-03 검증 기록

- Docker JDK 21에서 전체 `./gradlew test`: 194개 중 193개 통과, 1개 건너뜀, 실패 0개.
- `RUN_JEV_LIVE=true`: 합성 가격/P95의 AWS 선택과 공개 저장소 분석을 실제 JEV API로 확인.
- `lily-blog-sample` SHA `1b24f0be9717937833280eb00632a649bb5ea1b6`: `build.gradle`에서
  Spring Boot·PostgreSQL 추출. 최종 실행의 JEV 응답은 `portable`, 신뢰도 0.83이었다.
- 앞선 실행에서는 같은 분류의 신뢰도가 0.72여서 `unknown`으로 처리되었다.
  모델 신뢰도는 실행마다 달라질 수 있으며 실제 정확도나 성능 측정값이 아니다.
- 실제 worker로 배포하지 않았다. AWS/GCP 라우팅은 mock HTTP worker로 검증했다.

### 비교 문서 반영 후 추가 검증

- 클라우드 모듈 테스트 35개 중 33개 통과, 실제 JEV 호출 2개는 환경변수를 주입하지 않아 건너뜀.
- 데이터 위치/단일 운영/필수 서비스 조건, 조건 충돌, 미지원 환경, 실행 직전 조건 유지,
  API 입력 검증과 context 전달, 기업 계정 SDK의 이식성 미확정 처리를 확인했다.
- 이 보완 작업에서는 실제 JEV와 클라우드 worker를 호출하지 않았다.

### 리뷰 반영 후 검증 (2026-10-03)

- 반영: 분석 폴더를 배포와 같게, JEV는 규칙으로 정할 수 없을 때만, 같은 상태의 답 재사용, catalog 장애 사유 구분,
  같은 앱 중복 전송 방지, Java AWS SDK·Maven·주석 처리, 확신도·모델 설정.
- Docker JDK 21에서 전체 `./gradlew test`: 214개 중 210개 통과, 4개 건너뜀(실제 JEV 3개, 기존 1개), 실패 0개.
- lily-jev `feature/jev-reuse`: 10개 통과.
- 실제 JEV 테스트(`CloudLiveTest`)는 키가 없어 실행하지 않았다. 이제 일반 의존성만 있는 `lily-blog-sample`은 JEV에 묻지 않고
  `portable`이어야 하며, 실제 JEV 호출은 합성 GCP SDK manifest와 비용·P95가 엇갈리는 후보로 확인하도록 바꿨다.

## 영속 계획·동시 요청 병합 통합 검증

builder 232개 통과·4개 제외, lily-jev 12개 통과. DynamoDB Local에서 계획 직렬화·다른 인스턴스 읽기, 동시 선점, 상태 비교 갱신을 확인했다. 실제 카탈로그·GCP 환경을 사용한 운영 배포 검증은 포함하지 않았다.
