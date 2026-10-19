package com.projtimeline.project;

import com.projtimeline.common.ApiResponse;
import com.projtimeline.common.PageResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProjectController {
    private final ProjectService service;
    public ProjectController(ProjectService service) { this.service = service; }

    @GetMapping("/projects")
    ApiResponse<PageResponse<Map<String, Object>>> projects(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.projects(page, size)); }

    @PostMapping("/projects")
    ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) { return ApiResponse.ok(service.createProject(body)); }

    @PutMapping("/projects/{id}")
    ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateProject(id, body)); }

    @GetMapping("/projects/{id}/timeline")
    ApiResponse<Map<String, Object>> timeline(@PathVariable long id) { return ApiResponse.ok(service.timeline(id)); }

    @GetMapping("/projects/{id}/members")
    ApiResponse<List<Map<String, Object>>> members(@PathVariable long id) { return ApiResponse.ok(service.members(id)); }

    @GetMapping("/projects/{id}/users")
    ApiResponse<List<Map<String, Object>>> projectUsers(@PathVariable long id, @RequestParam(defaultValue="members") String scope) { return ApiResponse.ok(service.selectableUsers(id, scope)); }

    @PostMapping("/projects/{id}/members")
    ApiResponse<Map<String, Object>> addMember(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.addMember(id, body)); }

    @DeleteMapping("/projects/{id}/members/{userId}")
    ApiResponse<Void> removeMember(@PathVariable long id, @PathVariable long userId) { service.removeMember(id, userId); return ApiResponse.ok(null); }

    @PutMapping("/projects/{id}/pm")
    ApiResponse<Void> transferPm(@PathVariable long id, @RequestBody Map<String, Object> body) { service.transferPm(id, body); return ApiResponse.ok(null); }

    @GetMapping("/projects/{id}/modules")
    ApiResponse<List<Map<String, Object>>> modules(@PathVariable long id) { return ApiResponse.ok(service.modules(id)); }

    @PostMapping("/projects/{id}/modules")
    ApiResponse<Map<String, Object>> createModule(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.createModule(id, body)); }

    @PutMapping("/modules/{id}")
    ApiResponse<Map<String, Object>> updateModule(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateModule(id, body)); }

    @DeleteMapping("/modules/{id}")
    ApiResponse<Void> deleteModule(@PathVariable long id) { service.deleteModule(id); return ApiResponse.ok(null); }

    @PostMapping("/projects/{id}/stage-targets")
    ApiResponse<Map<String, Object>> createStageTarget(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.createStageTarget(id, body)); }

    @PutMapping("/stage-targets/{id}")
    ApiResponse<Map<String, Object>> updateStageTarget(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateStageTarget(id, body)); }

    @DeleteMapping("/stage-targets/{id}")
    ApiResponse<Void> deleteStageTarget(@PathVariable long id) { service.deleteStageTarget(id); return ApiResponse.ok(null); }

    @PostMapping("/projects/{id}/archive")
    ApiResponse<Void> archive(@PathVariable long id) { service.archive(id); return ApiResponse.ok(null); }

    @PostMapping("/projects/{id}/unarchive")
    ApiResponse<Void> unarchive(@PathVariable long id) { service.unarchive(id); return ApiResponse.ok(null); }
}
