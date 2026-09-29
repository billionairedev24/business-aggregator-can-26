package ca.northline.messaging.domain;

import ca.northline.shared.CodedEnum;

/** The Studio portal a business sees (its merchant type): picks help topics, articles and quick replies. */
public enum Portal implements CodedEnum {
    PROVIDER,
    SELLER,
    BOTH,
    KITCHEN;

    public static Portal ofMerchantType(String type) {
        return CodedEnum.fromCode(Portal.class, type);
    }
}
