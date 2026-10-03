package com.lily.builder.cloud;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 같은 앱 이름으로 최근에 보낸 배포. 두 번 누르거나 호출자가 다시 보내도 worker 에 빌드가 두 번 생기지 않게 한다.
 * 이 프로세스 안에서만 기억한다. 재시작·여러 replica 사이의 중복은 frontend 의 배포 기록이 막는다.
 */
@Component
public class CloudDispatches {
    static final Duration WINDOW = Duration.ofMinutes(10);
    /** @param buildId worker 가 접수한 빌드. 보내는 중이거나 접수를 확인하지 못했으면 null */
    public record Dispatch(String provider, String buildId, Instant at) {}

    private final Map<String, Dispatch> recent = new HashMap<>();
    private final Clock clock;

    public CloudDispatches() { this(Clock.systemUTC()); }
    CloudDispatches(Clock clock) { this.clock = clock; }

    /** 최근 배포가 없으면 이 앱을 잡고 빈 값을 돌려준다. 있으면 그 배포를 돌려준다 */
    public synchronized Optional<Dispatch> claim(String appName, String provider) {
        Instant now = clock.instant();
        recent.values().removeIf(d -> !d.at().plus(WINDOW).isAfter(now));
        Dispatch current = recent.get(appName);
        if (current != null) return Optional.of(current);
        recent.put(appName, new Dispatch(provider, null, now));
        return Optional.empty();
    }

    public synchronized void started(String appName, String provider, String buildId) {
        recent.put(appName, new Dispatch(provider, buildId, clock.instant()));
    }

    /** worker 가 접수하지 않은 것이 확실할 때만 푼다. 접수 여부를 모르면 잡아 둔다 */
    public synchronized void release(String appName) {
        recent.remove(appName);
    }
}
