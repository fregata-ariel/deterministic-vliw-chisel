// Validate Quarto's built-in UI; this file is not included in the document.
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
    assert.equal(await page.locator('svg[id^="mermaid-figure-"]').count(), 10);
    assert.equal(await page.getByText(/Syntax error in text/).count(), 0);
    const groups = page.locator('.panel-tabset');
    assert.equal(await groups.count(), 2);
    let panel = 0;
    for (const group of await groups.all()) {
      for (const tab of await group.locator('[role="tab"]').all()) {
        await tab.click();
        assert.equal(await tab.getAttribute('aria-selected'), 'true');
        const target = await tab.getAttribute('aria-controls');
        const svg = page.locator(`#${target} svg[id^="mermaid-figure-"]`);
        await svg.waitFor({ state: 'visible' });
        const broken = await svg.locator('foreignObject').evaluateAll(labels => labels.filter(label => label.getBoundingClientRect().height <= 0 || /NaN/.test(label.parentElement.getAttribute('transform'))).length);
        assert.equal(broken, 0, 'previously hidden labels must remain visible');
        assert.ok(await svg.locator('text, .nodeLabel').count(), 'diagram or chart labels exist');
        await group.screenshot({ path: path.join(qa, `panel-${++panel}.png`) });
      }
    }
    for (const id of ['fig-issue-map', 'fig-wide-load', 'fig-read-port-rules', 'fig-decision-dependency']) {
      await page.locator(`#${id}`).screenshot({ path: path.join(qa, `${id}.png`) });
    }
    await page.screenshot({ path: path.join(qa, 'html-desktop.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(qa, 'html-mobile-page.png'), fullPage: true });
    const overflow = await page.locator('body *').evaluateAll(els => els.filter(e => e.getBoundingClientRect().right > innerWidth + 1).slice(0, 12).map(e => ({ tag: e.tagName, text: e.textContent.slice(0, 60), width: e.getBoundingClientRect().width })));
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), JSON.stringify(overflow));
    await groups.nth(1).screenshot({ path: path.join(qa, 'html-mobile.png') });
    assert.deepEqual(errors, []);
    assert.deepEqual(external, [], 'resources must be embedded for offline display');
    console.log('Issue #78: 10 figures, six tab panels, labels, mobile layout and offline display passed.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
