package com.lily.builder;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/builds")
public class BuildController {

    private final BuildService service;

    public BuildController(BuildService service) {
        this.service = service;
    }

    /** 빌드·배포는 몇 분 걸려서 바로 id 만 돌려준다. 진행 상황은 GET 으로 본다 */
    @PostMapping
    public ResponseEntity<Build> start(@Valid @RequestBody BuildRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Build> get(@PathVariable String id) {
        return service.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
