/**
 * Studio composition: screens that combine several modules (Dashboard, nav badges). It only talks to other modules'
 * public {@code api} packages — never to their repositories.
 */
@ApplicationModule(displayName = "studio")
@NullMarked
package ca.northline.studio;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
