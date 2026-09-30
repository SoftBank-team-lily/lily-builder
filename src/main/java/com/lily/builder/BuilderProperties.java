package com.lily.builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param namespace      Kaniko Job 을 띄울 namespace
 * @param registry       이미지를 올릴 레지스트리. 예: {@code 123.dkr.ecr.ap-northeast-2.amazonaws.com}
 * @param insecure       레지스트리가 http 면 true (로컬 테스트용 레지스트리)
 * @param cicdUrl        lily-cicd 주소. 빌드가 끝나면 여기에 배포를 요청한다
 * @param kanikoImage    Kaniko 실행 이미지
 * @param buildTimeoutSeconds 빌드 한 번에 기다리는 최대 시간
 * @param dynamodb       배포 이력 테이블
 */
@ConfigurationProperties("lily.builder")
public record BuilderProperties(
        @DefaultValue("lily-builds") String namespace,
        String registry,
        @DefaultValue("false") boolean insecure,
        String cicdUrl,
        @DefaultValue("gcr.io/kaniko-project/executor:v1.23.2") String kanikoImage,
        @DefaultValue("900") long buildTimeoutSeconds,
        @DefaultValue Dynamodb dynamodb) {

    /** ECR 이면 Kaniko 에 내장된 ecr-login 으로 인증한다 (실행 노드의 IAM 역할) */
    public boolean ecr() {
        return registry != null && registry.contains(".dkr.ecr.");
    }

    /**
     * endpoint 가 있으면 DynamoDB Local 로 붙는다 (로컬 개발).
     * create-table 은 로컬 전용. 운영 테이블은 lily-db-provisioner/infra 의 Terraform 이 만든다.
     */
    public record Dynamodb(
            @DefaultValue("lily-builds") String table,
            String endpoint,
            @DefaultValue("ap-northeast-2") String region,
            @DefaultValue("false") boolean createTable) {}
}
