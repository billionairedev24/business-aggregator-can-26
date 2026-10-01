/**
 * Discovery: the consumer site's landing reads that span several modules (S-46 home: counts per section, category and
 * cuisine, trusted providers near the visitor). Public, read-only; it only talks to other modules' {@code api}
 * packages.
 */
@ApplicationModule(displayName = "discovery")
@NullMarked
package ca.northline.discovery;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
