# lily-builder

GitHub 주소를 받아 이미지를 빌드하고 lily-cicd 로 배포를 요청하는 모듈 · SoftBank Hackathon 2026 · Team Lily

> 초안. 전체 흐름이 도는지 확인하기 위한 버전이다. 배포 이력은 DynamoDB `lily-builds`에 둔다.

```
배포 폼 (/) → POST /api/builds
  → Kaniko Job (k3s)   GitHub 에서 clone → Dockerfile 빌드 → 레지스트리 push
  → lily-cicd          POST /api/deployments (blue-green / canary, DB 준비 포함)
  → 접속 URL
```

- 레포 주소만 있으면 된다. Dockerfile 이 없으면 빌드 파일(pom.xml, build.gradle, package.json, requirements.txt, pyproject.toml, go.mod, index.html)을 보고 만든다
- 폴더를 비웠는데 루트에 빌드 파일이 없으면 최상위 폴더를 본다. 앱 폴더가 하나면 그 폴더를 빌드한다.
  서버 앱 하나와 정적 프론트 하나(예: `backend/` + `frontend/`)면 한 이미지로 묶어 주소 하나로 띄운다
  - 앞의 Caddy 가 `/api`·fetch 요청은 백엔드로, 파일·페이지는 프론트 빌드 결과로 보낸다. 같은 출처라 CORS 가 필요 없다
  - 프론트 코드의 `http://localhost:{백엔드 포트}` 는 빌드 전에 지워 같은 주소로 부르게 한다
  - DB·actuator 는 백엔드 폴더로, 마이그레이션은 `{백엔드 폴더}/src/main/resources/db/migration` 에서 찾는다
  - 그 밖의 조합(서버 둘 등)은 폴더 목록과 함께 실패한다. 폼의 "폴더" 로 하나를 고른다
- 빌드는 Kaniko 가 k3s 안에서 한다. 이 서버는 소스를 받지 않는다
- private 레포는 토큰을 넣는다. 빌드 동안만 k3s Secret 으로 Kaniko 에 넘기고 끝나면 지운다
- DB 를 고르면 lily-cicd 가 lily-db-provisioner 로 DB 를 만들고 접속 정보를 앱 환경변수로 넣는다
- 마이그레이션은 같은 커밋의 `src/main/resources/db/migration` (Flyway V/U SQL) 에서 읽는다.
  `{rootDir}/db/pgroll` 바로 아래에 `{번호}_{설명}.yaml|json` 이 있으면 SQL 대신 그 파일들을 보내고, lily-cicd 가 pgroll 로 무중단 적용한다
  (lily-cicd `docs/schema-migration.md` 7 절). 온프레미스 에이전트가 PostgreSQL 앱이면 pgroll 파일을 에이전트로 보내고 에이전트가 적용한다
  (`pgroll` 기능을 알리지 않은 에이전트면 거절). DB 가 RDS(`cloud`)면 보내기 전에 프로비저너로 pgroll 을 켠다

## API

### 저장소 분석 기반 AWS/GCP 선택

`POST /api/cloud/repository`로 배포할 앱 폴더의 의존성에서 클라우드 연관성을 판단하고
(AWS·GCP SDK 단서가 있을 때만 JEV에 묻는다), `POST /api/cloud/builds`로 예산·P95·실행기 준비 여부를 검사한 뒤
배포 대상을 선택할 수 있다. 기본 정책은 비용·속도 균형이며, 규칙으로 정할 수 없을 때만 JEV가 고른다.
실제 가격/관측 catalog와 클라우드별 worker 연결이 필요하며,
GCP 리소스 생성과 프론트 연결은 별도 작업이다. [API·환경변수·연결 순서](docs/cloud-placement.md). [JEV 적용 흐름과 팀 공유 설명](docs/jev-flow.md).
데이터 위치·기존 클라우드 유지·필수 서비스 접근 조건은 `policy.context`로 지정한다.
미리보기의 `planId`와 배포별 `requestId`로 실행하며, `CLOUD_STATE_TABLE`에 계획과 중복 방지 기록을 저장한다.
저장소의 서비스 SDK 단서와 계정 연동 검토 항목도 응답에 포함된다.

### 기존 빌드 API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/builds` | 빌드·배포 시작. 바로 `202` 와 id 를 돌려준다 |
| POST | `/api/detect` | 배포 전 DB 감지. 본문 `{repoUrl, branch?, token?, rootDir?}` → `{database, databaseSource, dir, apps, config, problem}` (`database` 는 postgres / mysql / null. `apps` 는 루트에 앱이 없을 때 폴더 후보, `config` 는 앱이 기동할 때 읽는 설정 키와 채울 방법, `problem` 은 폴더를 하나로 못 정한 이유). 빌드하지 않는다. 브랜치·앱을 못 찾으면 `422` |
| GET | `/api/builds/{id}` | 상태 (`QUEUED` → `BUILDING` → `DEPLOYING` → `SUCCEEDED` / `FAILED` / `ROLLED_BACK` / `CANCELLED`), 진행 단계, canary 판정, 단계별 로그, 이미지, URL |
| POST | `/api/builds/{id}/cancel` | 진행 중인 배포 취소 → `202` 와 `CANCELLED` 빌드. 클라우드는 lily-cicd 로 넘기기 전(`QUEUED`·`BUILDING`, 빌드 중이면 Kaniko Job 삭제), 온프레미스는 에이전트에 `cancel` 을 보내고 바로 닫는다 (에이전트는 트래픽을 바꾸기 전이면 후보를 지운다. 끊겨 있으면 기록만 닫는다). 끝났거나 `DEPLOYING` 인 클라우드 빌드는 `409`, 없으면 `404` |
| GET | `/api/builds` | 배포 이력 (최신순) |
| GET | `/api/apps` | k3s 에 떠 있는 앱 |
| GET | `/api/apps/{app}/release` | 슬롯별 릴리스와 롤백 가능 여부 |
| POST | `/api/apps/{app}/rollback` | 직전 릴리스로. 본문 `{appOnly}` (되돌릴 수 없는 스키마면 앱만). 온프레미스 앱은 에이전트로 보낸다 |
| GET | `/api/apps/{app}/schema` | 스키마 이력. 클라우드 앱은 lily-cicd, 온프레미스 앱은 에이전트가 3초마다 보내는 상태에서 |
| POST | `/api/apps/{app}/schema/complete` | 롤백 창을 닫고 바로 complete. 온프레미스 앱은 에이전트에 `schema-complete` 를 보내고 202 |
| POST | `/api/apps/{app}/stop`, `/start` | 중지(모든 슬롯 0)·다시 시작. lily-cicd 로 넘긴다. 배포 중이면 `409` |
| DELETE | `/api/apps/{app}?database=` | 앱 삭제. `database=true` 면 DB 도 DROP. 앱 주소 CNAME(ALB)과 엣지 라우트도 지운다 |
| GET, PUT | `/api/apps/{app}/write-queue` | 엣지 쓰기 큐. GET 은 등록 경로·`snapshot`·상태별 건수·최근 요청·`routed`·`lastCheck`·`downSince`, PUT 은 준 값만 바꾼다: `{"queue":true}` 쓰기 큐 켜기(모든 POST)·끄기, `{"snapshot":false}` 읽기 사본 끄기, `{"paths":[...]}` 경로 직접 지정. 큐가 꺼져 있으면 `404` |
| GET, PUT | `/api/apps/{app}/address` | 공개 주소 `{app}.{존}` 의 거점 (`CLOUD` ALB / `ONPREM` 터널 / `NONE` / `OTHER`). PUT `{"home":"cloud"}` 는 ALB 로 되돌린다 |

```json
{
  "repoUrl": "https://github.com/SoftBank-team-lily/lily-blog-sample",
  "branch": "main",
  "rootDir": "",
  "token": "",
  "appName": "blog",
  "targetPort": 8080,
  "database": "postgres",
  "env": {"SPRING_PROFILES_ACTIVE": "prod"},
  "canaryPath": "/api/posts"
}
```

- `targetPort` 를 비우면 레포의 Dockerfile `EXPOSE`, 그것도 없으면 8080
- `database` 를 `"auto"` 로 주면 레포의 DB 드라이버를 보고 `postgres` / `mysql` / 없음 중에 정한다
- `readinessPath` / `livenessPath` 를 비웠는데 레포에 Spring actuator 가 없으면 `/` 로 확인한다

### 레포로 정하는 값 (`AppDetector`)

레포 주소만 받는 화면(lily-frontend)에서도 포트가 다르거나 DB 가 필요 없는 앱이 배포되게 하려고, 빌드할 커밋의 파일을 보고 정한다.
요청에 값이 있으면 그 값이 우선하고, 정한 값은 빌드 로그에 `detect: ...` 로 남는다. 파일은 rootDir 기준으로 raw 주소에서 읽는다 (API rate limit 에 안 들어감).

| 값 | 보는 곳 |
|---|---|
| 포트 | `Dockerfile` 의 첫 `EXPOSE` |
| DB | `prisma/schema.prisma` 의 provider → `build.gradle(.kts)` / `pom.xml` / `package.json` / `requirements.txt` / `pyproject.toml` / `go.mod` 의 드라이버 (`org.postgresql`, `pg`, `psycopg`, `pgx`, `mysql-connector`, `mysql2`, `pymysql` 등) |
| actuator | `build.gradle(.kts)` / `pom.xml` / `gradle/libs.versions.toml` 에 `actuator` 가 있는지 |

드라이버 이름으로만 보므로, 드라이버를 쓰지만 DB 가 필요 없는 앱(테스트용 의존성 등)은 DB 가 붙을 수 있다. 그럴 땐 `database` 를 직접 준다.
- `canaryPath`: 블루그린 전환 전 canary 판정 때 새 버전과 이전 버전에 보낼 경로. 비우면 readiness 경로 (lily-cicd `docs/canary-analysis.md`)
- 완료 주소는 lily-cicd 가 돌려준 `targetHostUrl` (`https://{appName}.{LILY_DEPLOY_DOMAIN}`, 지금은 `lilycloud.kr`)
- 플랫폼 존을 설정하면 클라우드 배포 뒤 `{app}.{존}` CNAME 을 ALB(`PLATFORM_CUTOVER_ORIGIN`)로 둔다. 이미 PC 터널이나 다른 레코드면 그대로 둔다. 실패해도 배포는 성공

### 진행 단계 (`stage`, `stageName`)

배포 화면의 여섯 단계. lily-cicd 배포 요청은 끝날 때까지 응답하지 않아서, 배포 중에는 1초마다
`GET /api/deployments/{app}/progress` 를 물어 `progress:` 로그로 남기고 그 로그로 단계를 정한다.

| stage | stageName | 근거 |
|---|---|---|
| 0 | 레포 확인 | `QUEUED`, 커밋 고정 |
| 1 | 빌드 | Kaniko |
| 2 | 새 버전 띄우기 | 이미지 푸시, 새 색 Deployment, Ready 대기 |
| 3 | 트래픽 10%로 새 버전 내보내기 | cicd `canary-traffic`. 화면 이름이다. 사용자 트래픽은 이전 색에 있고 cicd가 프로브한다 |
| 4 | 에러율·응답 시간 판정 | cicd `canary-analysis` |
| 5 | 트래픽 100%로 전환 | cicd `service` 이후, `SUCCEEDED` |

- 첫 배포처럼 판정을 건너뛰면 2 에서 5 로 넘어간다
- `ROLLED_BACK` 이면 stage 는 4 에 멈춘다. 새 버전은 지워졌고 트래픽은 이전 버전 그대로다
- `canary`: 판정 결과 한 줄. 예 `canary: PASS new 120 req, error 0.0%, p95 10ms / old 120 req, error 0.0%, p95 10ms`

## 온프레미스 배포 (에이전트)

사용자 PC 의 lily-on-premise 에이전트가 이 builder 에 소켓으로 붙고, builder 가 잡을 보낸다 (컨트롤 플레인).
lily-frontend 에서 "내 PC" 로 등록한 프로젝트가 이 경로로 배포된다.

```
lily-frontend ─ POST /api/agents/{key}/builds ─▶ builder ═ wss /api/agents/connect?token= ═▶ 에이전트 (사용자 PC)
              ◀─ GET /api/builds/{id} (클라우드와 같은 Build) ─┘     ◀═ hello / status ═╛
```

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/agents` | 토큰 발급 `{key, token}`. token 은 이때만 보인다 |
| GET | `/api/agents/{key}` | `connected`, `agentId`, `publicUrl`, `database`(DB 터널 여부), 접속·마지막 수신 시각 |
| POST | `/api/agents/{key}/builds` | 이 에이전트로 배포. 본문은 `POST /api/builds` 와 같다. 앱 이름은 `[a-z][a-z0-9-]{0,30}` |
| WS | `/api/agents/connect?token=` | 에이전트 접속 (lily-on-premise `CONTROL_PLANE_URL`). 외부에는 이 경로만 연다 |
| POST | `/api/apps/{app}/home` | 거점 전환 `{home: cloud\|onprem, migrateDatabase}`. `migrateDatabase` 면 앱 DB 도 옮긴다 (PostgreSQL) |
| POST | `/api/apps/{app}/home/cancel` | 진행 중인 거점 전환 취소 (`202`). 주소를 바꾸기 전 단계에서만 |
| GET, PUT | `/api/apps/{app}/burst` | 버스팅 상태 / `{enabled, cloudPercent 0~100}`. 에이전트에 보내고 반영은 다음 상태에서 보인다 |

- 토큰은 `{key}.{HMAC}` 라서 저장하지 않는다. 서명 키는 `AGENT_TOKEN_SECRET` (Secret `lily-agents`). 바꾸면 모든 토큰이 무효
- 배포 전에 클라우드와 같이 레포로 포트·헬스 경로·DB 를 정한다 (`AppDetector`). DB 가 필요한데 에이전트에 DB 터널이 없으면(hello `database=false`) 보내지 않고 이유와 함께 FAILED
- 에이전트 단계 → Build 상태: `BUILDING` → BUILDING, `STARTING`·`HEALTH`·`SWITCHING` → DEPLOYING, `SUCCEEDED`(url) / `FAILED`
- 에이전트가 연결돼 있지 않으면 바로 FAILED, `BUILD_TIMEOUT_SECONDS` 동안 응답이 없어도 FAILED
- 연결 정보는 메모리에만 있다. builder 가 재시작하면 에이전트가 5초 뒤 다시 붙는다
- ALB 가 60초 유휴 연결을 끊어서 25초마다 ping 을 보낸다. Ingress 는 lily-loadbalancer `manifests/lily-builder.yaml`
- 소켓 메시지: 에이전트 → builder `hello`, `status`, `burst-state`(3초마다 버스팅·거점·CPU·메모리·p95), `burst`(builder 호출 중계), `cloudflare`(DNS·터널 호출 중계), `remediate`. builder → 에이전트 `welcome`(존, DB 터널 인증서, 역방향 포트, 버스팅 설정), `job`, `rollback`, `home`, `home-cancel`, `burst`
- 에이전트 DB 위치가 내 PC·기존 DB 면 `ssh -R` 로 열 역방향 포트를 에이전트마다 하나 준다 (ConfigMap `lily-agent-db-ports`, `PLATFORM_TUNNEL_REVERSE_PORT_FROM`~`TO`). 대기 배포는 그 주소로 만든 `databaseEnv` 를 lily-cicd 에 보낸다

### 클라우드 버스팅 API (`/api/burst`)

에이전트가 부르는 경로. `Authorization: Bearer {BURST_API_TOKEN}` 이 필요하고 토큰이 비면 끈다. 플랫폼 연결 에이전트는 같은 호출을 소켓 `burst` 로 보낸다. Ingress 는 `deploy/k3s/lily-builder-burst.yaml`.

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/burst/apps/{app}/standby` | 클라우드 대기 배포. 끝나면 레플리카 0 (공개 주소가 클라우드면 유지) |
| GET | `/api/burst/builds/{id}` | 대기 배포 상태 |
| GET | `/api/burst/apps/{app}` | 클라우드 앱 준비 상태 |
| PUT | `/api/burst/apps/{app}/replicas` | 대기 레플리카 0~5 |
| POST | `/api/burst/apps/{app}/database` | 같은 RDS DB 의 터널용 접속 정보 (lily-db-provisioner `/env?host=&port=`) |

### PC 장애 시 클라우드로 (엣지 Worker + CNAME 전환)

PC 가 꺼지면 클라우드 대기 Pod 가 있어도 공개 주소가 PC 터널을 가리켜 끊긴다. 두 겹으로 막는다. 자세한 설계와 측정은 `docs/장애-자동-전환.md`.

```
평소     {app}.{존} → Worker lily-edge → CNAME 내용물(PC 터널) → 에이전트 프록시
PC 장애  {app}.{존} → Worker → 530·연결 실패·엣지 오류 페이지·1.5초 무응답 → {app}-cloud.{존} → ALB → 클라우드 대기 Pod
```

| 단계 | 구현 | 끊김 |
|---|---|---|
| 엣지 Worker 재시도 | `EdgeWorker`, `src/main/resources/edge/worker.js`. 대기 배포가 끝난 앱에 `{app}-cloud.{존}`(ALB 프록시 CNAME)을 둔다 | 요청 단위로 바로 넘긴다. 전환 순간 가장 느린 응답 약 1.6~1.8초 |
| 엣지 읽기 사본 | 같은 Worker. 온프레미스 배포가 끝난 모든 앱에 라우트 `{app}.{존}/*` 를 걸고(`EdgeWorker.attachRoute`) 주요 페이지를 열어 둔다(`EdgePrewarm`) | 클라우드도 못 받는 GET/HEAD 를 그 데이터센터의 마지막 공개 응답으로 200 |
| 엣지 쓰기 큐 | 같은 Worker + Durable Object (`edge/queue.js`, `EdgeQueue`). 앱이 등록한 경로의 POST 를 앱마다 하나인 DO 에 쌓는다 | PC 가 못 받는 POST 를 202 로 받아 두고, PC 가 돌아오면 받은 순서대로 다시 보낸다 |
| CNAME 전환 (예비) | `AgentFailover`. 에이전트가 `FAILOVER_GRACE_SECONDS` 동안 끊겨 있으면 클라우드 레플리카를 올리고 CNAME 을 ALB 로 | 유예 60초 + DNS 반영 |

- 재시도: 530·연결 실패는 모든 메서드, 엣지 오류 페이지·1.5초 무응답은 GET/HEAD/OPTIONS 만 (PC 가 받았을 수 있는 POST 를 두 번 처리하지 않는다)
- 한 번 실패하면 10초 동안 PC 를 건너뛴다 (isolate 메모리 + Cache API)
- 대기 배포 때 lily-cicd 에 `aliases: [{app}-cloud.{존}]` 를 보내 같은 Service 로 Ingress 규칙을 둔다
- `-cloud` 로 끝나는 앱 이름은 받지 않는다
- 버스팅·비율 슬라이더·거점 전환은 그대로 PC 프록시와 CNAME 이 맡는다
- CNAME 전환은 DB 가 PC 에 있는 앱(`local`·`external`)에는 하지 않는다. PC 가 꺼지면 DB 도 함께 꺼져 클라우드가 이어받을 수 없다
- 읽기 사본: PC 가 공개 GET 에 200 을 주면 Worker 가 그 응답을 Cache API 에 7일 둔다. PC 가 받지 못한 GET/HEAD 를 클라우드도 처리하지 못하면(대기 Pod 없음, DB 가 PC 에 있어 5xx) 사본으로 200(`X-Lily-Edge: snapshot`, `X-Lily-Snapshot-At`), 사본이 없으면 503 `Retry-After: 30`. 사본이 있으면 클라우드를 3초까지만 기다린다 (DB 가 PC 에 있는 대기 Pod 는 PC 가 꺼지면 무응답)
  - 쿠키·인증 없는 요청, Set-Cookie·`private`·`no-store` 없는 응답, `Vary` 는 `Accept-Encoding` 만, 허용한 query(`page`·`sort` 등)만 둔다. 쓰기는 사본으로 답하지 않는다
  - 사본은 데이터센터마다 따로이고 밀려날 수 있다. prewarm 은 builder 가 있는 서울 리전에서 연다
- 쓰기 큐: 등록 경로의 POST 는 평소에도 앱 DO 를 거친다 (큐가 비어 있으면 PC 로 바로). PC 가 못 받으면(530·연결 실패, 엣지 오류 뒤 `GET /` 재확인도 엣지 오류) 헤더·본문을 `QUEUE_KEY` 로 AES-GCM 암호화해 DO SQLite 에 쌓고 202 `X-Lily-Queued-Id`. 결과는 `GET /__lily_edge/queued/{id}`. 자세한 설계는 `docs/엣지-쓰기-큐.md`
- 배포 화면 체크박스: 온프레미스 배포 요청의 `edgeSnapshot`·`edgeQueue`(기본 둘 다 켜짐)를 배포가 끝난 뒤 앱 DO 설정에 넣는다. 읽기 사본을 끄면 Worker 가 사본을 저장·응답하지 않고 prewarm 도 하지 않는다. 값이 없는 요청은 앱의 지금 설정을 그대로 둔다
  - 장애를 한 번 보면 DO 가 장애 상태를 기억해 PC 확인(`GET /`)이 성공할 때까지 새 POST 를 바로 쌓는다. alarm 이 10~60초 간격으로 확인하고, 성공하면 받은 순서대로 다시 보낸다 (`Idempotency-Key`, `X-Lily-Replay`, `X-Lily-Received-At`)
  - 2xx·3xx 는 sent, 4xx 는 failed, 5xx·시간 초과는 다음 alarm 에 다시. 앱당 1,000건, 본문 1 MiB
  - 등록 경로는 앱 DO 에 둔다. builder 는 관리 주소 `lily-edge-queue.{존}`(AAAA `100::` 프록시 + Worker 라우트)으로 바꾼다. 앱을 지우면 큐도 지운다
  - DO 를 쓰려면 Cloudflare 계정에 workers.dev 서브도메인이 있어야 한다. 큐 바인딩 업로드가 거절되면 바인딩 없이 올라가 재시도·읽기 사본은 그대로 동작한다
- Worker 테스트: `./gradlew edgeTest` (Node 22 이상, 쓰기 큐 테스트는 `node:sqlite` 때문에 Node 24 이상. `node --test src/test/js/*.test.mjs`)

## 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `BUILDER_REGISTRY` | (필수) | 이미지 레지스트리. ECR 이면 `<계정>.dkr.ecr.ap-northeast-2.amazonaws.com` |
| `BUILDER_INSECURE` | `false` | http 레지스트리(로컬 테스트)면 `true` |
| `BUILDER_NAMESPACE` | `lily-builds` | Kaniko Job namespace |
| `CICD_URL` | `http://localhost:8090` | lily-cicd 주소 |
| `BUILD_TIMEOUT_SECONDS` | `900` | 빌드 최대 대기 |
| `PLATFORM_EDGE_ENABLED` | `false` | PC 장애 때 엣지 Worker 가 요청을 클라우드로 다시 보낸다. 플랫폼 Cloudflare 토큰에 Workers Scripts:Edit(계정), Workers Routes:Edit(존) 권한이 있어야 한다 |
| `PLATFORM_EDGE_SCRIPT_NAME` | `lily-edge` | Cloudflare 의 Worker 이름. builder 가 기동할 때 올린다 |
| `PLATFORM_EDGE_QUEUE_KEY` | (없음) | 엣지 쓰기 큐 키 (base64 32바이트, Secret `lily-edge-queue`). 쌓아 둔 헤더·본문 암호화와 관리 주소 토큰에 쓴다. 비우면 큐를 끈다. 바꾸면 쌓여 있던 요청을 풀지 못한다 |
| `FAILOVER_GRACE_SECONDS` | `60` | 에이전트가 이만큼 끊겨 있으면 CNAME 을 ALB 로 바꾼다. 0 이면 끈다 |
| `FAILOVER_REPLICAS` | `2` | 그때 올릴 클라우드 레플리카 |
| `BURST_API_TOKEN` | (없음) | `/api/burst` 토큰 (Secret `lily-burst`). 비우면 버스팅 API 를 끈다 |
| `PROVISIONER_URL` / `PROVISIONER_API_TOKEN` | (없음) | 버스팅 DB 접속 정보를 받을 lily-db-provisioner |
| `AGENT_TOKEN_SECRET` | (없음) | 에이전트 토큰 서명 키 (Secret `lily-agents`). 32자 미만이면 에이전트 API 를 끈다 |
| `AGENT_PING_SECONDS` | `25` | 소켓 ping 간격 |
| `PLATFORM_CLOUDFLARE_API_TOKEN` / `_ACCOUNT_ID` / `_ZONE_ID` / `_ZONE_NAME` | (없음) | 앱 주소 존. 에이전트 Cloudflare 호출 중계, 앱 CNAME, 엣지 Worker 에 쓴다 (Secret `lily-agent-platform`) |
| `PLATFORM_TUNNEL_SSH_HOST` / `_SSH_USER` / `_REMOTE_HOST` / `_REMOTE_PORT` | - / `lily-tunnel` / - / `5432` | 에이전트 DB 터널 (배스천, RDS) |
| `PLATFORM_TUNNEL_CA_KEY` / `_VALIDITY_HOURS` | (없음) / `24` | 에이전트 터널 인증서를 서명하는 SSH CA 개인키, 인증서 유효 시간 |
| `PLATFORM_TUNNEL_REVERSE_HOST` / `_REVERSE_PORT_FROM` / `_TO` | (없음) / `20000` / `20999` | 역방향 터널을 열 배스천 사설 IP와 포트 범위. 비우면 끈다 |
| `PLATFORM_BURST_INGRESS_HOST` / `_PORT` | (없음) / `80` | 에이전트가 넘친 요청을 보낼 클러스터 Ingress |
| `PLATFORM_CUTOVER_ORIGIN` | (없음) | 거점이 클라우드일 때 앱 CNAME 이 가리킬 ALB. 비우면 거점 전환을 하지 않는다 |
| `BUILDS_TABLE` / `DYNAMODB_ENDPOINT` / `AWS_REGION` / `DYNAMODB_CREATE_TABLE` | `lily-builds` / (AWS) / `ap-northeast-2` / `false` | 배포 이력 |
| `REMEDIATE_ENABLED` / `JEV_API_KEY` / `GROQ_API_KEY` / `REMEDIATE_FRONTEND_URL` / `REMEDIATE_TOKEN` | `false` / - | 로그 사고 diff 초안 (`POST /api/remediate/drafts`) |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | (없음) | 설정 분류·실패 진단 `AiAdvisor` (Secret `lily-ai`). 없으면 규칙만 쓴다 |

- ECR 이면 Kaniko 에 내장된 `ecr-login` 으로 인증한다. Kaniko 가 뜨는 노드의 IAM 역할에 ECR push 권한이 필요하다
- k3s 매니페스트: `deploy/k3s/lily-builder.yaml` (빌더는 `lily-builds` namespace 의 Job/Secret/ConfigMap/로그만 다룬다)

## 로컬 실행

```bash
# kubeconfig 가 있는 곳에서. 형제 폴더에 lily-jev 가 있어야 한다 (이미지는 Dockerfile 이 JEV_REF 커밋을 받는다)
BUILDER_REGISTRY=localhost:30500 BUILDER_INSECURE=true CICD_URL=http://localhost:8090 \n  DYNAMODB_ENDPOINT=http://localhost:8000 DYNAMODB_CREATE_TABLE=true gradle bootRun
# http://localhost:8070
```

## 아직 없는 것

- 인증 (누구나 배포 가능). `/api/burst` 와 에이전트 소켓만 토큰을 받는다

### JEV 코드 결함 판별 기준 로컬 테스트

`REMEDIATE_JEV_YES_THRESHOLD`로 수정안 생성을 허용하는 예 확률 기준을 조절한다. 기본은 `0.8`, 허용 범위는 `0.5`~`1`이다. 로컬 비교 테스트는 `.env.local`에 `REMEDIATE_JEV_YES_THRESHOLD=0.6`을 넣고 환경변수로 전달해 실행한다. 환경변수를 제거하면 기본값으로 돌아간다. 아니오 기준 `0.2`와 diff 검증은 유지한다. `.env.local`은 Spring Boot가 자동으로 읽지 않으므로 실행 환경에 별도로 주입해야 한다.

## 운영 원인 진단

observer가 수집한 지표·파드·로그 근거를 `POST /api/diagnoses`로 분석합니다. JEV는 근거가 있는 원인 후보 중 하나를 선택하고, builder는 근거 ID와 검토 조치를 반환합니다. 모델 장애나 키 미설정 시에는 `source: rules`로 관측 규칙을 사용합니다. 실행형 복구 기능과 별도로 동작합니다.

`DIAGNOSIS_API_TOKEN`이 있어야 API를 사용할 수 있고, JEV를 쓰려면 `JEV_API_KEY`를 추가합니다. 입력 제한, 응답 예시와 연동 방식은 [운영 진단 API](docs/diagnosis.md)를 참고하세요.
