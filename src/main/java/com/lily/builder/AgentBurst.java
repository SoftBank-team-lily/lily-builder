package com.lily.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 플랫폼 연결 에이전트의 버스팅 호출을 {@link BurstController} 대신 받는다. 버스팅 토큰은 사용자 PC 에 가지 않는다.
 * 경로는 /api/burst 와 같고, 이 에이전트로 배포한 앱({@link AgentDeployService#ownedBy})만 다룬다.
 *
 * <pre>
 * POST /api/burst/apps/{app}/standby   클라우드 대기 배포. 빌드 로그에 "standby: agent={key}" 를 남겨 소유를 잇는다
 * GET  /api/burst/builds/{id}          대기 배포 진행
 * GET  /api/burst/apps/{app}           레플리카·Ready 수
 * PUT  /api/burst/apps/{app}/replicas  {"replicas":0~5}
 * POST /api/burst/apps/{app}/database  {"engine","host","port","pgroll"?} → 같은 RDS DB 를 에이전트 터널 주소 기준 접속 정보로 (거점 전환 DB 이전). pgroll 이면 RDS 에 pgroll 도 켠다
 * </pre>
 */
@Component
public class AgentBurst {

    static final String STANDBY_MARK = "standby: agent=";
    private static final Pattern APP =
            Pattern.compile("/api/burst/apps/([a-z][a-z0-9-]{0,30})(/standby|/replicas|/database)?");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    private static final Pattern BUILD = Pattern.compile("/api/burst/builds/([A-Za-z0-9-]{1,40})");

    private final BuildService builds;
    private final CicdClient cicd;
    private final AgentDeployService deploys;
    private final Validator validator;
    private final ProvisionerClient provisioner;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AgentBurst(BuildService builds, CicdClient cicd, AgentDeployService deploys, Validator validator,
                      ProvisionerClient provisioner) {
        this.provisioner = provisioner;
        this.builds = builds;
        this.cicd = cicd;
        this.deploys = deploys;
        this.validator = validator;
    }

    /**
     * @return 응답 본문 ({@link BurstController} 와 같은 모양)
     * @throws IllegalArgumentException 허용하지 않는 호출
     */
    public JsonNode call(String key, String method, String path, JsonNode body) {
        Matcher app = APP.matcher(path == null ? "" : path);
        if (app.matches()) {
            String name = app.group(1);
            require(deploys.ownedBy(key, name), name + " 은 이 에이전트로 배포한 앱이 아니다");
            String action = app.group(2) == null ? "" : app.group(2);
            if (action.isEmpty() && "GET".equals(method)) {
                CicdClient.AppStatus status = cicd.status(name);
                require(status != null, name + " 의 클라우드 배포가 없다");
                return json.valueToTree(status);
            }
            if ("/replicas".equals(action) && "PUT".equals(method)) {
                int replicas = body == null ? -1 : body.path("replicas").asInt(-1);
                require(replicas >= 0 && replicas <= 5, "replicas 는 0~5");
                return json.valueToTree(cicd.scale(name, replicas));
            }
            if ("/database".equals(action) && "POST".equals(method)) {
                String engine = body == null ? "" : body.path("engine").asText("");
                String host = body == null ? "" : body.path("host").asText("");
                int port = body == null ? 0 : body.path("port").asInt(0);
                require(engine.equals("postgres") || engine.equals("mysql"), "engine 은 postgres 또는 mysql");
                require(HOST.matcher(host).matches(), "host 형식이 아니다");
                require(port >= 1 && port <= 65535, "port 는 1~65535");
                ProvisionerClient.Connection connection = provisioner.ensure(name, engine, host, port);
                if (body.path("pgroll").asBoolean(false)) {
                    // pgroll init 은 이벤트 트리거라 RDS 관리자 권한이 필요하다. 에이전트 대신 provisioner 로 켠다
                    require("postgres".equals(engine), "pgroll 은 postgres 만");
                    provisioner.enablePgroll(name);
                }
                return json.valueToTree(connection);
            }
            if ("/standby".equals(action) && "POST".equals(method)) {
                BuildRequest request = request(body);
                require(name.equals(request.appName()), "appName 이 경로와 다르다");
                return json.valueToTree(builds.start(BurstController.standbyOf(request), STANDBY_MARK + key));
            }
        }
        Matcher build = BUILD.matcher(path == null ? "" : path);
        if (build.matches() && "GET".equals(method)) {
            Build found = builds.get(build.group(1)).orElseThrow(() -> new IllegalArgumentException("빌드가 없다"));
            require(deploys.ownedBy(key, found.getAppName()), "이 에이전트의 빌드가 아니다");
            return json.valueToTree(found);
        }
        throw new IllegalArgumentException("허용하지 않는 버스팅 호출: " + method + " " + path);
    }

    private BuildRequest request(JsonNode body) {
        require(body != null && body.isObject(), "본문이 없다");
        BuildRequest request;
        try {
            request = json.treeToValue(body, BuildRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("대기 배포 요청을 읽지 못했다");
        }
        Set<ConstraintViolation<BuildRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            ConstraintViolation<BuildRequest> first = violations.iterator().next();
            throw new IllegalArgumentException(first.getPropertyPath() + " " + first.getMessage());
        }
        return request;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
