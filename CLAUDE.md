# lily-builder

이 저장소의 스킬은 `.claude/skills/`에 있다. Cursor는 같은 내용을 `.cursor/skills/`에서 읽는다.

비사소한 작업을 시작하기 전에 `.claude/skills/using-agent-skills/SKILL.md`를 읽고, 그 단계에 맞는 스킬의 `SKILL.md`를 따라라.

lily-builder는 Spring Boot API(`src/main/java`)와 정적 화면(`src/main/resources/static`)을 함께 가진다.

- 화면 작업은 `frontend-ui-engineering`과 `lily-frontend-design`, 브라우저 확인은 `browser-testing-with-devtools`
- API와 모듈 경계는 `api-and-interface-design`
- 동작 변경과 버그 수정은 `test-driven-development`
- 머지 전에는 `code-review-and-quality`, 보안이 닿으면 `security-and-hardening`
