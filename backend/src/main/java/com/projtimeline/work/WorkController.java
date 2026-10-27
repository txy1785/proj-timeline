package com.projtimeline.work;

import com.projtimeline.common.ApiResponse;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.RequestValues;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkController {
    private final WorkService service;
    public WorkController(WorkService service) { this.service = service; }

    @GetMapping("/nodes/{id}")
    ApiResponse<Map<String, Object>> node(@PathVariable long id) { return ApiResponse.ok(service.node(id)); }

    @PostMapping("/projects/{id}/nodes")
    ApiResponse<Map<String, Object>> createNode(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.createNode(id, body)); }

    @PostMapping("/projects/{id}/bugs")
    ApiResponse<Map<String, Object>> reportBug(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.reportBug(id, body)); }

    @GetMapping("/projects/{id}/bug-routing")
    ApiResponse<Map<String, Object>> bugRouting(@PathVariable long id, @RequestParam(required = false) Long relatedNodeId) {
        return ApiResponse.ok(service.bugRouting(id, relatedNodeId));
    }

    @PutMapping("/nodes/{id}")
    ApiResponse<Map<String, Object>> updateNode(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateNode(id, body)); }

    @PostMapping("/nodes/{id}/status")
    ApiResponse<Map<String, Object>> nodeStatus(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.changeNodeStatus(id, String.valueOf(body.get("status")))); }

    /**
     * 已废弃：工作项进度改由任务完成率派生。保留路由只为让旧客户端得到明确提示，不修改任何数据；
     * 用 @Hidden 排除在 OpenAPI 之外，接口清单里不再出现。
     */
    @Deprecated
    @Hidden
    @PutMapping("/nodes/{id}/progress")
    ApiResponse<Void> progress(@PathVariable long id) {
        throw BusinessException.conflict("工作项进度由任务完成率自动计算，不再支持手动设置");
    }

    @PostMapping("/nodes/{id}/block")
    ApiResponse<Map<String, Object>> block(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.block(id, RequestValues.text(body, "reason"))); }

    @PostMapping("/nodes/{id}/unblock")
    ApiResponse<Map<String, Object>> unblock(@PathVariable long id) { return ApiResponse.ok(service.unblock(id)); }

    @PutMapping("/nodes/{id}/overdue-reason")
    ApiResponse<Map<String, Object>> overdueReason(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.overdueReason(id, RequestValues.text(body, "reason"))); }

    @GetMapping("/nodes/{id}/logs")
    ApiResponse<List<Map<String, Object>>> logs(@PathVariable long id) { return ApiResponse.ok(service.logs(id)); }

    @GetMapping("/nodes/{id}/tasks")
    ApiResponse<List<Map<String, Object>>> tasks(@PathVariable long id) { return ApiResponse.ok(service.tasks(id)); }

    @PostMapping("/nodes/{id}/tasks")
    ApiResponse<Map<String, Object>> createTask(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.createTask(id, body)); }

    @GetMapping("/nodes/{id}/deliverables")
    ApiResponse<List<Map<String, Object>>> deliverables(@PathVariable long id) { return ApiResponse.ok(service.deliverables(id)); }

    @PostMapping("/nodes/{id}/deliverables")
    ApiResponse<Map<String, Object>> createDeliverable(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.createDeliverable(id, body)); }

    @PutMapping("/deliverables/{id}")
    ApiResponse<Map<String, Object>> updateDeliverable(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateDeliverable(id, body)); }

    @DeleteMapping("/deliverables/{id}")
    ApiResponse<Void> deleteDeliverable(@PathVariable long id) { service.deleteDeliverable(id); return ApiResponse.ok(null); }

    @PutMapping("/tasks/{id}")
    ApiResponse<Map<String, Object>> updateTask(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateTask(id, body)); }

    @DeleteMapping("/tasks/{id}")
    ApiResponse<Void> deleteTask(@PathVariable long id) { service.deleteTask(id); return ApiResponse.ok(null); }

    @PutMapping("/tasks/{id}/status")
    ApiResponse<Map<String, Object>> taskStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.taskStatus(id, String.valueOf(body.get("status")), RequestValues.text(body, "expectedStatus")));
    }

    @GetMapping("/tasks/{id}/notes")
    ApiResponse<List<Map<String, Object>>> taskNotes(@PathVariable long id) { return ApiResponse.ok(service.taskNotes(id)); }

    @PostMapping("/tasks/{id}/notes")
    ApiResponse<Map<String, Object>> addTaskNote(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.addTaskNote(id, body)); }

    @DeleteMapping("/task-notes/{id}")
    ApiResponse<Void> deleteTaskNote(@PathVariable long id) { service.deleteTaskNote(id); return ApiResponse.ok(null); }

    @GetMapping("/tasks/{id}/work-logs")
    ApiResponse<List<Map<String, Object>>> workLogs(@PathVariable long id) { return ApiResponse.ok(service.workLogs(id)); }

    @PostMapping("/tasks/{id}/work-logs")
    ApiResponse<Map<String, Object>> addWorkLog(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.addWorkLog(id, body)); }

    @PutMapping("/work-logs/{id}")
    ApiResponse<Map<String, Object>> updateWorkLog(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateWorkLog(id, body)); }

    @DeleteMapping("/work-logs/{id}")
    ApiResponse<Void> deleteWorkLog(@PathVariable long id) { service.deleteWorkLog(id); return ApiResponse.ok(null); }
}
