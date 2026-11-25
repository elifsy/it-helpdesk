package com.tzme.helpdesk.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tzme.helpdesk.common.*;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.*;
import com.tzme.helpdesk.dto.*;
import com.tzme.helpdesk.job.ScheduledJobs;
import com.tzme.helpdesk.job.BorrowDueJob;
import com.tzme.helpdesk.mapper.*;
import com.tzme.helpdesk.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.ZonedDateTime;
import java.util.*;

@RestController
@RequestMapping("/admin/manage")
@RequiredArgsConstructor
public class AdminManagementController {
    private final AdminManagementService service;
    private final RuntimeSettingsService settings;
    private final DeptMapper depts;
    private final ManagementMapper management;
    private final AttachmentMapper attachments;
    private final HelpdeskProperties props;
    private final RolePermissionService rolePermissions;
    @GetMapping("/dictionary")
    @com.tzme.helpdesk.security.RequiresPermission("dict.manage")
    public ApiResponse<Map<String,Object>> dictionary() { var current=settings.get(); return ApiResponse.ok(Map.of("revision",current.getRevision(),"categories",current.getCategories(),"categoriesByType",current.getCategoriesByType())); }
    @PutMapping("/categories")
    @com.tzme.helpdesk.security.RequiresPermission("dict.manage")
    public ApiResponse<Map<String,Object>> categories(@Valid @RequestBody CategoriesReq req) { return ApiResponse.ok(settings.saveCategories(req)); }
    @GetMapping("/users") @com.tzme.helpdesk.security.RequiresPermission({"user.manage"})
    public ApiResponse<PageResult<User>> users(@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(service.users(keyword,status,page,size));
    }
    @PutMapping("/users/{id}") @com.tzme.helpdesk.security.RequiresPermission({"user.manage"})
    public ApiResponse<User> user(@PathVariable Long id,@Valid @RequestBody UserAdminReq req) { return ApiResponse.ok(service.updateUser(id,req)); }
    @GetMapping("/departments") @com.tzme.helpdesk.security.RequiresPermission({"dept.manage","user.manage"})
    public ApiResponse<List<Dept>> departments() { return ApiResponse.ok(depts.listAll()); }
    @PostMapping("/departments") @com.tzme.helpdesk.security.RequiresPermission({"dept.manage"})
    public ApiResponse<Dept> createDept(@Valid @RequestBody DeptAdminReq req) { return ApiResponse.ok(service.saveDept(null,req)); }
    @PutMapping("/departments/{id}") @com.tzme.helpdesk.security.RequiresPermission({"dept.manage"})
    public ApiResponse<Dept> updateDept(@PathVariable Long id,@Valid @RequestBody DeptAdminReq req) { return ApiResponse.ok(service.saveDept(id,req)); }
    @DeleteMapping("/departments/{id}") @com.tzme.helpdesk.security.RequiresPermission({"dept.manage"})
    public ApiResponse<Void> deleteDept(@PathVariable Long id) { service.deleteDept(id); return ApiResponse.ok(); }
    @GetMapping("/settings") @com.tzme.helpdesk.security.RequiresPermission({"settings.manage"})
    public ApiResponse<ObjectNode> settings() { return ApiResponse.ok(settings.forClient(settings.get())); }
    @PutMapping("/settings") @com.tzme.helpdesk.security.RequiresPermission({"settings.manage"})
    public ApiResponse<ObjectNode> saveSettings(@Valid @RequestBody RuntimeSettings req) { return ApiResponse.ok(settings.forClient(settings.save(req))); }
    @PostMapping("/solution-codes") @com.tzme.helpdesk.security.RequiresPermission({"dict.manage"})
    public ApiResponse<SolutionCode> createCode(@Valid @RequestBody SolutionAdminReq req) { return ApiResponse.ok(service.saveSolution(null,req)); }
    @PutMapping("/solution-codes/{id}") @com.tzme.helpdesk.security.RequiresPermission({"dict.manage"})
    public ApiResponse<SolutionCode> updateCode(@PathVariable Long id,@Valid @RequestBody SolutionAdminReq req) { return ApiResponse.ok(service.saveSolution(id,req)); }
    @DeleteMapping("/solution-codes/{id}") @com.tzme.helpdesk.security.RequiresPermission({"dict.manage"})
    public ApiResponse<Void> deleteCode(@PathVariable Long id) { service.deleteSolution(id); return ApiResponse.ok(); }
    @GetMapping("/audit") @com.tzme.helpdesk.security.RequiresPermission({"audit.view"})
    public ApiResponse<PageResult<Map<String,Object>>> audit(@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        int p=Math.max(1,page),s=Math.max(1,Math.min(100,size));
        return ApiResponse.ok(new PageResult<>(management.audits(keyword,(p-1)*s,s),management.auditCount(keyword),p,s));
    }
    @GetMapping("/storage") @com.tzme.helpdesk.security.RequiresPermission({"settings.manage"})
    public ApiResponse<Map<String,Object>> storage() {
        Map<String,Object> result=new LinkedHashMap<>(attachments.storageSummary());
        result.put("type","本地磁盘"); result.put("directory",java.nio.file.Path.of(props.getUpload().getDir()).toAbsolutePath().normalize().toString());
        return ApiResponse.ok(result);
    }
    @GetMapping("/jobs")
    public ApiResponse<JobStatus> jobs() { return ApiResponse.ok(jobStatus(settings.jobs())); }
    @PutMapping("/jobs")
    public ApiResponse<JobStatus> saveJobs(@Valid @RequestBody JobSettings req) { return ApiResponse.ok(jobStatus(settings.saveJobs(req))); }

    public record JobStatus(JobSettings settings, List<Map<String, Object>> runs, boolean dingTalkEnabled,
                            String timezone, ZonedDateTime slaNextRun, ZonedDateTime contactNextRun, ZonedDateTime borrowDueNextRun) { }

    private JobStatus jobStatus(JobSettings value) {
        boolean enabled = props.getDingtalk().isEnabled();
        ZonedDateTime now = ZonedDateTime.now(ScheduledJobs.ZONE);
        return new JobStatus(value, management.jobs(), enabled, ScheduledJobs.ZONE.getId(),
                ScheduledJobs.nextExecution(value.slaScanEnabled(), value.slaCron(), now),
                ScheduledJobs.nextExecution(enabled && value.contactSyncEnabled(), value.contactCron(), now),
                ScheduledJobs.nextExecution(true, BorrowDueJob.CRON, now));
    }
    @GetMapping("/permissions") @com.tzme.helpdesk.security.RequiresPermission({"role.manage"})
    public ApiResponse<Map<String,Object>> permissions() { return ApiResponse.ok(rolePermissions.describe()); }
    @PutMapping("/permissions") @com.tzme.helpdesk.security.RequiresPermission({"role.manage"})
    public ApiResponse<Map<String,Object>> savePermissions(@Valid @RequestBody RolePermissionsReq req) { return ApiResponse.ok(rolePermissions.save(req)); }
}
