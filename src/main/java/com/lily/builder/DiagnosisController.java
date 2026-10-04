package com.lily.builder;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Map;

@RestController
public class DiagnosisController {
    private final DiagnosisService diagnoses;
    public DiagnosisController(DiagnosisService diagnoses) { this.diagnoses = diagnoses; }

    @PostMapping("/api/diagnoses")
    public ResponseEntity<?> analyze(@Valid @RequestBody Diagnosis.Request request) {
        var ids = request.evidence().stream().map(Diagnosis.Evidence::id).toList();
        if (new HashSet<>(ids).size() != ids.size()
                || new HashSet<>(request.missingSources()).size() != request.missingSources().size()) return invalid();
        return ResponseEntity.ok(diagnoses.analyze(request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "message", "진단 요청 형식을 확인해 주세요."));
    }
}
