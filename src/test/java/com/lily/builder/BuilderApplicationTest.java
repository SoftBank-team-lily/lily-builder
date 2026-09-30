package com.lily.builder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 빈 연결이 깨지지 않았는지 (기동만 확인한다. 클러스터에는 붙지 않는다) */
@SpringBootTest(properties = "lily.builder.registry=reg:5000")
class BuilderApplicationTest {

    @Autowired
    BuildController controller;

    @Test
    void 기동() {
        assertThat(controller).isNotNull();
    }
}
