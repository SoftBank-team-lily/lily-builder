# lily-builder

GitHub 주소를 받아 이미지를 빌드하고 lily-cicd 로 배포를 요청하는 모듈 · SoftBank Hackathon 2026 · Team Lily

> 초안. 전체 흐름이 도는지 확인하기 위한 버전이다. 진행 상태는 메모리에만 둔다.

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

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/builds` | 빌드·배포 시작. 바로 `202` 와 id 를 돌려준다 |
| POST | `/api/detect` | 배포 전 DB 감지. 본문 `{repoUrl, branch?, token?, rootDir?}` → `{database, databaseSource, dir}` (`database` 는 postgres / mysql / null). 빌드하지 않는다. 브랜치·앱을 못 찾으면 `422` |
| GET | `/api/builds/{id}` | 상태 (`QUEUED` → `BUILDING` → `DEPLOYING` → `SUCCEEDED` / `FAILED` / `ROLLED_BACK`), 진행 단계, canary 판정, 단계별 로그, 이미지, URL |

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
- 완료 주소는 `https://{appName}.apps.lilycloud.kr` (lily-cicd `LILY_DEPLOY_DOMAIN`)

### 진행 단계 (`stage`, `stageName`)

배포 화면의 여섯 단계. lily-cicd 배포 요청은 끝날 때까지 응답하지 않아서, 배포 중에는 1초마다
`GET /api/deployments/{app}/progress` 를 물어 `progress:` 로그로 남기고 그 로그로 단계를 정한다.

| stage | stageName | 근거 |
|---|---|---|
| 0 | 레포 확인 | `QUEUED`, 커밋 고정 |
| 1 | 빌드 | Kaniko |
| 2 | 새 버전 띄우기 | 이미지 푸시, 새 색 Deployment, Ready 대기 |
| 3 | 트래픽 10%로 새 버전 내보내기 | cicd `canary-traffic` |
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

- 토큰은 `{key}.{HMAC}` 라서 저장하지 않는다. 서명 키는 `AGENT_TOKEN_SECRET` (Secret `lily-agents`). 바꾸면 모든 토큰이 무효
- 배포 전에 클라우드와 같이 레포로 포트·헬스 경로·DB 를 정한다 (`AppDetector`). DB 가 필요한데 에이전트에 DB 터널이 없으면(hello `database=false`) 보내지 않고 이유와 함께 FAILED
- 에이전트 단계 → Build 상태: `BUILDING` → BUILDING, `STARTING`·`HEALTH`·`SWITCHING` → DEPLOYING, `SUCCEEDED`(url) / `FAILED`
- 에이전트가 연결돼 있지 않으면 바로 FAILED, `BUILD_TIMEOUT_SECONDS` 동안 응답이 없어도 FAILED
- 연결 정보는 메모리에만 있다. builder 가 재시작하면 에이전트가 5초 뒤 다시 붙는다
- ALB 가 60초 유휴 연결을 끊어서 25초마다 ping 을 보낸다. Ingress 는 lily-loadbalancer `manifests/lily-builder.yaml`

## 설정

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `BUILDER_REGISTRY` | (필수) | 이미지 레지스트리. ECR 이면 `<계정>.dkr.ecr.ap-northeast-2.amazonaws.com` |
| `BUILDER_INSECURE` | `false` | http 레지스트리(로컬 테스트)면 `true` |
| `BUILDER_NAMESPACE` | `lily-builds` | Kaniko Job namespace |
| `CICD_URL` | `http://localhost:8090` | lily-cicd 주소 |
| `BUILD_TIMEOUT_SECONDS` | `900` | 빌드 최대 대기 |

- ECR 이면 Kaniko 에 내장된 `ecr-login` 으로 인증한다. Kaniko 가 뜨는 노드의 IAM 역할에 ECR push 권한이 필요하다
- k3s 매니페스트: `deploy/k3s/lily-builder.yaml` (빌더는 `lily-builds` namespace 의 Job/Secret/ConfigMap/로그만 다룬다)

## 로컬 실행

```bash
# kubeconfig 가 있는 곳에서
BUILDER_REGISTRY=localhost:30500 BUILDER_INSECURE=true CICD_URL=http://localhost:8090 gradle bootRun
# http://localhost:8070
```

## 아직 없는 것

- 빌드 기록 저장 (지금은 메모리)
- 인증 (누구나 배포 가능)
