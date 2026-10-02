package com.lily.builder;

import java.util.Optional;

/** 코드 결함으로 PR 을 낼지. 호출이 실패하거나 애매하면 빈 값이고, 그때는 PR 을 열지 않는다. */
public interface DefectGate {

    Optional<Boolean> codeDefect(String log);
}
