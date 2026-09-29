package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code offers.image_source}: inherit the shared catalogue record's images, or upload the seller's own. */
public enum ImageSource implements CodedEnum {
    SHARED,
    OWN
}
