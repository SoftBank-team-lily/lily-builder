package com.lily.builder.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

/** 운영 수집기가 제공한 같은 부하 프로필의 가격·관측 스냅샷. URL은 서버 설정에서만 읽는다. */
@Component
public class CloudCatalog {
    private final CloudProperties props;
    private final ObjectMapper json;
    private final RestClient http;
    public CloudCatalog(CloudProperties props, ObjectMapper json) {
        this.props = props; this.json = json;
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        http = RestClient.builder().requestFactory(factory).build();
    }
    record Snapshot(List<CloudPolicy.Candidate> candidates) {}
    /**
     * 빈 목록은 수집기가 정상 응답했지만 후보가 없다는 뜻이다.
     * 수집기를 설정하지 않았거나 읽지 못하면 {@link Unavailable} 로 알려 "후보 없음"과 구분한다.
     */
    public List<CloudPolicy.Candidate> read() {
        if (!CloudProperties.validUrl(props.catalogUrl())) throw new Unavailable("catalog_unconfigured");
        try {
            var request = http.get().uri(props.catalogUrl());
            if (props.catalogToken() != null && !props.catalogToken().isBlank()) request.header("Authorization", "Bearer " + props.catalogToken());
            return request.exchange((req, response) -> {
                if (response.getStatusCode().value() != 200) throw new Unavailable("catalog_unavailable");
                byte[] bytes = response.getBody().readNBytes(131073);
                if (bytes.length > 131072) throw new Unavailable("catalog_unavailable");
                Snapshot snapshot = json.readValue(bytes, Snapshot.class);
                if (snapshot.candidates() == null || snapshot.candidates().size() > 100) throw new Unavailable("catalog_unavailable");
                return snapshot.candidates();
            });
        } catch (Unavailable e) { throw e; }
        // 응답 본문·토큰이 담길 수 있는 원래 오류는 돌려주지 않는다
        catch (RuntimeException e) { throw new Unavailable("catalog_unavailable"); }
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String code) { super(code); }
    }
}
