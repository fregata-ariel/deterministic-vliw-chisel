// Test Quarto's standard Mermaid renderer and tabs; nothing here is shipped in the manual.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const out = path.resolve(__dirname, '../_output');
const qa = path.join(out, 'qa');
fs.mkdirSync(qa, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 960 } });
    const errors = [], external = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route(/^https?:/, route => { external.push(route.request().url()); return route.abort(); });
    await page.goto(pathToFileURL(path.join(out, 'index.html')).href);
    await page.waitForFunction(() => document.querySelectorAll('svg.mermaid-js').length === 14);
    const tabsets = page.locator('.panel-tabset');
    assert.equal(await tabsets.count(), 4);
    for (const group of await tabsets.all()) {
      for (const tab of await group.locator('[role="tab"]').all()) {
        await tab.click();
        assert.equal(await tab.getAttribute('aria-selected'), 'true');
        const target = await tab.getAttribute('aria-controls');
        await page.locator(`#${target} svg.mermaid-js`).first().waitFor({ state: 'visible' });
      }
    }
    assert.equal(await page.locator('svg.mermaid-js').count(), 14);
    assert.equal(await page.getByText(/Syntax error in text/).count(), 0);
    const animated = await page.locator('svg.mermaid-js path').evaluateAll(paths => paths.some(p => getComputedStyle(p).animationName !== 'none'));
    assert.ok(animated, 'native Mermaid edge animation is present');
    // Select the Core and E4 panels for visual review.
    await tabsets.nth(0).locator('[role="tab"]').nth(0).click();
    await tabsets.nth(2).locator('[role="tab"]').nth(2).click();
    await tabsets.nth(0).screenshot({ path: path.join(qa, 'architecture.png') });
    await tabsets.nth(2).screenshot({ path: path.join(qa, 'cycle-e4.png') });
    await page.screenshot({ path: path.join(qa, 'html-desktop.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await tabsets.nth(2).screenshot({ path: path.join(qa, 'html-mobile.png') });
    assert.deepEqual(errors, []);
    assert.deepEqual(external, [], 'HTML must use embedded Quarto resources');
    console.log('Native Quarto tabs, 14 Mermaid diagrams, built-in animation and offline rendering passed.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
