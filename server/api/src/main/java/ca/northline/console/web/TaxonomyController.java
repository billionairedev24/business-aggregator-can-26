package ca.northline.console.web;

import ca.northline.catalogue.api.TaxonomyAdmin.CategoryInput;
import ca.northline.catalogue.api.TaxonomyAdmin.RegulatorInput;
import ca.northline.console.application.ManageTaxonomy;
import ca.northline.console.application.ManageTaxonomy.Actor;
import ca.northline.console.application.ManageTaxonomy.RegulatorRow;
import ca.northline.console.application.ManageTaxonomy.Resolved;
import ca.northline.console.application.ManageTaxonomy.Row;
import ca.northline.console.application.ManageTaxonomy.Screen;
import ca.northline.merchants.api.MerchantCategories.Limit;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.ConsoleAction;
import ca.northline.shared.security.ConsoleScreen;
import ca.northline.shared.security.CurrentStaff;
import ca.northline.shared.security.RequiresConsole;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console — catalogue taxonomy (S-94, design 03 {@code taxonomy}: admin only; changes need {@code vet}).
 *
 * <pre>
 * GET  /api/v1/console/taxonomy                                     Screen
 * POST /api/v1/console/taxonomy/categories {root?, parentId?, nameEn, nameFr?, bookingType?, regulatedRegistry?,
 *                                           requiresVsCheck}       201 Row   409 category_exists
 * PUT  /api/v1/console/taxonomy/categories/{id} {nameEn, nameFr?, bookingType?, regulatedRegistry?, requiresVsCheck}
 * PUT  /api/v1/console/taxonomy/categories/{id}/regulators/{province} {regulator: code|none|null}   Row
 * POST /api/v1/console/taxonomy/regulators {code, name, province, website?}   201   409 regulator_exists
 * PUT  /api/v1/console/taxonomy/regulators/{code} {name, province, website?}       409 regulator_in_use
 * PUT  /api/v1/console/taxonomy/limits/{merchantType} {max}                        Limit
 * POST /api/v1/console/taxonomy/suggestions/{suggestionId}/approve {CategoryInput}  Resolved
 * POST /api/v1/console/taxonomy/suggestions/{suggestionId}/merge {categoryId}       Resolved
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/console/taxonomy")
@RequiredArgsConstructor
class TaxonomyController {

    private final ManageTaxonomy taxonomy;

    record CategoryRequest(
            @Nullable String root,
            @Nullable String parentId,
            @Nullable String nameEn,
            @Nullable String nameFr,
            @Nullable String bookingType,
            @Nullable String regulatedRegistry,
            @Nullable Boolean requiresVsCheck) {

        CategoryInput input() {
            return new CategoryInput(
                    root,
                    parentId,
                    nameEn,
                    nameFr,
                    bookingType,
                    regulatedRegistry,
                    Boolean.TRUE.equals(requiresVsCheck));
        }
    }

    record RegulateRequest(@Nullable String regulator) {}

    record RegulatorRequest(
            @Nullable String code,
            @Nullable String name,
            @Nullable String province,
            @Nullable String website) {}

    record LimitRequest(@Nullable Integer max) {}

    record MergeRequest(@Nullable String categoryId) {}

    @GetMapping
    @RequiresConsole(ConsoleScreen.TAXONOMY)
    Screen screen() {
        return taxonomy.screen();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    Row create(@RequestBody CategoryRequest body, CurrentStaff staff) {
        return taxonomy.createCategory(body.input(), actor(staff));
    }

    @PutMapping("/categories/{id}")
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    Row update(@PathVariable String id, @RequestBody CategoryRequest body, CurrentStaff staff) {
        return taxonomy.updateCategory(id, body.input(), actor(staff));
    }

    @PutMapping("/categories/{id}/regulators/{province}")
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    Row regulate(
            @PathVariable String id,
            @PathVariable String province,
            @RequestBody RegulateRequest body,
            CurrentStaff staff) {
        return taxonomy.regulate(id, province, body.regulator(), actor(staff));
    }

    @PostMapping("/regulators")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    RegulatorRow createRegulator(@RequestBody RegulatorRequest body, CurrentStaff staff) {
        return taxonomy.createRegulator(
                new RegulatorInput(body.code(), body.name(), body.province(), body.website()), actor(staff));
    }

    @PutMapping("/regulators/{code}")
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    RegulatorRow updateRegulator(@PathVariable String code, @RequestBody RegulatorRequest body, CurrentStaff staff) {
        return taxonomy.updateRegulator(
                code, new RegulatorInput(code, body.name(), body.province(), body.website()), actor(staff));
    }

    @PutMapping("/limits/{merchantType}")
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    Limit setLimit(@PathVariable String merchantType, @RequestBody LimitRequest body, CurrentStaff staff) {
        if (body.max() == null) {
            throw RuleViolation.of("max", "required", "Enter a limit from 1 to 50.");
        }
        return taxonomy.setLimit(merchantType, body.max(), actor(staff));
    }

    @PostMapping("/suggestions/{suggestionId}/approve")
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    Resolved approve(@PathVariable String suggestionId, @RequestBody CategoryRequest body, CurrentStaff staff) {
        return taxonomy.approveSuggestion(suggestionId, body.input(), actor(staff));
    }

    @PostMapping("/suggestions/{suggestionId}/merge")
    @RequiresConsole(value = ConsoleScreen.TAXONOMY, actions = ConsoleAction.VET)
    Resolved merge(@PathVariable String suggestionId, @RequestBody MergeRequest body, CurrentStaff staff) {
        return taxonomy.mergeSuggestion(suggestionId, body.categoryId() == null ? "" : body.categoryId(), actor(staff));
    }

    private static Actor actor(CurrentStaff staff) {
        return new Actor(staff.userId(), staff.roleCodes());
    }
}
