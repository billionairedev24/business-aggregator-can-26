package ca.northline.messaging.web;

import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.messaging.application.BrowseInbox.Message;
import ca.northline.messaging.application.ManageHelpCases;
import ca.northline.messaging.application.ManageHelpCases.CaseDetail;
import ca.northline.messaging.application.ManageHelpCases.CaseSummary;
import ca.northline.messaging.web.MessagingRequests.CaseReplyRequest;
import ca.northline.messaging.web.MessagingRequests.OpenCaseRequest;
import ca.northline.shared.ListResponse;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Help › My cases and Contact support: {@code GET/POST /help/cases}, {@code GET /help/cases/{id}},
 * {@code POST /help/cases/{id}/messages}. Every team member may open and follow cases (the design's case table lets
 * all roles create).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/help/cases")
@RequiredArgsConstructor
class HelpCaseController {

    private final ManageHelpCases cases;

    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<CaseSummary> list(@PathVariable String merchantId) {
        return new ListResponse<>(cases.cases(merchantId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(VIEW)
    CaseSummary open(
            @PathVariable String merchantId,
            @Valid @RequestBody OpenCaseRequest body,
            CurrentMember member,
            Locale locale) {
        return cases.open(new ManageHelpCases.Open(
                merchantId,
                member.userId(),
                member.role(),
                body.topic(),
                body.refType(),
                body.refId(),
                body.refLabel(),
                body.body(),
                body.files(),
                body.channel(),
                body.urgent(),
                locale));
    }

    @GetMapping("/{caseId}")
    @RequiresMerchant(VIEW)
    CaseDetail get(@PathVariable String merchantId, @PathVariable String caseId) {
        return cases.view(merchantId, caseId);
    }

    @PostMapping("/{caseId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresMerchant(VIEW)
    Message reply(
            @PathVariable String merchantId,
            @PathVariable String caseId,
            @Valid @RequestBody CaseReplyRequest body,
            CurrentMember member) {
        return cases.reply(new ManageHelpCases.Reply(merchantId, caseId, member.userId(), body.body(), body.files()));
    }
}
