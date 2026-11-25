package com.tzme.helpdesk.service;

import com.tzme.helpdesk.common.BizException;
import com.tzme.helpdesk.common.CurrentUser;
import com.tzme.helpdesk.common.PageResult;
import com.tzme.helpdesk.domain.Attachment;
import com.tzme.helpdesk.domain.MissingReport;
import com.tzme.helpdesk.domain.Ticket;
import com.tzme.helpdesk.dto.TicketCreateReq;
import com.tzme.helpdesk.mapper.MissingReportMapper;
import com.tzme.helpdesk.mapper.UserMapper;
import com.tzme.helpdesk.security.Roles;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MissingReportService {
    private final MissingReportMapper reports;
    private final UserMapper users;
    private final MissingReportGuard guard;
    private final TicketService tickets;
    private final AttachmentService attachments;
    private final AuditService audit;

    public record Registration(@NotNull @Positive Long requesterId,
                               @NotBlank @Size(max = 200) String title,
                               @NotBlank @Size(max = 500) String description,
                               @NotBlank @Pattern(regexp = "CHAT|PHONE") String channel,
                               @NotNull @PastOrPresent LocalDateTime completedAt) { }
    public record Cancellation(@NotBlank @Size(max = 500) String reason) { }
    public record Applicant(Long id, String name, String department, String employeeNo) { }
    public record Summary(long pending, int limit, boolean blocked) { }
    public record Detail(MissingReport report, List<Attachment> evidence) { }

    public Summary summary() {
        long pending = guard.pending(CurrentUser.require().id());
        return new Summary(pending, MissingReportGuard.LIMIT, pending > MissingReportGuard.LIMIT);
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

    public Detail detail(String no) {
        MissingReport report = require(no);
        return new Detail(report, attachments.list("MISSING_REPORT", no));
    }

    @Transactional
    public MissingReport register(Registration input, MultipartFile evidence) {
        requireStaff();
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
        reports.insert(report);
        attachments.saveReportEvidence(report.getNo(), evidence);
        audit.record("MISSING_REPORT_REGISTER", report.getNo(), "需求人 ID " + requester.getId());
        return require(report.getNo());
    }

    @Transactional
    public Ticket submit(String no, TicketCreateReq input) {
        var user = CurrentUser.require();
        guard.lockRequester(user.id());
        MissingReport report = reports.lock(no);
        if (report == null) throw new BizException(404, "补报登记不存在");
        if (!user.id().equals(report.getRequesterId())) throw new BizException(403, "仅需求人本人可补报");
        if ("SUBMITTED".equals(report.getStatus())) return tickets.detail(report.getTicketNo()).getTicket();
        requirePending(report);
        Ticket ticket = tickets.archiveSupplement(input, report);
        reports.submit(no, ticket.getNo());
        audit.record("MISSING_REPORT_SUBMIT", no, "工单 " + ticket.getNo());
        return ticket;
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
