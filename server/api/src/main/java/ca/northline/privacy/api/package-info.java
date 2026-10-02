/**
 * Privacy module's public API: the events of the erasure pipeline. They carry ids only — never a name, a contact or
 * what was erased.
 */
@NamedInterface("api")
@NullMarked
package ca.northline.privacy.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
