package com.lily.builder.cloud;

import com.lily.jev.CachedJev;
import com.lily.jev.HttpJev;
import com.lily.jev.Jev;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
class CloudConfiguration {
    /** 저장소 연관성과 배포 위치 선택이 함께 쓰는 Jev. 키가 없으면 묻지 않고 규칙만 쓴다. */
    @Bean("cloudJev")
    Jev cloudJev(CloudProperties props) {
        if (!(props.jevMinConfidence() > 0 && props.jevMinConfidence() <= 1))
            throw new IllegalStateException("lily.cloud.jev-min-confidence 는 0 초과 1 이하여야 한다");
        if (props.jevApiKey() == null || props.jevApiKey().isBlank()) return Jev.disabled();
        Jev http = new HttpJev(props.jevApiKey(), props.jevMinConfidence(), props.jevModel());
        return props.jevCacheSeconds() < 1 ? http : new CachedJev(http, Duration.ofSeconds(props.jevCacheSeconds()), 256);
    }
}
