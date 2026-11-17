package com.tzme.helpdesk.controller;

import com.tzme.helpdesk.common.ApiResponse;
import com.tzme.helpdesk.domain.Attachment;
import com.tzme.helpdesk.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @com.tzme.helpdesk.security.RequiresPermission({"attachment.upload"})
    public ApiResponse<Attachment> upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam("bizType") String bizType,
                                          @RequestParam("bizNo") String bizNo) {
        Attachment a = service.save(bizType, bizNo, file);
        return ApiResponse.ok(a);
    }

    @GetMapping
    public ApiResponse<List<Attachment>> list(@RequestParam String bizType, @RequestParam String bizNo) {
        return ApiResponse.ok(service.list(bizType, bizNo));
    }

    /** 图片/PDF 需登录且具备所属业务权限；前端认证请求后以 Blob URL 展示。 */
    @GetMapping("/raw/{storedName:.+}")
    public ResponseEntity<Resource> raw(@PathVariable String storedName) {
        Resource resource = service.loadRaw(storedName);
        MediaType type;
        try {
            type = MediaType.parseMediaType(service.contentTypeOf(storedName));
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore())
                .header("Content-Disposition", "inline")
                .body(resource);
    }

    @DeleteMapping("/{id}")
    @com.tzme.helpdesk.security.RequiresPermission({"attachment.delete"})
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
