# 저장소 분석과 AWS/GCP 배포 선택

## 구현 범위

`feature/cloud-placement`는 builder에 선택 API를 추가한다. 기본 정책은 **비용·속도 균형**이다.

1. GitHub의 커밋을 고정하고 `rootDir`의 의존성 파일을 읽는다.
2. 알려진 ML·데이터·웹·클라우드 SDK 의존성을 추출해 JEV가 클라우드 연관성을 판단한다.
3. 서버가 수집한 같은 작업 규모의 비용·P95와 배포 가능 여부로 후보를 제한한다.
4. 남은 후보를 JEV가 선택한다. 불확실하거나 호출에 실패하면 수치 기반 규칙을 사용한다.
5. 실행 직전에 관측값을 다시 확인하고 선택한 worker의 기존 `/api/builds`로 전달한다.
6. 같은 provider의 worker에서 상태를 조회한다.

현재 GCP 환경은 없다. **GCP worker·실제 가격/관측 데이터 수집기·프론트 호출은 별도 연결이 필요하다.**
이 브랜치는 GKE 리소스를 생성하지 않으며 기존 `/api/builds` 요청을 자동으로 이 경로로 바꾸지 않는다.
Groq의 코드 수정 기능도 이 API와 별개다. JEV는 저장소 특성과 배포 위치를 판단한다.

## 저장소를 어떻게 읽는가

지원 파일: `package.json`, `requirements.txt`, `pyproject.toml`, `pom.xml`,
`build.gradle`, `build.gradle.kts`, `go.mod`, `Cargo.toml`, `Gemfile`.

- `rootDir` 바로 아래의 파일만 분석한다. 모노레포에서는 실제 배포할 앱의 폴더를 지정한다.
- manifest당 64 KiB 제한. 접근 실패나 크기 초과는 분석 실패로 처리한다.
- manifest 원문, README, `.env`, 토큰, 환경변수, 임의 소스 코드는 JEV로 전송하지 않는다.
- 서버에서 정해 둔 의존성 이름만 추출해 전달한다. 저장소 코드를 실행하지 않는다.
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
JEV 응답 신뢰도가 0.8 미만이거나 유효하지 않으면 연관성은 `unknown`으로 남는다.

`sourceCommit`으로 분석한 SHA를 worker에 전달한다. worker도 이 브랜치의 BuildRequest/GitHubSource를
포함한 버전이어야 한다. catalog의 `pinned-source-v1`은 해당 버전을 배포한 뒤에만 등록한다.

## 선택 규칙

- 실행기 URL·지역이 등록되어 있고, 요청 profile·허용 지역·필요 기능이 일치해야 한다.
- 월 예상 비용이 예산 이하이고 P95가 상한 이하여야 한다.
- 데이터는 기본 300초 이내여야 한다. 미래 시각 30초 초과, 누락/잘못된 값, 같은 profile의 중복 provider는 보류한다.
- `cost`: 비용 최소, 동률이면 P95 최소.
- `latency`: P95 최소, 동률이면 비용 최소.
- `balanced` **기본값**: 비용과 P95를 동등하게 고려하고 저장소 연관성을 참고한다.
  JEV 실패 시 `비용 / 후보 최소 비용 + P95 / 후보 최소 P95`가 최소인 후보를 선택한다.
  분모는 각각 최소 $0.01, 1ms다. 완전 동률이면 provider 이름순으로 정한다.
- 모델이 비용과 P95 모두에서 열등한 후보를 고르거나 명시한 cost/latency 우선순위를 어기면 규칙으로 대체한다.
- JEV가 높은 신뢰도로 `hold`를 반환하면 보류한다. 후보가 하나이거나 수동 provider를 지정하면 규칙으로 선택한다.
- 실행 직전 조건이 바뀌면 `changed_before_dispatch`로 보류한다. 다른 provider로 몰래 바꾸지 않는다.

예산은 예상값에 대한 필터이며 실제 청구액 상한 기능은 아니다. 저장소 분석으로 가격이나 성능을 만들어내지 않는다.

## API

모든 경로는 내부 서버 전용이며 `Authorization: Bearer <CLOUD_API_TOKEN>`이 필요하다.
키 미설정/32자 미만이면 503, 인증 실패는 401이다. 본문은 최대 256 KiB다.

| Method | Path | 용도 |
|---|---|---|
| POST | `/api/cloud/plans` | policy만 받아 관측값으로 선택. 저장소 분석·배포 없음 |
| POST | `/api/cloud/repository` | `{policy, build}`를 받아 저장소 분석과 선택 결과 반환. 배포 없음 |
| POST | `/api/cloud/builds` | `{policy, build}`를 받아 분석·선택·재확인·빌드 시작 |
| GET | `/api/cloud/builds/{aws\|gcp}/{id}` | 선택한 실행기의 상태 조회 |

`/repository`, `/builds` 요청 예시 (한도와 profile은 운영 환경에 맞춰 설정):

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

배포 응답은 202 `{decision, repository, build, statusPath}`다. 저장소 분석 실패는 422,
선택 보류는 409다. worker 응답 문제는 502와 `retryable:false`를 반환한다.
응답이 끊겨도 worker에 이미 접수되었을 수 있으므로 **자동 POST 재시도·다른 클라우드 재전송을 하지 않는다.**
앱 이름으로 worker 이력을 먼저 확인한다. 호출자가 반복 POST하는 것까지 막는 영속 idempotency 저장소는 아직 없다.

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
| `CLOUD_API_TOKEN` | 선택 API 인증용 서버 비밀값, 최소 32자. 프론트의 서버 코드에서만 사용 |
| `JEV_API_KEY` | 저장소 연관성·최종 후보 선택. 없으면 unknown/규칙 기반 선택 |
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
2. GKE 등 Kubernetes 환경에 같은 builder/CI-CD worker 계약을 준비한다. Cloud Run 어댑터는 없다.
3. 현재 코드의 registry 인증 Secret 이름은 `ecr-pull`이다. GCP registry를 사용할 때도 이 이름의 유효한
   Docker registry 인증이 필요하다. ECR 저장소 자동 생성 코드는 ECR 주소에서만 동작한다.
4. worker의 Kubernetes 권한, registry 주소, 해당 클러스터의 `CICD_URL`, DB provisioner, 공개 ingress/DNS,
   네트워크 연결과 자원 설정을 준비한다. 현재 메타데이터 저장은 DynamoDB이므로 GCP worker도 해당 접근 설정이 필요하다.
5. 실제 관측/가격 수집기를 연결하고 확인된 기능만 catalog에 게시한다. `available`은 실행기 상태를 반영해야 한다.
6. frontend 서버가 사용자 로그인·프로젝트 소유권을 확인한 뒤 이 내부 API를 호출한다.
7. 사용자에게 repository 신호·분석 한계·decision 후보/제외 이유를 보여 준다.
8. 최초 배포 결과의 provider/region/build ID/commit/decision/evidenceId를 프로젝트 DB에 저장하고,
   상태 조회도 선택된 provider로 보낸다. 기존 앱의 재배포는 저장된 provider에 고정한다.

현재 controller는 사용자별 소유권이나 기존 앱 존재 여부를 저장/검증하지 않는다. 따라서 frontend 연결 시
소유권 검사와 최초 배포 여부 검사가 필요하다. ONPREM_ONLY·standby·DB import 요청은 이 API에서 거절한다.
기존 앱/DB의 AWS↔GCP 이동은 데이터 복제·전환·롤백 절차를 갖춘 별도 작업으로 구현해야 한다.

## 테스트

JDK 21과 형제 폴더 `../lily-jev` 또는 프로젝트 설정에 맞는 composite build 경로가 필요하다.

```sh
./gradlew test --tests 'com.lily.builder.cloud.*'
```

실제 JEV 테스트는 `RUN_JEV_LIVE=true`, `JEV_API_KEY`를 안전하게 환경변수로 주입한 경우에만 실행한다.
공개 `lily-blog-sample`의 manifest를 실제 읽는 테스트와 합성 가격/P95로 선택하는 테스트가 포함된다.
이 테스트는 클라우드 리소스를 생성하거나 실제 배포를 시작하지 않는다.

자동 테스트 범위: 정책 필터·균형 기본값·잘못된 모델 응답·JSON 직렬화·토큰·본문 제한·실행 전 재확인,
worker HTTP 전달/상태 조회/재시도 방지, 저장소 rootDir·SHA 고정·모델 입력 비밀값 제외.
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
