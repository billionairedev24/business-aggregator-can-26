package ca.northline.payments.web;

import static ca.northline.shared.security.MerchantPermission.MANAGE;
import static ca.northline.shared.security.MerchantPermission.OPERATE;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.payments.application.RespondToCases;
import ca.northline.payments.domain.CaseMessages;
import ca.northline.payments.domain.Evidence;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refunds &amp; disputes. Read: every team member. Draft response + evidence: owner, technician, cook (they did the job).
 * Offers, refunds and contesting: owner only; the money-moving ones take {@code Idempotency-Key}.
 *
 * <pre>
 * GET  …/refunds                               headline counts, open cases, history
 * POST …/refunds/{id}/accept                   Idempotency-Key → approved, queued
 * POST …/refunds/{id}/contest   {reason}       → agent
 * PUT  …/disputes/{id}/response {response}     draft
 * POST …/disputes/{id}/evidence                raw file body, Content-Type + X-File-Name (URL-encoded)
 * GET  …/disputes/{id}/evidence/{evidenceId}   the file
 * POST …/disputes/{id}/goodwill-offer {amountCents}   Idempotency-Key
 * POST …/disputes/{id}/full-refund             Idempotency-Key
 * POST …/disputes/{id}/contest                 → agent (needs the response)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}")
@RequiredArgsConstructor
class RefundCaseController {

    private final RespondToCases cases;
    private final PaymentsIdempotency idempotency;
    private final PaymentsWebMapper mapper;

    @GetMapping("/refunds")
    @RequiresMerchant(VIEW)
    CaseResponses.Overview overview(@PathVariable String merchantId) {
        return mapper.toResponse(cases.overview(merchantId));
    }

    @PostMapping("/refunds/{refundId}/accept")
    @RequiresMerchant(MANAGE)
    ResponseEntity<String> accept(
            @PathVariable String merchantId,
            @PathVariable String refundId,
            @RequestHeader(name = PaymentsIdempotency.HEADER, required = false) @Nullable String key,
            CurrentMember member) {
        return idempotency.run(
                PayoutController.scope(member, "refund-accept"),
                key,
                refundId,
                HttpStatus.OK,
                () -> mapper.toDetail(cases.acceptRefund(merchantId, refundId)));
    }

    @PostMapping("/refunds/{refundId}/contest")
    @RequiresMerchant(MANAGE)
    CaseResponses.CaseDetail contestRefund(
            @PathVariable String merchantId,
            @PathVariable String refundId,
            @Valid @RequestBody CaseRequests.Contest body) {
        return mapper.toDetail(cases.contestRefund(merchantId, refundId, Objects.requireNonNull(body.reason())));
    }

    @PutMapping("/disputes/{disputeId}/response")
    @RequiresMerchant(OPERATE)
    CaseResponses.CaseDetail response(
            @PathVariable String merchantId,
            @PathVariable String disputeId,
            @Valid @RequestBody CaseRequests.Response body) {
        return mapper.toDetail(
                cases.saveResponse(merchantId, disputeId, Objects.requireNonNullElse(body.response(), "")));
    }

    @PostMapping(path = "/disputes/{disputeId}/evidence", consumes = MediaType.ALL_VALUE)
    @RequiresMerchant(OPERATE)
    ResponseEntity<CaseResponses.CaseDetail> evidence(
            @PathVariable String merchantId,
            @PathVariable String disputeId,
            @RequestHeader(name = "X-File-Name", required = false) @Nullable String fileName,
            HttpServletRequest request) {
        byte[] bytes;
        try (var in = request.getInputStream()) {
            bytes = in.readNBytes((int) Evidence.MAX_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > Evidence.MAX_BYTES) {
            throw RuleViolation.of("file", "size", CaseMessages.EVIDENCE_SIZE);
        }
        var name = fileName == null || fileName.isBlank()
                ? "evidence"
                : URLDecoder.decode(fileName, StandardCharsets.UTF_8).strip();
        var contentType = Objects.requireNonNullElse(request.getContentType(), "application/octet-stream")
                .split(";")[0]
                .strip();
        var dispute = cases.addEvidence(merchantId, disputeId, new RespondToCases.Upload(name, contentType, bytes));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDetail(dispute));
    }

    @GetMapping("/disputes/{disputeId}/evidence/{evidenceId}")
    @RequiresMerchant(VIEW)
    ResponseEntity<byte[]> evidenceFile(
            @PathVariable String merchantId, @PathVariable String disputeId, @PathVariable String evidenceId) {
        var file = cases.evidenceFile(merchantId, disputeId, evidenceId)
                .orElseThrow(() -> new NotFound("evidence", evidenceId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(evidenceId).build().toString())
                .body(file.bytes());
    }

    @PostMapping("/disputes/{disputeId}/goodwill-offer")
    @RequiresMerchant(MANAGE)
    ResponseEntity<String> goodwill(
            @PathVariable String merchantId,
            @PathVariable String disputeId,
            @Valid @RequestBody CaseRequests.GoodwillOffer body,
            @RequestHeader(name = PaymentsIdempotency.HEADER, required = false) @Nullable String key,
            CurrentMember member) {
        return idempotency.run(
                PayoutController.scope(member, "goodwill-" + disputeId),
                key,
                body,
                HttpStatus.OK,
                () -> mapper.toDetail(
                        cases.offerGoodwill(merchantId, disputeId, Objects.requireNonNull(body.amountCents()))));
    }

    @PostMapping("/disputes/{disputeId}/full-refund")
    @RequiresMerchant(MANAGE)
    ResponseEntity<String> fullRefund(
            @PathVariable String merchantId,
            @PathVariable String disputeId,
            @RequestHeader(name = PaymentsIdempotency.HEADER, required = false) @Nullable String key,
            CurrentMember member) {
        return idempotency.run(
                PayoutController.scope(member, "full-refund"),
                key,
                disputeId,
                HttpStatus.OK,
                () -> mapper.toDetail(cases.refundInFull(merchantId, disputeId, member.userId())));
    }

    @PostMapping("/disputes/{disputeId}/contest")
    @RequiresMerchant(MANAGE)
    CaseResponses.CaseDetail contest(@PathVariable String merchantId, @PathVariable String disputeId) {
        return mapper.toDetail(cases.contest(merchantId, disputeId));
    }
}
