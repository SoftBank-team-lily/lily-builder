# lily-builder

GitHub 주소를 받아 이미지를 빌드하고 lily-cicd 로 배포를 요청하는 모듈 · SoftBank Hackathon 2026 · Team Lily

> 초안. 전체 흐름이 도는지 확인하기 위한 버전이다. 진행 상태는 메모리에만 둔다.

```
배포 폼 (/) → POST /api/builds
  → Kaniko Job (k3s)   GitHub 에서 clone → Dockerfile 빌드 → 레지스트리 push
  → lily-cicd          POST /api/deployments (blue-green / canary, DB 준비 포함)
  → 접속 URL
```

- 사용자 레포에는 **Dockerfile** 만 있으면 된다. 백엔드/프론트가 한 레포에 있으면 폼의 "폴더" 에 Dockerfile 이 있는 폴더를 넣는다
- 빌드는 Kaniko 가 k3s 안에서 한다. 이 서버는 소스를 받지 않는다
- private 레포는 토큰을 넣는다. 빌드 동안만 k3s Secret 으로 Kaniko 에 넘기고 끝나면 지운다
- DB 를 고르면 lily-cicd 가 lily-db-provisioner 로 DB 를 만들고 접속 정보를 앱 환경변수로 넣는다

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/builds` | 빌드·배포 시작. 바로 `202` 와 id 를 돌려준다 |
| GET | `/api/builds/{id}` | 상태 (`QUEUED` → `BUILDING` → `DEPLOYING` → `SUCCEEDED` / `FAILED`), 단계별 로그, 이미지, URL |

```json
{
  "repoUrl": "https://github.com/SoftBank-team-lily/lily-blog-sample",
  "branch": "main",
  "rootDir": "",
  "token": "",
  "appName": "blog",
  "targetPort": 8080,
  "database": "postgres",
  "env": {"SPRING_PROFILES_ACTIVE": "prod"}
}
```

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

- Dockerfile 이 없는 레포 자동 감지 (build.gradle, package.json 보고 Dockerfile 생성)
- 빌드 기록 저장 (지금은 메모리)
- 인증 (누구나 배포 가능)
