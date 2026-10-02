/**
 * Privacy requests across modules (S-105): {@link ca.northline.shared.privacy.PersonalDataContributor} is what each
 * module that keeps personal data implements — its part of the access export, of the erasure pipeline and of
 * corrections. The {@code privacy} module runs the requests through it; {@link
 * ca.northline.shared.privacy.PersonalDataSql} helps a module render its rows and lift its immutability triggers for a
 * privacy change.
 */
@NamedInterface("privacy")
@NullMarked
package ca.northline.shared.privacy;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
