/**
 * SMS and voice delivery shared by northline-auth, the api and the worker (S-8 adapters, extracted in S-27):
 * {@link ca.northline.sms.SmsTransport} and its adapters, chosen by {@code northline.sms.provider} through
 * {@link ca.northline.sms.SmsTransports} / {@link ca.northline.sms.SmsTransportConfiguration}. What a message says is
 * the caller's business (auth's code texts, the worker's notification texts).
 */
@NullMarked
package ca.northline.sms;

import org.jspecify.annotations.NullMarked;
