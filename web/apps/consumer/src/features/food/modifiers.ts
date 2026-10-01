import type { Group } from './api';

/**
 * The kitchen's pick rules on the customer side (S-57, mirrors the server's `PickCheck`): exactly N (none, or N when
 * optional), at least N, up to N; a nested group counts only when an option it hangs off is picked.
 */
export const visibleGroups = (groups: Group[], picked: string[]) =>
  groups.filter(g => g.showForOptionIds.length === 0 || g.showForOptionIds.some(id => picked.includes(id)));

export type PickProblem = { groupId: string; key: 'pickExactly' | 'pickAtLeast' | 'pickUpTo'; count: number; group: string };

export function pickProblems(groups: Group[], picked: string[]): PickProblem[] {
  const out: PickProblem[] = [];
  for (const g of visibleGroups(groups, picked)) {
    const n = g.options.filter(o => picked.includes(o.id)).length;
    const ok = g.rule === 'exactly' ? n === g.count || (!g.required && n === 0)
      : g.rule === 'at_least' ? n >= g.count || (!g.required && n === 0)
      : n <= g.count && (!g.required || n >= 1);
    if (!ok) out.push({ groupId: g.id, key: g.rule === 'exactly' ? 'pickExactly' : g.rule === 'at_least' ? 'pickAtLeast' : 'pickUpTo', count: g.count, group: g.name });
  }
  return out;
}

/** Tapping an option: "pick 1" groups behave like radio buttons; others toggle within their maximum. */
export function toggle(groups: Group[], picked: string[], groupId: string, optionId: string): string[] {
  const g = groups.find(x => x.id === groupId);
  if (!g) return picked;
  const inGroup = new Set(g.options.map(o => o.id));
  if (picked.includes(optionId)) {
    if (g.rule === 'exactly' && g.count === 1 && g.required) return picked;
    return prune(groups, picked.filter(id => id !== optionId));
  }
  if (g.rule === 'exactly' && g.count === 1) return prune(groups, [...picked.filter(id => !inGroup.has(id)), optionId]);
  const max = g.rule === 'at_least' ? Infinity : g.count;
  const current = picked.filter(id => inGroup.has(id));
  if (current.length >= max) return picked;
  return [...picked, optionId];
}

/** Drops choices of nested groups that are no longer shown. */
function prune(groups: Group[], picked: string[]): string[] {
  const shown = new Set(visibleGroups(groups, picked).flatMap(g => g.options.map(o => o.id)));
  return picked.filter(id => shown.has(id));
}

/** Defaults a required "pick 1" group starts with (the kitchen's default option). */
export const defaults = (groups: Group[]) =>
  visibleGroups(groups, []).flatMap(g => (g.rule === 'exactly' && g.required ? g.options.filter(o => o.isDefault && !o.soldOut).slice(0, g.count).map(o => o.id) : []));

export const deltaOf = (groups: Group[], picked: string[]) =>
  groups.flatMap(g => g.options).filter(o => picked.includes(o.id)).reduce((n, o) => n + o.deltaCents, 0);

export const choiceNames = (groups: Group[], picked: string[]) =>
  groups.flatMap(g => g.options).filter(o => picked.includes(o.id)).map(o => o.name);
