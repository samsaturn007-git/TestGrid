import { expect, test } from '@playwright/test';
import {
  cancelRun,
  cleanUp,
  createRun,
  getRun,
  headerStatus,
  isTerminal,
  restartProxy,
  servedEngines,
  runIdFromUrl,
  tabTo,
  waitForRun,
} from './support.ts';

// PRD-05 T7: the dashboard end to end against the real stack (API, worker, demo shop; not
// mocked). Each test cancels what it leaves running, so the one worker slot is free for the next.

const created: string[] = [];

test.afterEach(async ({ request }) => {
  await cleanUp(request, created.splice(0));
});

test.describe('dashboard end to end (PRD-05)', () => {
  test('UI-002: a run from the dialog ends PASSED with PASS, FAILED with PRODUCT_DEFECT, naming engine, attempt and case', async ({
    page,
    request,
  }) => {
    for (const [mode, expected] of [
      ['PASS', 'PASSED'],
      ['PRODUCT_DEFECT', 'FAILED'],
    ] as const) {
      await page.goto('/');
      await page.getByRole('button', { name: 'New run' }).click();
      const dialog = page.getByRole('dialog', { name: 'New run' });
      await dialog.getByLabel('Suite').selectOption('checkout-smoke');
      await dialog.getByRole('checkbox', { name: 'firefox' }).uncheck();
      await dialog.getByRole('checkbox', { name: 'webkit' }).uncheck();
      await dialog.getByRole('radio', { name: new RegExp(`^${mode}`) }).check();
      await dialog.getByRole('button', { name: 'Start run' }).click();

      await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/);
      const runId = runIdFromUrl(page);
      created.push(runId);
      await expect(page.getByText('Live')).toBeVisible();

      // The page reaches the outcome by itself: no reload.
      await expect(headerStatus(page)).toHaveAccessibleName(`Run status: ${expected}`, {
        timeout: 120_000,
      });
      expect((await getRun(request, runId)).status).toBe(expected);
    }

    // The PRODUCT_DEFECT failure: engine column, attempt number and the failing case.
    const matrix = page.getByRole('table', { name: /Case results by engine/ });
    await expect(matrix.getByRole('columnheader', { name: /chromium/ })).toContainText(
      'attempt 1 of 3',
    );
    const row = matrix
      .getByRole('row')
      .filter({ has: page.getByRole('rowheader', { name: /cart-total/ }) });
    await expect(row.getByRole('img', { name: 'Case status: FAILED' })).toBeVisible();
    await expect(row).toContainText('ASSERTION');

    await page.getByRole('button', { name: 'Show case results' }).click();
    const cases = page.getByRole('table', { name: 'Case results on chromium, attempt 1' });
    const first = cases.getByRole('row').nth(1);
    await expect(first).toContainText('cart-total');
    await expect(first.getByRole('img', { name: 'Case status: FAILED' })).toBeVisible();
  });

  test('AC-4: keyboard only, start a run, land on its page, and cancel it', async ({
    page,
    request,
  }) => {
    await page.goto('/');
    await expect(page.getByText('Demo shop')).toBeVisible();

    await tabTo(page, page.getByRole('button', { name: 'New run' }));
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'New run' });
    await expect(dialog.getByLabel('Suite')).toBeFocused();
    // Defaults: every allowed engine and PASS. One worker slot runs targets one by one, so
    // the later targets are still QUEUED when the cancel lands.
    await tabTo(page, dialog.getByRole('button', { name: 'Start run' }));
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/);
    const runId = runIdFromUrl(page);
    created.push(runId);
    await expect(page.getByRole('main')).toBeFocused();

    await tabTo(page, page.getByRole('button', { name: 'Cancel run' }));
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: 'Yes, cancel run' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/Cancel requested at/)).toBeVisible();

    // QUEUED targets end CANCELLED; with PASS, the aggregate can only be CANCELLED.
    await expect(headerStatus(page)).toHaveAccessibleName('Run status: CANCELLED', {
      timeout: 120_000,
    });
    expect((await getRun(request, runId)).status).toBe('CANCELLED');
  });

  test('UI-001 / AC-3: the stream is blocked mid-run, the page polls and settles on the database state, then goes live again', async ({
    page,
    request,
  }) => {
    // TIMEOUT keeps the run going long enough to block and restore the stream: about 45 s per
    // target on one worker slot, and the restore needs the run unfinished ~50 s in.
    const engines = await servedEngines(request);
    expect(
      engines.length,
      'UI-001 needs a worker serving at least two engines (TESTGRID_WORKER_ENGINES)',
    ).toBeGreaterThanOrEqual(2);
    const run = await createRun(request, 'checkout-smoke', engines, 'TIMEOUT');
    created.push(run.id);
    await page.goto(`/runs/${run.id}`);
    await expect(page.getByText('Live')).toBeVisible();

    // Block every new stream request (Playwright), then cut the open one (proxy restart).
    await page.route('**/api/v1/runs/*/events', (route) => route.abort());
    restartProxy();

    await expect(page.getByText('Live updates paused, refreshing every 5s')).toBeVisible({
      timeout: 20_000,
    });
    // While polling, the page follows the database.
    const now = await getRun(request, run.id);
    await expect(headerStatus(page)).toHaveAccessibleName(`Run status: ${now.status}`, {
      timeout: 12_000,
    });

    // Restore: the page tries a fresh stream within 30 s and is live again.
    await page.unroute('**/api/v1/runs/*/events');
    await expect(page.getByText('Live', { exact: true })).toBeVisible({ timeout: 45_000 });
    await expect(page.getByText('Live updates paused, refreshing every 5s')).toHaveCount(0);

    // Finish it and check the page lands exactly where the database is, with nothing pulsing.
    await cancelRun(request, run.id);
    const final = await waitForRun(request, run.id, (r) => isTerminal(r.status));
    await expect(headerStatus(page)).toHaveAccessibleName(`Run status: ${final.status}`, {
      timeout: 15_000,
    });
    await expect(page.locator('.tg-pulse')).toHaveCount(0);
    for (const target of final.targets) {
      await expect(
        page
          .getByRole('table', { name: /Case results by engine/ })
          .getByRole('columnheader', { name: new RegExp(target.engine) }),
      ).toContainText(target.status);
    }
  });

  test('UI-003 / AC-5: rerunning failed targets makes a linked run with only those targets', async ({
    page,
    request,
  }) => {
    // One slot: the first target runs while the others wait. Cancel once it has FAILED, so the
    // run ends with FAILED and CANCELLED targets.
    const engines = await servedEngines(request);
    // A rerun of only the failed targets needs a run with failed and other targets: two engines.
    expect(
      engines.length,
      'UI-003 needs a worker serving at least two engines (TESTGRID_WORKER_ENGINES)',
    ).toBeGreaterThanOrEqual(2);
    const parent = await createRun(request, 'checkout-smoke', engines, 'PRODUCT_DEFECT');
    created.push(parent.id);
    await waitForRun(request, parent.id, (r) => r.targets.some((t) => t.status === 'FAILED'));
    await cancelRun(request, parent.id);
    const finished = await waitForRun(request, parent.id, (r) => isTerminal(r.status));
    const failed = finished.targets
      .filter((t) => ['FAILED', 'TIMED_OUT', 'INFRA_ERROR'].includes(t.status))
      .map((t) => t.engine);
    expect(failed.length).toBeGreaterThan(0);
    expect(failed.length).toBeLessThan(finished.targets.length);

    await page.goto(`/runs/${parent.id}`);
    await page.getByRole('button', { name: `Rerun failed targets (${failed.join(', ')})` }).click();
    // Wait for the move to the new run (the page starts on a run URL already).
    await expect(page).not.toHaveURL(new RegExp(parent.id));
    await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/);
    const rerunId = runIdFromUrl(page);
    created.push(rerunId);

    const rerun = await getRun(request, rerunId);
    expect(rerun.trigger).toBe('RERUN');
    expect(rerun.parentRunId).toBe(parent.id);
    expect(rerun.targets.map((t) => t.engine).sort()).toEqual([...failed].sort());
    await expect(page.getByRole('link', { name: parent.id.slice(0, 8) })).toBeVisible();

    // And the link back the other way.
    await page.goto(`/runs/${parent.id}`);
    await expect(page.getByRole('link', { name: rerunId.slice(0, 8) })).toHaveAttribute(
      'href',
      `/runs/${rerunId}`,
    );
  });
});
