package com.projtimeline.work;

import com.projtimeline.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class NodeNoteController {
    private final NodeNoteService service;
    public NodeNoteController(NodeNoteService service) { this.service = service; }

    @GetMapping("/nodes/{id}/notes")
    ApiResponse<List<Map<String, Object>>> list(@PathVariable long id) { return ApiResponse.ok(service.list(id)); }

    @PostMapping("/nodes/{id}/notes")
    ApiResponse<Map<String, Object>> add(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.add(id, body)); }

    @PutMapping("/node-notes/{id}")
    ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.update(id, body)); }

    @DeleteMapping("/node-notes/{id}")
    ApiResponse<Void> delete(@PathVariable long id) { service.delete(id); return ApiResponse.ok(null); }
}
