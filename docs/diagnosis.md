# 운영 진단 API

`POST /api/diagnoses`는 observer가 모은 관측 근거에서 원인 후보를 고릅니다. JEV의 `choice` API를 사용하며, 설명과 조치는 서버 템플릿으로 작성합니다. PR 생성, 설정 변경, 재배포, 트래픽 변경, 롤백은 실행하지 않습니다.

## 연결

```yaml
lily:
  diagnosis:
    api-token: ${DIAGNOSIS_API_TOKEN:}
    jev-api-key: ${JEV_API_KEY:}
```

observer와 builder에 같은 `DIAGNOSIS_API_TOKEN`을 설정하고, observer의 builder 주소를 연결합니다. 토큰이 없으면 API는 503입니다. JEV 키가 없으면 분석은 관측 규칙으로 동작하며 `source: rules`를 반환합니다. 키는 서버 환경변수 또는 Kubernetes Secret에 저장합니다.

이 API는 서비스 간 통신용입니다. 사용자별 프로젝트 소유권을 확인하지 않습니다. 브라우저에 토큰을 전달하지 말고, 사용자 요청을 받는 서버에서 로그인과 프로젝트 소유권을 확인한 뒤 observer를 호출해야 합니다.

## 요청

헤더는 `Authorization: Bearer <DIAGNOSIS_API_TOKEN>`, `Content-Type: application/json`입니다.

```json
{
  "app": "blog",
  "namespace": "default",
  "observedAt": "2026-10-02T00:00:00Z",
  "evidence": [
    {"id": "pod-1", "source": "pods", "signal": "oom", "summary": "blog-blue: OOMKilled, 재시작 3회"}
  ],
  "missingSources": ["logs"]
}
```

| 필드 | 제한 |
|---|---|
| app, namespace | 소문자 DNS label, 최대 63자 |
| observedAt | ISO 8601 시각, 필수 |
| evidence | 최대 24건, null 항목 불가 |
| evidence.id | `[a-z][a-z0-9-]{0,63}`, 요청 내 중복 불가 |
| evidence.source, missingSources 항목 | `status`, `metrics`, `pods`, `logs`, `deployment` |
| evidence.signal | `low_traffic`, `high_error_rate`, `high_latency`, `pod_not_ready`, `oom`, `restarts`, `database_error`, `config_error`, `code_error`, `deployment`, `observation` |
| evidence.summary | 필수, 항목당 최대 2000자 |
| missingSources | 필수, 최대 5개, 중복 불가 |
| 전체 본문 | 최대 196608 bytes, Content-Length가 없어도 검사 |

## 응답

```json
{
  "app": "blog",
  "namespace": "default",
  "observedAt": "2026-10-02T00:00:00Z",
  "analyzedAt": "2026-10-02T00:00:01Z",
  "source": "ai",
  "category": "resources",
  "summary": "메모리 부족이 원인 후보입니다. 종료 사유와 메모리 제한을 확인해 주세요.",
  "evidenceIds": ["pod-1"],
  "recommendations": [
    {"action": "review_resources", "reason": "메모리 부족이 원인 후보입니다. 종료 사유와 메모리 제한을 확인해 주세요.", "evidenceIds": ["pod-1"]}
  ],
  "limitations": [
    "관측 근거에서 추정한 원인 후보입니다. 조치는 자동 실행하지 않습니다.",
    "수집하지 못한 자료: logs"
  ]
}
```

`source: ai`는 JEV가 허용된 후보를 선택했다는 뜻입니다. 자유 형식 설명을 생성했다는 뜻은 아닙니다. 판정 확신이 0.8~1.0이고 유한한 값이며 질문의 선택지에 속할 때만 사용합니다. 잘못된 타입, 다른 선택지, 낮은 확신, 호출 실패는 `source: rules`로 대체합니다. 판단을 보류할 때는 `category: unknown`입니다. 파드의 종료 사유와 누적 재시작은 과거 이력일 수 있어, 발생 시각과 현재 장애와의 관계를 확인해야 한다는 한계를 함께 표시합니다.

| 원인 후보 | 필요한 신호 | 검토 조치 |
|---|---|---|
| resources | oom | review_resources |
| database | database_error | check_database |
| configuration | config_error | check_configuration |
| application | code_error | review_code |
| unknown | 근거 부족, 모순 또는 JEV 판단 보류 | review_logs 또는 빈 목록 |

계약의 `traffic` 원인과 `review_traffic`, `review_rollback` 조치는 이후 근거가 마련될 때 확장할 수 있습니다. 현재는 응답 시간과 오류율만으로 트래픽 과부하를 판정하지 않습니다. 규칙은 위 표의 순서로 근거가 있는 첫 후보를 선택합니다. 자료가 없거나 원인 신호가 없으면 JEV를 호출하지 않습니다. 모든 근거 ID는 요청에 포함된 ID에서 서버가 정합니다.

## 처리 한계와 오류

- 로그는 인용된 자료로 전달하고, 비밀번호·토큰·URL 자격 증명·개인키 형식을 마스킹합니다. 비정형 개인정보까지 완전히 검출하는 기능은 아니므로 수집기에서도 필요한 근거만 보내야 합니다.
- 모델 출력 문장, 명령, 예외 메시지를 응답에 복사하지 않습니다. 응답 설명과 조치는 서버 템플릿이며 입력 로그 원문을 반환하지 않습니다.
- 기존 `HttpJev`는 연결 제한 2초, 요청 제한 4초이며 재시도하지 않습니다. 동일 근거의 캐시와 동시 요청 합치기는 observer가 담당합니다.
- 이 모듈의 JEV 연동은 코드와 모의 응답으로 검증합니다. 실제 운영 사용에는 토큰, JEV 키, 네트워크 연결이 필요합니다.

| HTTP | error | 뜻 |
|---|---|---|
| 400 | invalid_request | 요청 형식·개수·길이·ID 중복이 맞지 않음 |
| 401 | unauthorized | 서비스 토큰이 없거나 다름 |
| 413 | request_too_large | 전체 본문 제한 초과 |
| 503 | diagnosis_disabled | 서버의 진단 토큰 미설정 |

예: `{"error":"invalid_request","message":"진단 요청 형식을 확인해 주세요."}`. 인증 오류에는 `error`만 포함됩니다. JEV 미설정과 장애는 HTTP 오류가 아닌 200 규칙 분석이며 limitations에 이유를 표시합니다.

## 테스트

Java 21과 옆 폴더의 `lily-jev` 체크아웃이 필요합니다.

```sh
./gradlew test --tests '*DiagnosisTest' --tests '*DiagnosisApiTest'
./gradlew test
```

테스트는 JEV 인터페이스의 모의 응답으로 선택 검증, 인젝션 문장의 비실행, 비밀값 마스킹, 타임아웃·실패 대체 경로, 토큰 검사, 입력 제한을 확인합니다. 유료 모델이나 공유 클러스터에 요청하지 않습니다.
