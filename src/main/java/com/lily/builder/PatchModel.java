package com.lily.builder;

import java.util.Optional;

/** 가린 로그와 파일 내용으로 unified diff 한 편을 만든다. 비우면 고치지 않는다. */
public interface PatchModel {

    Optional<String> diff(String prompt);
}
