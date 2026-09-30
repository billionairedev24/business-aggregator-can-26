package ca.northline.auth.sms;

import ca.northline.sms.aws.AwsSmsTransport;

/**
 * {@code northline.sms.provider=aws}: codes through the shared AWS End User Messaging adapter
 * ({@link AwsSmsTransport}: {@code SendTextMessage} transactional, {@code SendVoiceMessage} with the Polly voice of the
 * language). Kept so the platform can run entirely on AWS; Twilio stays the default recommendation.
 */
final class AwsSmsSender extends TransportSmsSender {

    AwsSmsSender(AwsSmsTransport transport) {
        super(transport);
    }
}
