package com.lily.builder.cloud;

import com.lily.jev.HttpJev;
import com.lily.jev.Jev;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

@Service
public class CloudService {
    private final CloudCatalog catalog;
    private final CloudProperties props;
    private final CloudPolicy policy;
    private final Clock clock;
    @Autowired
    public CloudService(CloudCatalog catalog, CloudProperties props) {
        this(catalog, props, new CloudPolicy(props.jevApiKey() == null || props.jevApiKey().isBlank()
                ? Jev.disabled() : new HttpJev(props.jevApiKey(), .8)), Clock.systemUTC());
    }
    CloudService(CloudCatalog catalog, CloudProperties props, CloudPolicy policy, Clock clock) {
        this.catalog = catalog; this.props = props; this.policy = policy; this.clock = clock;
    }
    public CloudPolicy.Decision plan(CloudPolicy.Request request) {
        return plan(request, Map.of());
    }
    public CloudPolicy.Decision plan(CloudPolicy.Request request, Map<String,?> repository) {
        if (props.maxAgeSeconds() < 1 || props.maxAgeSeconds() > 3600)
            return CloudPolicy.held("invalid_freshness_config", List.of(), List.of(), clock.instant());
        return policy.decide(request, catalog.read(), props.regions(), clock.instant(), props.maxAgeSeconds(), repository);
    }
    /** JEV 호출 중 바뀐 가용성·관측 시각도 실행 직전에 다시 검사한다. 다른 provider로 자동 재선택하지 않는다. */
    public CloudPolicy.Decision recheck(CloudPolicy.Request request, CloudPolicy.Decision decision) {
        if (!decision.status().equals("selected")) return decision;
        var pinned = new CloudPolicy.Request(decision.provider(), request.priority(), request.profile(),
            request.maxMonthlyCostUsd(), request.maxP95Ms(), Set.of(decision.region()), request.capabilities(), request.context());
        var checked = new CloudPolicy(Jev.disabled()).decide(pinned, catalog.read(), props.regions(), clock.instant(), props.maxAgeSeconds());
        return checked.status().equals("selected")
            ? new CloudPolicy.Decision("selected", decision.provider(), decision.region(), decision.source(), decision.confidence(),
                decision.reason(), checked.selected(), checked.candidates(), checked.excluded(), clock.instant())
            : CloudPolicy.held("changed_before_dispatch", checked.candidates(), checked.excluded(), clock.instant());
    }
}
