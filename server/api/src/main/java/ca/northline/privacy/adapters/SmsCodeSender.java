package ca.northline.privacy.adapters;

import ca.northline.privacy.application.CodeSender;
import ca.northline.sms.PhoneNumbers;
import ca.northline.sms.SmsTransport;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** The verification code by SMS (the shared SMS library, {@code SMS_PROVIDER}). The code is never logged. */
@Slf4j
@Component
@RequiredArgsConstructor
class SmsCodeSender implements CodeSender {

    private final SmsTransport sms;

    @Override
    public void send(String phone, String code, Locale locale) {
        sms.sendText(phone, text(code, locale));
        log.info("Privacy verification code sent to {}", PhoneNumbers.masked(phone));
    }

    static String text(String code, Locale locale) {
        return "fr".equals(locale.getLanguage())
                ? "Northline : votre code pour confirmer votre demande sur vos renseignements personnels est " + code
                        + ". Ne le communiquez à personne."
                : "Northline: your code to confirm your personal information request is " + code
                        + ". Don't share it with anyone.";
    }
}
