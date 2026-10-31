package com.schemaguard.api.controllers;

import com.schemaguard.api.services.PlatformMetadataService;
import com.schemaguard.api.services.PlatformMetadataService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SchemaGuardApiController {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuardApiController.class);
    private final PlatformMetadataService metadataService;

    public SchemaGuardApiController(PlatformMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/projects")
    public List<ProjectRecord> listProjects() {
        return metadataService.listProjects();
    }

    // 1. POST /api/v1/projects
    @PostMapping("/projects")
    public ResponseEntity<ProjectRecord> createProject(@RequestBody CreateProjectRequest request) {
        log.info("REST POST /api/v1/projects [name={}]", request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(metadataService.createProject(request));
    }

    // 2. GET /api/v1/projects/{id}
    @GetMapping("/projects/{id}")
    public ResponseEntity<Map<String, Object>> getProject(@PathVariable("id") String id) {
        return metadataService.getProjectDetails(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 3. POST /api/v1/projects/{id}/analyses
    @PostMapping("/projects/{id}/analyses")
    public ResponseEntity<?> createAnalysis(
            @PathVariable("id") String projectId,
            @RequestBody TriggerAnalysisRequest request) {
        log.info("REST POST /api/v1/projects/{}/analyses", projectId);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(metadataService.runAnalysis(projectId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 4. GET /api/v1/analyses/{id}
    @GetMapping("/analyses/{id}")
    public ResponseEntity<Map<String, Object>> getAnalysis(@PathVariable("id") String runId) {
        return metadataService.getAnalysisStatus(runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 5. GET /api/v1/analyses/{id}/findings
    @GetMapping("/analyses/{id}/findings")
    public ResponseEntity<List<Map<String, Object>>> getAnalysisFindings(@PathVariable("id") String runId) {
        return ResponseEntity.ok(metadataService.getFindingsForRun(runId));
    }

    // 6. POST /api/v1/projects/{id}/contracts
    @PostMapping("/projects/{id}/contracts")
    public ResponseEntity<?> registerContract(
            @PathVariable("id") String projectId,
            @RequestBody CreateContractRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(metadataService.registerContract(projectId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 7. GET /api/v1/projects/{id}/policies
    @GetMapping("/projects/{id}/policies")
    public ResponseEntity<List<Map<String, Object>>> getPolicies(@PathVariable("id") String projectId) {
        return ResponseEntity.ok(metadataService.getPolicies(projectId));
    }

    // 8. POST /api/v1/projects/{id}/policies
    @PostMapping("/projects/{id}/policies")
    public ResponseEntity<?> createPolicy(
            @PathVariable("id") String projectId,
            @RequestBody CreatePolicyRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(metadataService.createPolicy(projectId, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // 9. GET /api/v1/projects/{id}/snapshots
    @GetMapping("/projects/{id}/snapshots")
    public ResponseEntity<List<Map<String, Object>>> getSnapshots(@PathVariable("id") String projectId) {
        return ResponseEntity.ok(metadataService.getSnapshots(projectId));
    }

    // 10. GET /api/v1/rules
    @GetMapping("/rules")
    public ResponseEntity<List<Map<String, String>>> listRules() {
        return ResponseEntity.ok(metadataService.listRuleCatalog());
    }
}