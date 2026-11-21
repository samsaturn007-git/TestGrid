import axe from 'axe-core';
import { expect } from 'vitest';

/**
 * PRD-05 AC-4 at component level: no serious or critical axe violations. jsdom has no layout,
 * so colour contrast is checked by the Playwright axe run in T7, not here.
 */
export async function expectNoSeriousA11yViolations(root: Element = document.body) {
  const results = await axe.run(root, { rules: { 'color-contrast': { enabled: false } } });
  const serious = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(serious).toEqual([]);
}
