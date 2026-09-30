package com.lily.builder;

import java.util.List;
import java.util.Optional;

/** 배포 이력 저장소. 운영은 DynamoDB ({@link DynamoBuildStore}) */
public interface BuildStore {

    /** 같은 id 면 덮어쓴다. 상태가 바뀔 때마다 부른다 */
    void save(Build build);

    Optional<Build> find(String id);

    /** 최신순 */
    List<Build> findAll();
}
