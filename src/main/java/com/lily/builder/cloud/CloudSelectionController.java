package com.lily.builder.cloud;

import com.lily.builder.BuildRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/cloud/selection")
public class CloudSelectionController {
    private final CloudSelection selection;
    public CloudSelectionController(CloudSelection selection) {this.selection=selection;}
    @PostMapping
    public ResponseEntity<?> choose(@Valid @RequestBody BuildRequest build) {
        if ("ONPREM_ONLY".equals(build.deploymentModeOrDefault())) return ResponseEntity.badRequest().body(Map.of("error","onprem_only_has_no_cloud"));
        try {return ResponseEntity.ok(selection.choose(build));}
        catch (CloudRepository.Unavailable e) {return ResponseEntity.unprocessableEntity().body(Map.of("error","repository_unavailable"));}
    }
}
