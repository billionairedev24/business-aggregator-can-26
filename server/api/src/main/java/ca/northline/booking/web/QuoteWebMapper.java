package ca.northline.booking.web;

import ca.northline.booking.application.MediaCatalog.MediaInfo;
import ca.northline.booking.application.QuoteViews.QuoteRequestCard;
import ca.northline.booking.application.QuoteViews.QuoteView;
import ca.northline.booking.domain.QuoteContent;
import ca.northline.booking.domain.QuoteEnums.DepositKind;
import ca.northline.booking.domain.QuoteEnums.LineKind;
import ca.northline.booking.domain.QuoteEnums.Warranty;
import ca.northline.booking.domain.QuoteLine;
import ca.northline.booking.web.QuoteResponses.AttachmentResponse;
import ca.northline.booking.web.QuoteResponses.LineResponse;
import ca.northline.booking.web.QuoteResponses.QuoteRequestResponse;
import ca.northline.booking.web.QuoteResponses.QuoteResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface QuoteWebMapper {

    @Mapping(target = "id", source = "quote.id")
    @Mapping(target = "requestId", source = "quote.requestId")
    @Mapping(target = "ref", source = "quote.ref")
    @Mapping(target = "version", source = "quote.version")
    @Mapping(target = "state", source = "quote.state")
    @Mapping(target = "lines", source = "quote.content.lines")
    @Mapping(target = "labourCents", source = "quote.totals.labourCents")
    @Mapping(target = "partsCents", source = "quote.totals.partsCents")
    @Mapping(target = "feesCents", source = "quote.totals.feesCents")
    @Mapping(target = "discountCents", source = "quote.totals.discountCents")
    @Mapping(target = "subtotalCents", source = "quote.totals.subtotalCents")
    @Mapping(target = "taxBps", source = "quote.totals.taxBps")
    @Mapping(target = "taxCents", source = "quote.totals.taxCents")
    @Mapping(target = "totalCents", source = "quote.totals.totalCents")
    @Mapping(target = "depositKind", source = "quote.content.depositKind")
    @Mapping(target = "depositBps", source = "quote.content.depositBps")
    @Mapping(target = "depositCents", source = "quote.totals.depositCents")
    @Mapping(target = "scope", source = "quote.content.scope")
    @Mapping(target = "exclusions", source = "quote.content.exclusions")
    @Mapping(target = "warranty", source = "quote.content.warranty")
    @Mapping(target = "proposedAt", source = "quote.content.proposedAt")
    @Mapping(target = "durationMin", source = "quote.content.durationMin")
    @Mapping(target = "validHours", source = "quote.content.validHours")
    @Mapping(target = "validUntil", source = "quote.validUntil")
    @Mapping(target = "sentAt", source = "quote.sentAt")
    @Mapping(target = "attachments", source = "attachments")
    QuoteResponse toResponse(QuoteView view);

    @Mapping(target = "amountCents", expression = "java(line.amountCents())")
    LineResponse toResponse(QuoteLine line);

    AttachmentResponse toResponse(MediaInfo media);

    QuoteRequestResponse toResponse(QuoteRequestCard card);

    List<QuoteRequestResponse> toResponses(List<QuoteRequestCard> cards);

    /** Request body → domain content; {@link QuoteContent} re-validates every rule. */
    default QuoteContent toContent(QuoteBody body) {
        var lines = Objects.requireNonNullElse(body.lines(), List.<QuoteLineBody>of()).stream()
                .map(l -> new QuoteLine(
                        Objects.requireNonNullElse(l.kind(), LineKind.LABOUR),
                        Objects.requireNonNullElse(l.description(), ""),
                        l.note(),
                        Objects.requireNonNullElse(l.qty(), BigDecimal.ONE),
                        Objects.requireNonNullElse(l.unitCents(), 0L),
                        Objects.requireNonNullElse(l.taxable(), true)))
                .toList();
        return new QuoteContent(
                lines,
                Objects.requireNonNullElse(body.scope(), ""),
                body.exclusions(),
                body.proposedAt(),
                body.durationMin(),
                Objects.requireNonNullElse(body.validHours(), 0),
                Objects.requireNonNullElse(body.warranty(), Warranty.NONE),
                Objects.requireNonNullElse(body.depositKind(), DepositKind.NONE),
                body.depositBps(),
                Objects.requireNonNullElse(body.attachments(), List.of()));
    }
}
