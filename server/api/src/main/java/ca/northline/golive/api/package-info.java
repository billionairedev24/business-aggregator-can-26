/**
 * What other modules give the go-live checklist: {@link ca.northline.golive.api.PilotReadiness}, implemented by the
 * console (it composes the pilot pipeline), so this module never reaches into the console.
 */
@NamedInterface("api")
@NullMarked
package ca.northline.golive.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
