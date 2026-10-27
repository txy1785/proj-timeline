package com.projtimeline.workspace;

import com.projtimeline.common.ApiResponse;
import com.projtimeline.common.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) { this.service = service; }

    @GetMapping("/context")
    ApiResponse<Map<String, Object>> context() { return ApiResponse.ok(service.context()); }

    @GetMapping("/workspace/tasks")
    ApiResponse<WorkspaceTaskPage> tasks(@RequestParam(defaultValue = "OPEN") String scope,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Long projectId,
                                        @RequestParam(required = false) Long assigneeId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueWeek,
                                        @RequestParam(defaultValue="false") boolean legacyOnly) {
        return ApiResponse.ok(service.tasks(scope, status, projectId, assigneeId, q, page, size, dueWeek,legacyOnly));
    }

    @GetMapping("/workspace/deliveries")
    ApiResponse<WorkspaceDeliveryPage> deliveries(@RequestParam(defaultValue="OPEN") String scope,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long projectId,
            @RequestParam(required=false) Long ownerId,@RequestParam(required=false) String q,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dueWeek,
            @RequestParam(defaultValue="false") boolean overdue,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.deliveries(scope,status,projectId,ownerId,q,dueWeek,overdue,page,size));
    }

    @GetMapping("/workspace/bugs")
    ApiResponse<WorkspaceTaskPage> bugs(@RequestParam(defaultValue="OPEN") String scope,
            @RequestParam(required=false) String status,@RequestParam(required=false) Long projectId,
            @RequestParam(required=false) Long assigneeId,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="false") boolean overdue,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.bugs(scope,status,projectId,assigneeId,q,overdue,page,size));
    }

    @GetMapping("/workspace/management")
    ApiResponse<Map<String, Object>> management() { return ApiResponse.ok(service.management()); }

    @GetMapping("/workspace/work-items")
    ApiResponse<PageResponse<Map<String, Object>>> workItems(@RequestParam(required = false) Long ownerId,
                                                            @RequestParam(defaultValue = "ACTIVE") String scope,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.workItems(ownerId, scope, page, size));
    }
}
