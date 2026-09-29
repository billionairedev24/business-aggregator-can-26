package ca.northline.food.web;

import static ca.northline.shared.security.MerchantPermission.EDIT;
import static ca.northline.shared.security.MerchantPermission.VIEW;

import ca.northline.food.application.MenuUseCases.EditMenus;
import ca.northline.food.application.MenuUseCases.EditSections;
import ca.northline.food.application.MenuUseCases.ImportMenuCsv;
import ca.northline.food.application.MenuUseCases.ListMenus;
import ca.northline.food.application.MenuUseCases.ViewMenu;
import ca.northline.food.application.MenuViews.ImportResult;
import ca.northline.food.application.MenuViews.MenuDetail;
import ca.northline.food.application.MenuViews.MenuSummary;
import ca.northline.food.application.MenuViews.SectionRef;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.web.KitchenRequests.MenuNameRequest;
import ca.northline.food.web.KitchenRequests.ScheduleRequest;
import ca.northline.food.web.KitchenRequests.SectionNameRequest;
import ca.northline.food.web.KitchenRequests.SectionOrderRequest;
import ca.northline.shared.ListResponse;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.CurrentMember;
import ca.northline.shared.security.RequiresMerchant;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Menu builder: menus, their sections, schedule, publish, CSV import. */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/menus")
@RequiredArgsConstructor
class MenuController {

    private final ListMenus listMenus;
    private final ViewMenu viewMenu;
    private final EditMenus editMenus;
    private final EditSections editSections;
    private final ImportMenuCsv importMenu;

    /** {@code {"items":[{id, name, status, schedule, sections:[{id, name, sort, itemCount}]}]}}. */
    @GetMapping
    @RequiresMerchant(VIEW)
    ListResponse<MenuSummary> menus(@PathVariable String merchantId) {
        return new ListResponse<>(listMenus.menus(merchantId));
    }

    @PostMapping
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    MenuSummary create(@PathVariable String merchantId, @Valid @RequestBody MenuNameRequest body) {
        return editMenus.create(merchantId, body.name());
    }

    @GetMapping("/{menuId}")
    @RequiresMerchant(VIEW)
    MenuDetail menu(@PathVariable String merchantId, @PathVariable String menuId) {
        return viewMenu.menu(merchantId, menuId);
    }

    @PatchMapping("/{menuId}")
    @RequiresMerchant(EDIT)
    MenuSummary rename(
            @PathVariable String merchantId, @PathVariable String menuId, @Valid @RequestBody MenuNameRequest body) {
        return editMenus.rename(merchantId, menuId, body.name());
    }

    @PutMapping("/{menuId}/schedule")
    @RequiresMerchant(EDIT)
    MenuSummary schedule(
            @PathVariable String merchantId, @PathVariable String menuId, @Valid @RequestBody ScheduleRequest body) {
        return editMenus.schedule(merchantId, menuId, body.toSchedule());
    }

    /** 409 {@code not_approved} until Northline approves the kitchen. */
    @PostMapping("/{menuId}/publish")
    @RequiresMerchant(EDIT)
    MenuSummary publish(@PathVariable String merchantId, @PathVariable String menuId, CurrentMember member) {
        return editMenus.publish(merchantId, menuId, member.userId());
    }

    @PostMapping("/{menuId}/hide")
    @RequiresMerchant(EDIT)
    MenuSummary hide(@PathVariable String merchantId, @PathVariable String menuId) {
        return editMenus.hide(merchantId, menuId);
    }

    @PostMapping("/{menuId}/sections")
    @RequiresMerchant(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    SectionRef addSection(
            @PathVariable String merchantId, @PathVariable String menuId, @Valid @RequestBody SectionNameRequest body) {
        return editSections.add(merchantId, menuId, body.name());
    }

    @PatchMapping("/{menuId}/sections/{sectionId}")
    @RequiresMerchant(EDIT)
    SectionRef renameSection(
            @PathVariable String merchantId,
            @PathVariable String menuId,
            @PathVariable String sectionId,
            @Valid @RequestBody SectionNameRequest body) {
        return editSections.rename(merchantId, menuId, sectionId, body.name());
    }

    /** Drag to reorder: the full ordered list of section ids. */
    @PutMapping("/{menuId}/sections/order")
    @RequiresMerchant(EDIT)
    ListResponse<SectionRef> reorder(
            @PathVariable String merchantId,
            @PathVariable String menuId,
            @Valid @RequestBody SectionOrderRequest body) {
        return new ListResponse<>(editSections.reorder(merchantId, menuId, body.sectionIds()));
    }

    /** Multipart {@code file} (CSV ≤ 1 MB). All rows or none; errors on {@code rows[<line>].<field>}. */
    @PostMapping("/{menuId}/import")
    @RequiresMerchant(EDIT)
    ImportResult importCsv(
            @PathVariable String merchantId,
            @PathVariable String menuId,
            @RequestPart("file") @Nullable MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw RuleViolation.of("file", "required", KitchenMessages.CSV_FILE);
        }
        return importMenu.importCsv(merchantId, menuId, file.getBytes());
    }
}
