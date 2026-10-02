package com.lily.builder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Runtime diagnosis uses cited observations. Recommendations are review tasks, never commands. */
public final class Diagnosis {
    private Diagnosis() {}

    static final String NAME = "[a-z0-9]([-a-z0-9]*[a-z0-9])?";
    static final String SOURCE = "status|metrics|pods|logs|deployment";
    static final String SIGNAL = "low_traffic|high_error_rate|high_latency|pod_not_ready|oom|restarts|database_error|config_error|code_error|deployment|observation";


    public record Request(
            @NotBlank @Size(max = 63) @Pattern(regexp = NAME) String app,
            @NotBlank @Size(max = 63) @Pattern(regexp = NAME) String namespace,
            @NotNull Instant observedAt,
            @NotNull @Size(max = 24) List<@NotNull @Valid Evidence> evidence,
            @NotNull @Size(max = 5) List<@NotNull @Pattern(regexp = SOURCE) String> missingSources) {}

    public record Evidence(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String id,
            @NotNull @Pattern(regexp = SOURCE) String source,
            @NotNull @Pattern(regexp = SIGNAL) String signal,
            @NotBlank @Size(max = 2000) String summary) {}

    public record Recommendation(String action, String reason, List<String> evidenceIds) {}

    public record Response(String app, String namespace, Instant observedAt, Instant analyzedAt, String source,
                           String category, String summary, List<String> evidenceIds,
                           List<Recommendation> recommendations, List<String> limitations) {}
}
