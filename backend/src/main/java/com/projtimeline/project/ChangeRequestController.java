package com.projtimeline.project;

import com.projtimeline.common.ApiResponse;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.RequestValues;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChangeRequestController {
    private final ChangeRequestService service;
    public ChangeRequestController(ChangeRequestService service) { this.service = service; }

    @GetMapping("/projects/{id}/change-requests")
    ApiResponse<List<Map<String, Object>>> list(@PathVariable long id, @RequestParam(defaultValue = "ALL") String status) {
        return ApiResponse.ok(service.list(id, status));
    }

    @PostMapping("/modules/{id}/change-requests")
    ApiResponse<Map<String, Object>> create(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.create(id, body));
    }

    @PutMapping("/change-requests/{id}/decision")
    ApiResponse<Map<String, Object>> decide(@PathVariable long id, @RequestBody Map<String, Object> body) {
        // 只接受 JSON 布尔：以前的 Boolean.valueOf 会把 "1"/"yes" 静默当成 false，等于把"通过"变成"驳回"
        Object raw = body.get("approved");
        if (!(raw instanceof Boolean approved)) throw BusinessException.badRequest("approved 必须是 true 或 false");
        return ApiResponse.ok(service.decide(id, approved, RequestValues.text(body, "remark")));
    }

    @PostMapping("/change-requests/{id}/cancel")
    ApiResponse<Map<String, Object>> cancel(@PathVariable long id) { return ApiResponse.ok(service.cancel(id)); }
}
