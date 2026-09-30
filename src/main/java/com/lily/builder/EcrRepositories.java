package com.lily.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.RepositoryAlreadyExistsException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ECR 은 저장소가 없으면 push 를 거부한다 (NAME_UNKNOWN). 빌드 전에 appName 저장소를 만들어 둔다.
 * ECR 이 아닌 레지스트리(로컬 테스트용)면 아무것도 하지 않는다.
 * 필요한 IAM 권한: ecr:CreateRepository (lily-db-provisioner/infra 의 lily-builder 정책)
 */
@Component
public class EcrRepositories implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EcrRepositories.class);
    // 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com
    private static final Pattern ECR_HOST = Pattern.compile("^\\d+\\.dkr\\.ecr\\.([a-z0-9-]+)\\.amazonaws\\.com");

    private final BuilderProperties props;
    private volatile EcrClient ecr;

    public EcrRepositories(BuilderProperties props) {
        this.props = props;
    }

    /** @return 이번에 새로 만들었으면 true */
    public boolean ensure(String repository) {
        if (!props.ecr()) {
            return false;
        }
        try {
            client().createRepository(r -> r.repositoryName(repository)
                    .imageScanningConfiguration(c -> c.scanOnPush(true)));
            log.info("ecr repository created: {}", repository);
            return true;
        } catch (RepositoryAlreadyExistsException e) {
            return false;
        }
    }

    private EcrClient client() {
        if (ecr == null) {
            synchronized (this) {
                if (ecr == null) {
                    Matcher m = ECR_HOST.matcher(props.registry());
                    if (!m.find()) {
                        throw new IllegalStateException("cannot read region from ECR registry: " + props.registry());
                    }
                    ecr = EcrClient.builder().region(Region.of(m.group(1))).build();
                }
            }
        }
        return ecr;
    }

    @Override
    public void close() {
        if (ecr != null) {
            ecr.close();
        }
    }
}
