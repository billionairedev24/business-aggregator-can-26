package ca.northline.auth.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The two Twilio REST calls northline-auth needs (API version 2010-04-01): send an SMS, and place a call that reads a
 * TwiML document. Form-encoded requests, JSON answers, HTTP Basic with the account SID and auth token.
 */
@HttpExchange(
        url = "/2010-04-01/Accounts/{account}",
        contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        accept = MediaType.APPLICATION_JSON_VALUE)
interface TwilioApi {

    /** {@code To}, {@code Body} and {@code From} or {@code MessagingServiceSid}. */
    @PostExchange("/Messages.json")
    Resource sendMessage(@PathVariable String account, @RequestBody MultiValueMap<String, String> form);

    /** {@code To}, {@code From} and {@code Twiml}. */
    @PostExchange("/Calls.json")
    Resource createCall(@PathVariable String account, @RequestBody MultiValueMap<String, String> form);

    /** A created message or call ({@code SM…} / {@code CA…}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Resource(String sid, @Nullable String status) {}

    /** Twilio's error body, e.g. {@code {"code": 21211, "message": "Invalid 'To' Phone Number", "status": 400}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Failure(@Nullable Integer code, @Nullable String message) {}
}
