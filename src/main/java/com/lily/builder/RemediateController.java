package com.lily.builder;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * diff 초안. 기능이 꺼져 있으면 본문을 모델에 넘기지 않는다.
 */
@RestController
public class RemediateController {

    private final ObjectProvider<RemediateDraftService> drafts;

    public RemediateController(ObjectProvider<RemediateDraftService> drafts) {
        this.drafts = drafts;
    }

    @PostMapping(value = "/api/remediate/drafts", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> draft(@RequestBody(required = false) RemediateDraftService.DraftRequest request) {
        return response(drafts.getIfAvailable(), request);
    }

    static Map<String, Object> response(RemediateDraftService service, RemediateDraftService.DraftRequest request) {
        if (service == null) {
            return Map.of("status", "off", "reason", "lily.remediate.enabled 가 꺼져 있다");
        }
        if (request == null) {
            return Map.of("status", "rejected", "reason", "요청이 비어 있다");
        }
        return service.draft(request);
    }
}
