package ca.northline.privacy.application;

import java.util.Locale;

/** Outbound port: texts a privacy request's verification code to the account's verified mobile. */
public interface CodeSender {

    void send(String phone, String code, Locale locale);
}
