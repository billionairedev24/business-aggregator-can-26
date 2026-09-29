package ca.northline.food.application;

import ca.northline.food.application.ModifierUseCases.EditModifierGroups;
import ca.northline.food.application.ModifierUseCases.GroupCommand;
import ca.northline.food.application.ModifierUseCases.GroupView;
import ca.northline.food.application.ModifierUseCases.ListModifierGroups;
import ca.northline.food.application.ModifierUseCases.OptionCommand;
import ca.northline.food.application.ModifierUseCases.OptionView;
import ca.northline.food.domain.ModifierGroup;
import ca.northline.food.domain.ModifierRule;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ModifierGroupService implements ListModifierGroups, EditModifierGroups {

    private final ModifierGroupStore store;

    @Override
    public List<GroupView> groups(String merchantId) {
        var usage = store.usage(merchantId);
        return store.groups(merchantId).stream().map(g -> view(g, usage)).toList();
    }

    @Override
    @Transactional
    public GroupView create(String merchantId, GroupCommand command) {
        var group = build(merchantId, Ids.next(), command, store.nextSort(merchantId), Set.of());
        store.insert(group);
        return view(group, Map.of());
    }

    @Override
    @Transactional
    public GroupView update(String merchantId, String groupId, GroupCommand command) {
        var before = require(merchantId, groupId);
        var keptIds = before.options().stream()
                .map(ModifierGroup.Option::id)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        var group = build(merchantId, groupId, command, before.sort(), keptIds);
        store.update(group);
        return view(group, store.usage(merchantId));
    }

    @Override
    @Transactional
    public GroupView addOption(String merchantId, String groupId, OptionCommand option) {
        var before = require(merchantId, groupId);
        var options = new ArrayList<>(before.options());
        options.add(new ModifierGroup.Option(
                Ids.next(), option.name().strip(), option.priceDeltaCents(), false, false, options.size()));
        var group = before.toBuilder().options(options).build().validated(otherOptionIds(merchantId, groupId));
        store.update(group);
        return view(group, store.usage(merchantId));
    }

    @Override
    @Transactional
    public void delete(String merchantId, String groupId) {
        require(merchantId, groupId);
        store.delete(merchantId, groupId);
    }

    private ModifierGroup build(
            String merchantId, String groupId, GroupCommand c, int sort, Set<String> existingOptionIds) {
        var options = IntStream.range(0, c.options().size())
                .mapToObj(i -> {
                    var o = c.options().get(i);
                    var id = o.id() != null && existingOptionIds.contains(o.id()) ? o.id() : Ids.next();
                    return new ModifierGroup.Option(
                            id, o.name().strip(), o.priceDeltaCents(), o.isDefault(), o.soldOut(), i);
                })
                .toList();
        return ModifierGroup.builder()
                .id(groupId)
                .merchantId(merchantId)
                .name(c.name().strip())
                .rule(new ModifierRule(c.pickRule(), c.pickCount(), c.required()))
                .showForOptionIds(List.copyOf(Set.copyOf(c.showForOptionIds())))
                .options(options)
                .sort(sort)
                .build()
                .validated(otherOptionIds(merchantId, groupId));
    }

    private Set<String> otherOptionIds(String merchantId, String groupId) {
        return store.groups(merchantId).stream()
                .filter(g -> !g.id().equals(groupId))
                .flatMap(g -> g.options().stream())
                .map(ModifierGroup.Option::id)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private ModifierGroup require(String merchantId, String groupId) {
        return store.group(merchantId, groupId).orElseThrow(() -> new NotFound("modifier group", groupId));
    }

    private static GroupView view(ModifierGroup g, Map<String, Integer> usage) {
        return new GroupView(
                g.id(),
                g.name(),
                g.rule().rule(),
                g.rule().count(),
                g.rule().required(),
                g.rule().minSelect(),
                g.rule().maxSelect(),
                g.showForOptionIds(),
                g.options().stream()
                        .map(o -> new OptionView(
                                java.util.Objects.requireNonNull(o.id()),
                                o.name(),
                                o.priceDeltaCents(),
                                o.isDefault(),
                                o.soldOut()))
                        .toList(),
                usage.getOrDefault(g.id(), 0));
    }
}
