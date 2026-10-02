package com.lily.builder;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RemediateDraftTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String PATH = "src/main/java/com/acme/OrderService.java";
    private static final String SOURCE = """
            class OrderService {
                String label(String name) {
                    return name.toUpperCase();
                }
            }
            """;

    @Test
    void offSwitchDoesNotAskTheModel() {
        AtomicInteger calls = new AtomicInteger();
        PatchModel model = prompt -> {
            calls.incrementAndGet();
            return Optional.of(diff());
        };

        Map<String, Object> off = RemediateController.response(null, request());

        assertThat(off).containsEntry("status", "off");
        assertThat(calls).hasValue(0);
        assertThat(model).isNotNull();
    }

    @Test
    void noOrFailedJevDoesNotAskTheModel() {
        AtomicInteger calls = new AtomicInteger();
        RemediateDraftService service = service(Optional.empty(), prompt -> {
            calls.incrementAndGet();
            return Optional.of(diff());
        });

        Map<String, Object> rejected = service.draft(request());

        assertThat(rejected).containsEntry("status", "rejected");
        assertThat(calls).hasValue(0);
    }

    @Test
    void appliesOnlyTheAllowedFile() {
        RemediateDraftService service = service(Optional.of(true), prompt -> Optional.of(diff()));

        Map<String, Object> draft = service.draft(request());

        assertThat(draft).containsEntry("status", "draft");
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) draft.get("files");
        assertThat(files.get(PATH)).contains("name == null");
    }

    @Test
    void rejectsAWorkflowDiff() {
        String workflow = """
                --- a/.github/workflows/ci.yml
                +++ b/.github/workflows/ci.yml
                @@ -1,1 +1,1 @@
                -name: ci
                +name: changed
                """;
        DiffCheck.Result result = DiffCheck.check(workflow, Map.of(".github/workflows/ci.yml", "name: ci\n"),
                Set.of(".github/workflows/ci.yml"));

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("고칠 수 없는 경로");
    }

    @Test
    void jevYesNoThreshold() {
        assertThat(new JevDefectGate((state, question) -> Optional.of(new com.lily.jev.Answer(null, 0.9, 0.9)))
                .codeDefect("log")).contains(true);
        assertThat(new JevDefectGate((state, question) -> Optional.of(new com.lily.jev.Answer(null, 0.1, 0.1)))
                .codeDefect("log")).contains(false);
        assertThat(new JevDefectGate((state, question) -> Optional.empty()).codeDefect("log")).isEmpty();
    }

    private static RemediateDraftService service(Optional<Boolean> defect, PatchModel model) {
        return new RemediateDraftService((repo, token, commit, path) -> PATH.equals(path) ? SOURCE : null,
                log -> defect, model);
    }

    private static RemediateDraftService.DraftRequest request() {
        return new RemediateDraftService.DraftRequest("https://github.com/acme/blog", null, COMMIT, "NullPointerException",
                java.util.List.of(PATH));
    }

    private static String diff() {
        return """
                --- a/src/main/java/com/acme/OrderService.java
                +++ b/src/main/java/com/acme/OrderService.java
                @@ -2,3 +2,3 @@
                     String label(String name) {
                -        return name.toUpperCase();
                +        return name == null ? "" : name.toUpperCase();
                     }
                """;
    }
}
