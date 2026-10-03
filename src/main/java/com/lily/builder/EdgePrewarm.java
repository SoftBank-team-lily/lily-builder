package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 온프레미스 배포가 끝난 앱의 주요 페이지를 공개 주소로 한 번씩 연다 (쿠키 없이).
 * 요청이 엣지 Worker 를 지나며 그 데이터센터에 읽기 사본이 생기므로, 방문자가 아직 열지 않은 페이지도 PC 장애 때 사본으로 나간다.
 *
 * <pre>
 * 고르는 주소: 첫 화면 / → 그 HTML 의 같은 호스트 href·src → 스크립트의 fetch GET 경로 → /sitemap.xml 의 loc
 *            → 연 HTML·JS 파일의 fetch GET 경로. 첫 화면을 포함해 최대 {@link #LIMIT} 개
 * 사본 저장 여부는 Worker 가 정한다 (공개 응답만). 여기서는 열기만 한다
 * </pre>
 *
 * 사본은 이 요청이 들어간 데이터센터에만 생긴다 (builder 가 있는 서울 리전 → 한국 방문자 대부분이 들어가는 곳).
 */
@Component
public class EdgePrewarm {

    private static final Logger log = LoggerFactory.getLogger(EdgePrewarm.class);
    /** 첫 화면을 포함해 여는 페이지 수 */
    static final int LIMIT = 50;
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:href|src)\\s*=\\s*[\"']([^\"'#\\s]+)");
    private static final Pattern LOC = Pattern.compile("(?is)<loc>\\s*([^<\\s]+)\\s*</loc>");
    /** fetch("경로") 또는 fetch("경로", { 옵션 }). 경로에 $ 가 있으면(템플릿) 맞지 않는다 */
    private static final Pattern FETCH = Pattern.compile(
            "\\bfetch\\(\\s*([\"'`])([^\"'`$\\s]+)\\1\\s*(\\)|,\\s*\\{([^}]*)\\})");
    private static final Pattern NOT_GET = Pattern.compile("(?i)\\bmethod\\s*:\\s*[\"'`](?!GET[\"'`])");
    private static final Pattern AXIOS_GET = Pattern.compile("\\baxios\\.get\\(\\s*([\"'`])([^\"'`$\\s]+)\\1");

    /** GET 한 번. 닿지 못하면 RuntimeException */
    interface Http {
        Page get(URI uri);
    }

    /** body 는 HTML·XML·JS 일 때만 채운다 */
    record Page(int status, String contentType, String body) {
    }

    private final Http http;
    /** 라우트를 막 만든 경우 Cloudflare 에 반영될 때까지 기다리는 시간 */
    private final Duration delay;

    @Autowired
    public EdgePrewarm() {
        this(new HttpGet(), Duration.ofSeconds(5));
    }

    EdgePrewarm(Http http, Duration delay) {
        this.http = http;
        this.delay = delay;
    }

    /**
     * @param base 공개 주소 https://{app}.{zone}/
     * @return 화면 로그에 남길 한 줄
     */
    public String warm(String base) {
        URI root = URI.create(base);
        pause();
        Page first;
        try {
            first = http.get(root);
        } catch (RuntimeException e) {
            return "prewarm: skipped (" + base + " " + e.getMessage() + ")";
        }
        Set<URI> targets = new LinkedHashSet<>();
        targets.add(root);
        collect(root, first.body(), LINK, targets);
        collectApis(root, first.body(), targets);
        try {
            Page sitemap = http.get(root.resolve("/sitemap.xml"));
            if (sitemap.status() == 200) {
                collect(root, sitemap.body(), LOC, targets);
            }
        } catch (RuntimeException e) {
            log.debug("prewarm sitemap failed: {} {}", base, e.getMessage());
        }

        // 연 HTML·JS 에서 찾은 API 경로를 뒤에 붙여 가며 연다 (첫 화면을 포함해 LIMIT 개까지)
        List<URI> pages = new ArrayList<>(targets);
        int ok = first.status() == 200 ? 1 : 0;
        int opened = 1;
        for (int i = 1; i < pages.size() && opened < LIMIT; i++, opened++) {
            URI page = pages.get(i);
            try {
                Page result = http.get(page);
                if (result.status() == 200) {
                    ok++;
                    int before = targets.size();
                    collectApis(root, result.body(), targets);
                    pages.addAll(new ArrayList<>(targets).subList(before, targets.size()));
                }
            } catch (RuntimeException e) {
                log.debug("prewarm page failed: {} {}", page, e.getMessage());
            }
        }
        return "prewarm: " + ok + "/" + opened + " pages 200 from " + base;
    }

    /**
     * 스크립트(HTML 안 또는 JS 파일)가 GET 으로 부르는 같은 호스트 경로. SPA 는 목록을 HTML 이 아니라 API 로 불러온다.
     * fetch("…") 중 method 가 GET 이 아닌 호출, ${…} 가 든 템플릿 경로는 뺀다. axios.get("…") 도 본다
     */
    private static void collectApis(URI root, String text, Set<URI> targets) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder gets = new StringBuilder();
        Matcher fetch = FETCH.matcher(text);
        while (fetch.find()) {
            String options = fetch.group(4);
            if (options == null || !NOT_GET.matcher(options).find()) {
                gets.append("href=\"").append(fetch.group(2)).append("\"\n");
            }
        }
        Matcher axios = AXIOS_GET.matcher(text);
        while (axios.find()) {
            gets.append("href=\"").append(axios.group(2)).append("\"\n");
        }
        collect(root, gets.toString(), LINK, targets);
    }

    /** 같은 호스트의 http(s) 주소만 모은다. Worker 내부 경로와 해석하지 못하는 링크는 뺀다 */
    private static void collect(URI root, String text, Pattern pattern, Set<URI> targets) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String link = matcher.group(1).replace("&amp;", "&");
            URI uri;
            try {
                uri = root.resolve(link);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("https") || scheme.equals("http"))
                    || !root.getHost().equalsIgnoreCase(uri.getHost())
                    || uri.getPath() == null || uri.getPath().startsWith("/__lily_edge/")) {
                continue;
            }
            targets.add(uri);
        }
    }

    private void pause() {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 쿠키 없이, 리다이렉트를 따라가지 않고 본문을 끝까지 읽는다 (Worker 가 사본을 다 받도록) */
    static final class HttpGet implements Http {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public Page get(URI uri) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "lily-prewarm/1")
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                String type = response.headers().firstValue("content-type").orElse("");
                boolean text = type.contains("html") || type.contains("xml") || type.contains("javascript");
                return new Page(response.statusCode(), type,
                        text ? new String(response.body(), java.nio.charset.StandardCharsets.UTF_8) : "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            } catch (Exception e) {
                throw new IllegalStateException(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
