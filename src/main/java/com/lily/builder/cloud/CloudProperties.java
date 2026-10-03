package com.lily.builder.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.util.*;

@ConfigurationProperties("lily.cloud")
public record CloudProperties(
        @DefaultValue("") String apiToken,
        @DefaultValue("") String catalogUrl,
        @DefaultValue("") String catalogToken,
        @DefaultValue("300") long maxAgeSeconds,
        @DefaultValue("") String jevApiKey,
        // 비우면 lily-jev 기본 모델(jev-latest). 판마다 확신도가 달라질 수 있어 고정할 수 있게 둔다
        @DefaultValue("") String jevModel,
        @DefaultValue("0.8") double jevMinConfidence,
        // 같은 상태의 답을 다시 쓰는 시간. 미리보기와 실제 배포가 같은 결론을 내게 한다. 0 이면 끈다
        @DefaultValue("900") long jevCacheSeconds,
        @DefaultValue Worker aws,
        @DefaultValue Worker gcp) {
    public record Worker(@DefaultValue("") String url, @DefaultValue("") String token, @DefaultValue("") String region) {}
    public Map<String,String> regions() {
        Map<String,String> result = new LinkedHashMap<>();
        if (usable(aws)) result.put("aws", aws.region());
        if (usable(gcp)) result.put("gcp", gcp.region());
        return Map.copyOf(result);
    }
    public Worker worker(String provider) { return switch (provider) { case "aws" -> aws; case "gcp" -> gcp; default -> null; }; }
    static boolean usable(Worker worker) {
        return worker != null && worker.region() != null && worker.region().matches("[a-z0-9-]{1,64}") && validUrl(worker.url());
    }
    static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException e) { return false; }
    }
}
