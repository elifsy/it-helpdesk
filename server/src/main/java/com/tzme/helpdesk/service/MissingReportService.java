package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.domain.Asset;
import com.tzme.helpdesk.domain.AssetRequest;
import com.tzme.helpdesk.domain.Attachment;
import com.tzme.helpdesk.domain.MissingReport;
import com.tzme.helpdesk.domain.PendingReportCounts;
import com.tzme.helpdesk.domain.Ticket;
import com.tzme.helpdesk.dto.TicketCreateReq;
import com.tzme.helpdesk.mapper.AssetMapper;
import com.tzme.helpdesk.mapper.AssetRequestMapper;
import com.tzme.helpdesk.mapper.MissingReportMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.Roles;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MissingReportService {
    private final MissingReportMapper reports;
    private final UserMapper users;
    private final MissingReportGuard guard;
    private static final String REPAIR = "REPAIR";

    private final TicketService tickets;
    private final AssetMapper assets;
    private final AssetService assetService;
    private final AssetRequestService assetRequests;
    private final AssetRequestMapper requestMapper;
    private final AttachmentService attachments;
    private final AuditService audit;

    public record Registration(@NotNull @Positive Long requesterId,
                               @NotBlank @Size(max = 200) String title,
                               @NotBlank @Size(max = 500) String description,
                               @NotBlank @Pattern(regexp = "CHAT|PHONE") String channel,
                               @NotNull @PastOrPresent LocalDateTime completedAt,
                               @Pattern(regexp = "REPAIR|RECEIVE|BORROW|REPLACE|RETURN") String businessType,
                               @Size(max = 32) String assetNo, @Size(max = 32) String newAssetNo,
                               LocalDate expectReturn) { }
    public record AssetSupplement(@NotBlank @Size(max = 500) String reason) { }
    public record AssetOption(String no, String name, String model, String category, String status, String location) {
        static AssetOption of(Asset asset) {
            return asset == null ? null : new AssetOption(asset.getNo(), asset.getName(), asset.getModel(),
                    asset.getCategory(), asset.getStatus(), asset.getLocation());
        }
    }
    public record AssetOptions(List<AssetOption> primary, List<AssetOption> replacement) { }
    public record Cancellation(@NotBlank @Size(max = 500) String reason) { }
    public record Applicant(Long id, String name, String department, String employeeNo) { }
    public record Summary(long pending, int limit, boolean blocked, PendingReportCounts pendingByType) { }
    public record Detail(MissingReport report, List<Attachment> evidence, AssetOption asset, AssetOption replacement) { }

    public Summary summary() {
        PendingReportCounts counts = guard.pendingByType(CurrentUser.require().id());
        long pending = counts.total();
        return new Summary(pending, MissingReportGuard.LIMIT, pending > MissingReportGuard.LIMIT, counts);
    }

    public List<MissingReport> mine() {
        return reports.mine(CurrentUser.require().id());
    }

    public PageResult<MissingReport> list(int page) {
        requireStaff();
        int p = Math.max(1, page);
        return new PageResult<>(reports.list((p - 1L) * 20, 20), reports.total(), p, 20);
    }

    public List<Applicant> search(String keyword) {
        requireStaff();
        return users.searchActivePeople(keyword, false).stream()
                .map(user -> new Applicant(user.getId(), user.getName(), user.getDeptName(), user.getEmployeeNo())).toList();
    }

    public AssetOptions assetOptions(Long requesterId, String type) {
        requireStaff();
        if (requesterId == null || requesterId <= 0 || !AssetRequestService.supportsType(type)) {
            throw new BizException("请选择需求人和资产业务类型");
        }
        var rows = assets.list(null, null, null);
        var primary = rows.stream().filter(a -> assetService.stateActions(a, requesterId).contains(type))
                .map(AssetOption::of).toList();
        var replacement = "REPLACE".equals(type)
                ? rows.stream().filter(AssetRequestService::availableReplacement).map(AssetOption::of).toList()
                : List.<AssetOption>of();
        return new AssetOptions(primary, replacement);
    }

    public Detail detail(String no) {
        // 校验登记与佐证的访问权限后，再读取关联设备。
        MissingReport report = require(no);
        var evidence = attachments.list("MISSING_REPORT", no);
        return new Detail(report, evidence, findAsset(report.getAssetNo()), findAsset(report.getNewAssetNo()));
    }

    private AssetOption findAsset(String no) {
        return no == null ? null : AssetOption.of(assets.findByNo(no));
    }

    @Transactional
    public MissingReport register(Registration input, List<MultipartFile> evidence) {
        requireStaff();
        if (evidence == null || evidence.isEmpty()) throw new BizException("请添加佐证附件");
        var requester = guard.lockRequester(input.requesterId());
        if (!"ACTIVE".equals(requester.getStatus())) throw new BizException("请选择在职需求人");
        MissingReport report = new MissingReport();
        report.setNo(UUID.randomUUID().toString().replace("-", ""));
        report.setRequesterId(requester.getId());
        report.setRequesterName(requester.getName());
        report.setRecordedBy(CurrentUser.name());
        report.setTitle(input.title().strip());
        report.setDescription(input.description().strip());
        report.setChannel(input.channel());
        report.setCompletedAt(input.completedAt());
        report.setBusinessType(input.businessType() == null ? REPAIR : input.businessType());
        if (!REPAIR.equals(report.getBusinessType())) {
            populateAssetFields(report, input);
            assetRequests.validateSupplement(report, requester);
            if (reports.pendingForAssets(requester.getId(), report.getAssetNo(), report.getNewAssetNo()) > 0)
                throw new BizException(409, "该需求人的相关设备已有待补交申请");
        }
        reports.insert(report);
        for (MultipartFile file : evidence) attachments.saveReportEvidence(report.getNo(), file);
        audit.record("MISSING_REPORT_REGISTER", report.getNo(), "类型 " + report.getBusinessType() + "；需求人 ID " + requester.getId());
        return require(report.getNo());
    }

    private static void populateAssetFields(MissingReport report, Registration input) {
        if (!AssetRequestService.supportsType(report.getBusinessType())
                || input.assetNo() == null || input.assetNo().isBlank()) {
            throw new BizException("请选择资产业务类型和设备");
        }
        report.setAssetNo(input.assetNo().strip());
        if ("REPLACE".equals(report.getBusinessType())) {
            if (input.newAssetNo() == null || input.newAssetNo().isBlank()
                    || report.getAssetNo().equals(input.newAssetNo().strip())) {
                throw new BizException("更换必须指定另一台替换设备");
            }
            report.setNewAssetNo(input.newAssetNo().strip());
        }
        if ("BORROW".equals(report.getBusinessType())) {
            if (input.expectReturn() == null || input.expectReturn().isBefore(report.getCompletedAt().toLocalDate())) {
                throw new BizException("约定归还日期不能早于实际借用日期");
            }
            report.setExpectReturn(input.expectReturn());
        }
    }

    @Transactional
    public Ticket submit(String no, TicketCreateReq input) {
        var user = CurrentUser.require();
        guard.lockRequester(user.id());
        MissingReport report = lockForRequester(no, user.id());
        if (!REPAIR.equals(report.getBusinessType())) throw new BizException("请填写资产补交申请");
        if ("SUBMITTED".equals(report.getStatus())) return tickets.detail(report.getTicketNo()).getTicket();
        requirePending(report);
        Ticket ticket = tickets.archiveSupplement(input, report);
        reports.submit(no, ticket.getNo());
        audit.record("MISSING_REPORT_SUBMIT", no, "工单 " + ticket.getNo());
        return ticket;
    }

    @Transactional
    public AssetRequest submitAsset(String no, AssetSupplement input) {
        var requester = guard.lockRequester(CurrentUser.require().id());
        MissingReport report = lockForRequester(no, requester.getId());
        if (!AssetRequestService.supportsType(report.getBusinessType())) throw new BizException("请填写报修补报表单");
        if ("SUBMITTED".equals(report.getStatus())) return requestMapper.findByNo(report.getAssetRequestNo());
        requirePending(report);
        AssetRequest request = assetRequests.archiveSupplement(report, requester, input.reason().strip());
        reports.submitAsset(no, request.getNo());
        audit.record("MISSING_REPORT_SUBMIT", no, "资产申请 " + request.getNo());
        return request;
    }

    private MissingReport lockForRequester(String no, Long requesterId) {
        MissingReport report = reports.lock(no);
        if (report == null) throw new BizException(404, "补报登记不存在");
        if (!requesterId.equals(report.getRequesterId())) throw new BizException(403, "仅需求人本人可提交");
        return report;
    }

    @Transactional
    public void cancel(String no, Cancellation input) {
        requireStaff();
        guard.lockRequester(require(no).getRequesterId());
        requirePending(reports.lock(no));
        reports.cancel(no, input.reason().strip());
        audit.record("MISSING_REPORT_CANCEL", no, input.reason().strip());
    }

    private MissingReport require(String no) {
        MissingReport report = reports.find(no);
        if (report == null) throw new BizException(404, "补报登记不存在");
        return report;
    }

    private static void requirePending(MissingReport report) {
        if (!"PENDING".equals(report.getStatus())) throw new BizException(409, "该事项已补报或已撤销");
    }

    private static void requireStaff() {
        if (!Roles.isItOrAdmin(CurrentUser.require().role())) throw new BizException(403, "仅 IT 和管理员可以登记或撤销补报");
    }
}
