package ca.northline.privacy.domain;

import ca.northline.shared.CodedEnum;

/** What the person asks for: a copy of their data, a correction, or that it be erased. */
public enum RequestType implements CodedEnum {
    ACCESS,
    CORRECTION,
    ERASURE
}
