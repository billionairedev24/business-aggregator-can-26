package ca.northline.payments.domain;

/** Messages of the Refunds &amp; disputes screen (ours — validation-rules.md has none; see DECISIONS.md). */
public final class CaseMessages {
    public static final int RESPONSE_MAX = 2000;
    public static final String RESPONSE_TOO_LONG = "Keep your response under 2,000 characters.";
    public static final String RESPONSE_REQUIRED = "Write your response before sending this to an agent.";
    public static final String OFFER_RANGE = "Offer less than the full amount — or choose Full refund.";
    public static final String CONTEST_REASON_REQUIRED = "Tell the agent why you're contesting this refund.";
    public static final String EVIDENCE_REQUIRED = "Choose a file to upload.";
    public static final String EVIDENCE_TYPE = "Upload a photo (JPG, PNG, HEIC) or a PDF.";
    public static final String EVIDENCE_SIZE = "Files can be up to 10 MB.";
    public static final String EVIDENCE_COUNT = "You can attach up to 20 files.";

    public static final String CASE_CLOSED = "This case is already closed.";

    private CaseMessages() {}
}
