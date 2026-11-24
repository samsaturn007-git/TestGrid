import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { cleanUp, createRun, isTerminal, projectId, waitForRun } from './support.ts';

// PRD-05 AC-4 in a real browser: axe on every page, in both themes, with every WCAG 2.x A/AA
// rule including colour contrast (which jsdom cannot check). Serious and critical findings fail.

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
}

async function setTheme(page: Page, theme: 'dark' | 'light') {
  await page.addInitScript((t) => localStorage.setItem('testgrid.theme', t), theme);
}

let finishedRunId = '';
let project = '';

test.beforeAll(async ({ request }) => {
  project = await projectId(request);
  // A finished run with a failure, so the run page shows the matrix, attempts and cases.
  const run = await createRun(request, 'checkout-smoke', ['chromium'], 'PRODUCT_DEFECT');
  await waitForRun(request, run.id, (r) => isTerminal(r.status));
  finishedRunId = run.id;
});

test.afterAll(async ({ request }) => {
  await cleanUp(request, [finishedRunId]);
});

for (const theme of ['dark', 'light'] as const) {
  test.describe(`axe, ${theme} theme (PRD-05 AC-4)`, () => {
    test.beforeEach(async ({ page }) => {
      await setTheme(page, theme);
    });

    test('overview', async ({ page }) => {
      await page.goto('/');
      await expect(
        page.getByRole('region', { name: 'Run status · last 24 h' }).getByRole('listitem').first(),
      ).toBeVisible();
      await expect(page.getByRole('img', { name: /sessions in use/ })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });

    test('runs list with filters', async ({ page }) => {
      await page.goto('/runs?status=FAILED');
      await expect(page.getByRole('table', { name: 'Runs, newest first' })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });

    test('run detail with case results open', async ({ page }) => {
      await page.goto(`/runs/${finishedRunId}`);
      await expect(page.getByRole('rowheader', { name: /cart-total/ })).toBeVisible();
      // PRD-08 FR-7: the default policy fails a critical case, so the gate panel says FAIL.
      await expect(
        page
          .getByRole('region', { name: 'Quality gate' })
          .getByRole('img', { name: 'Quality gate: FAIL' }),
      ).toBeVisible();
      await page.getByRole('button', { name: 'Show case results' }).click();
      await expect(page.getByRole('tablist', { name: 'Engine' })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });

    test('PRD-06: timeline and the failed case drawer, with its screenshot loaded', async ({
      page,
    }) => {
      await page.goto(`/runs/${finishedRunId}`);
      await expect(
        page
          .getByRole('region', { name: 'Timeline' })
          .getByText(/heartbeats?/)
          .first(),
      ).toBeVisible();
      await page
        .getByRole('button', { name: /^cart-total on chromium:.*FAILED.*Open details$/ })
        .click();
      const drawer = page.getByRole('dialog', { name: /cart-total/ });
      const shot = drawer.getByRole('img', { name: /^Screenshot of cart-total/ });
      await expect(shot).toBeVisible();
      // The signed link really served the PNG into the <img> (CSP img-src 'self', attachment).
      await expect
        .poll(() => shot.evaluate((img: HTMLImageElement) => img.naturalWidth))
        .toBeGreaterThan(0);
      await expect(drawer.getByText(/^npx playwright show-trace trace-/)).toBeVisible();
      await expect(drawer.getByLabel('Log tail, redacted')).toContainText('[REDACTED]');
      expect(await seriousViolations(page)).toEqual([]);
      await page.keyboard.press('Escape');
      await expect(drawer).toBeHidden();
      await expect(
        page.getByRole('button', { name: /^cart-total on chromium:.*FAILED.*Open details$/ }),
      ).toBeFocused();
    });

    test('suites with cases expanded', async ({ page }) => {
      await page.goto(`/projects/${project}/suites`);
      await page.getByRole('button', { name: /Show 6 cases/ }).click();
      await expect(page.getByRole('table', { name: /Cases in/ })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });

    test('new-run dialog, with field errors showing', async ({ page }) => {
      await page.goto('/');
      await page.getByRole('button', { name: 'New run' }).click();
      const dialog = page.getByRole('dialog', { name: 'New run' });
      await dialog.getByLabel('Suite').selectOption('checkout-smoke');
      expect(await seriousViolations(page)).toEqual([]);
      await dialog.getByLabel(/Commit SHA/).fill('zz91');
      await dialog.getByRole('button', { name: 'Start run' }).click();
      await expect(dialog.getByRole('alert')).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });

    test('PRD-08: quality policy and quarantines', async ({ page }) => {
      await page.goto(`/projects/${project}/policy`);
      await expect(page.getByRole('region', { name: /Version \d+/ })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
      await page.goto('/quarantines');
      await expect(page.getByRole('form', { name: 'Quarantine a case' })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });

    test('workers', async ({ page }) => {
      await page.goto('/workers');
      await expect(page.getByRole('table', { name: /Workers/ })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });

    test('not found', async ({ page }) => {
      await page.goto('/nowhere');
      await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
      expect(await seriousViolations(page)).toEqual([]);
    });
  });
}
