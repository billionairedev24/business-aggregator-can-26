package ca.northline.privacy.domain;

import ca.northline.shared.CodedEnum;

/** The grounds the laws give for one extension of the deadline. */
public enum ExtensionReason implements CodedEnum {
    /** Meeting the deadline would unreasonably interfere with operations (many records). */
    VOLUME,
    /** Consultations are needed to answer. */
    CONSULTATION,
    /** Records must be converted into another format. */
    CONVERSION
}
