package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.config.HelpdeskProperties;
import com.tzme.helpdesk.domain.Attachment;
import com.tzme.helpdesk.mapper.AttachmentMapper;
import com.tzme.helpdesk.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 附件存储：本地磁盘 + UUID 文件名。
 * 目录结构 {upload.dir}/{bizType}/{bizNo}/{uuid}.{ext}，原始文件名只入库用于展示，
 * 不参与路径拼装，避免路径穿越。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final Set<String> BIZ_TYPES = Set.of("TICKET", "ASSET", "REQUEST");
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "m4a", "wav");

    private final AttachmentMapper mapper;
    private final HelpdeskProperties props;
    private final BusinessAccessService access;
    private final RuntimeSettingsService settings;

    @org.springframework.transaction.annotation.Transactional
    public Attachment save(String bizType, String bizNo, MultipartFile file) {
        com.tzme.helpdesk.security.Permissions.require("attachment.upload");
        LoginUser user = CurrentUser.require();
        if (bizType == null || !BIZ_TYPES.contains(bizType)) {
            throw new BizException("不支持的附件业务类型");
        }
        if (bizNo == null || bizNo.isBlank() || bizNo.contains("..") || bizNo.contains("/") || bizNo.contains("\\")) {
            throw new BizException("业务单号不合法");
        }
        access.check(bizType, bizNo, true);
        return store(bizType, bizNo, file, user);
    }

    Attachment saveReportEvidence(String no, MultipartFile file) {
        return store("MISSING_REPORT", no, file, CurrentUser.require());
    }

    private Attachment store(String bizType, String bizNo, MultipartFile file, LoginUser user) {
        if (mapper.countByBusiness(bizType, bizNo) >= settings.get().getMaxAttachments()) {
            throw new BizException("该业务的附件数量已达上限");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        if (file.getSize() > settings.get().getMaxUploadMb() * 1024L * 1024L) {
            throw new BizException("单个附件不能超过 " + settings.get().getMaxUploadMb() + "MB");
        }
        String original = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        String ext = extOf(original);
        boolean evidence = "MISSING_REPORT".equals(bizType);
        if (!ALLOWED_EXT.contains(ext) && !(evidence && AUDIO_EXT.contains(ext))) {
            throw new BizException(evidence ? "证据须为图片、PDF、MP3、M4A 或 WAV" : "只支持 jpg / png / gif / webp / pdf");
        }

        String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = root().resolve(bizType).resolve(bizNo);
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(stored).toFile());
        } catch (IOException e) {
            log.error("save attachment failed", e);
            throw new BizException(500, "附件保存失败，请重试");
        }

        Attachment a = new Attachment();
        a.setBizType(bizType);
        a.setBizNo(bizNo);
        a.setFileName(original.length() > 200 ? original.substring(original.length() - 200) : original);
        a.setStoredName(stored);
        a.setContentType(contentTypeOf(stored));
        a.setFileSize(file.getSize());
        a.setUploader(user.name());
        try {
            mapper.insert(a);
        } catch (RuntimeException e) {
            try { Files.deleteIfExists(dir.resolve(stored)); } catch (IOException ignored) { }
            throw e;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            try { Files.deleteIfExists(dir.resolve(stored)); } catch (IOException ignored) { }
                        }
                    }
                });
        }
        return a;
    }

    public List<Attachment> list(String bizType, String bizNo) {
        access.check(bizType, bizNo, false);
        if (bizType == null || bizNo == null) {
            throw new BizException("缺少 bizType / bizNo");
        }
        return mapper.list(bizType, bizNo);
    }

    public Attachment require(Long id) {
        Attachment a = mapper.findById(id);
        if (a == null) {
            throw new BizException(404, "附件不存在");
        }
        return a;
    }

    /** raw 访问用：按 UUID 文件名定位，不暴露自增 id */
    public Resource loadRaw(String storedName) {
        if (storedName == null || storedName.contains("..") || storedName.contains("/") || storedName.contains("\\")) {
            throw new BizException(400, "文件名不合法");
        }
        Attachment a = mapper.findByStored(storedName);
        if (a == null) {
            throw new BizException(404, "附件不存在");
        }
        access.check(a.getBizType(), a.getBizNo(), false);
        Path p = root().resolve(a.getBizType()).resolve(a.getBizNo()).resolve(a.getStoredName());
        if (!Files.exists(p)) {
            throw new BizException(404, "附件文件已丢失");
        }
        return new FileSystemResource(p);
    }

    public String contentTypeOf(String storedName) {
        String ext = extOf(storedName);
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            default -> "image/jpeg";
        };
    }

    @org.springframework.transaction.annotation.Transactional
    public void delete(Long id) {
        com.tzme.helpdesk.security.Permissions.require("attachment.delete");
        LoginUser user = CurrentUser.require();
        Attachment a = require(id);
        access.check(a.getBizType(), a.getBizNo(), true);
        if (!a.getUploader().equals(user.name()) && !user.hasRole("ADMIN") && !user.hasPermission("ticket.handle") && !user.hasPermission("request.approve")) {
            throw new BizException(403, "只能删除自己上传的附件");
        }
        mapper.delete(id);
        try {
            Files.deleteIfExists(root().resolve(a.getBizType()).resolve(a.getBizNo()).resolve(a.getStoredName()));
        } catch (IOException e) {
            log.warn("delete attachment file failed: {}", e.getMessage());
        }
    }

    private Path root() {
        return Paths.get(props.getUpload().getDir()).toAbsolutePath().normalize();
    }

    private String extOf(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }
}
