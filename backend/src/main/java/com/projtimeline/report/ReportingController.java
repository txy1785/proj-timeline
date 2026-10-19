package com.projtimeline.report;

import com.projtimeline.common.ApiResponse;
import com.projtimeline.common.BusinessException;
import com.projtimeline.common.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReportingController {
    private final ReportingService service;
    public ReportingController(ReportingService service) { this.service = service; }

    @GetMapping("/workload")
    ApiResponse<Map<String, Object>> workload(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate week) { return ApiResponse.ok(service.workload(week)); }

    @GetMapping("/notifications")
    ApiResponse<PageResponse<Map<String, Object>>> notifications(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.ok(service.notifications(page, size)); }

    @PutMapping("/notifications/{id}/read")
    ApiResponse<Void> read(@PathVariable long id) { service.readNotification(id); return ApiResponse.ok(null); }

    @PutMapping("/notifications/read-all")
    ApiResponse<Void> readAll() { service.readAllNotifications(); return ApiResponse.ok(null); }

    @GetMapping("/projects/{id}/reports")
    ApiResponse<Map<String, Object>> report(@PathVariable long id, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate week) { return ApiResponse.ok(service.report(id, week)); }

    @GetMapping("/projects/{id}/reports/history")
    ApiResponse<List<Map<String, Object>>> history(@PathVariable long id) { return ApiResponse.ok(service.reportHistory(id)); }

    @PostMapping("/projects/{id}/reports")
    ApiResponse<Map<String, Object>> createDraft(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object value = body.get("weekStart"); if (value == null) throw BusinessException.badRequest("weekStart 不能为空");
        try { return ApiResponse.ok(service.createDraft(id, LocalDate.parse(value.toString()))); }
        catch (java.time.format.DateTimeParseException ex) { throw BusinessException.badRequest("weekStart 必须是 YYYY-MM-DD"); }
    }

    @PutMapping("/reports/{id}/note")
    ApiResponse<Map<String, Object>> note(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.updateNote(id, body.get("note") == null ? null : body.get("note").toString())); }

    @PostMapping("/reports/{id}/publish")
    ApiResponse<Map<String, Object>> publish(@PathVariable long id) { return ApiResponse.ok(service.publish(id)); }
}
