package com.lily.builder;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 테스트용 저장소 */
class InMemoryBuildStore implements BuildStore {

    final Map<String, Build> builds = new ConcurrentHashMap<>();
    int saves;

    @Override
    public void save(Build build) {
        saves++;
        builds.put(build.getId(), build);
    }

    @Override
    public Optional<Build> find(String id) {
        return Optional.ofNullable(builds.get(id));
    }

    @Override
    public List<Build> findAll() {
        return builds.values().stream().sorted(Comparator.comparing(Build::getCreatedAt).reversed()).toList();
    }
}
