/* Run after `quarto render docs/manual`. Requires Playwright + Chromium. */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { chromium } = require("playwright");
const manual = path.resolve(__dirname, "..");
const root = path.resolve(manual, "../..");
const output = path.join(manual, "_output");
const url = pathToFileURL(path.join(output, "index.html")).href;
const qa = path.join(output, "qa");
fs.mkdirSync(qa, { recursive: true });

(async () => {
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : {})
  });
  const errors = [], external = [];
  const context = await browser.newContext({ viewport: { width: 1360, height: 1000 } });
  await context.route(/^https?:/, route => { external.push(route.request().url()); return route.abort(); });
  const page = await context.newPage();
  page.on("pageerror", e => errors.push(e.message));
  page.on("console", msg => { if (msg.type() === "error") errors.push(msg.text()); });
  const text = selector => page.locator(selector).innerText();
  const click = selector => page.locator(selector).click();
  const seek = async cycle => {
    await page.locator("#replay-cycle").evaluate((el, value) => {
      el.value = value; el.dispatchEvent(new Event("input", { bubbles: true }));
    }, String(cycle));
  };
  try {
    await page.goto(url);
    await page.waitForFunction(() => document.querySelector("#replay [data-play]").getAttribute("aria-pressed") === "false");
    assert.match(await page.title(), /動く図で読む/);
    assert.equal(await page.locator(".manual-figure").count(), 5);
    await page.screenshot({ path: path.join(qa, "manual-desktop.png") });

    // Source anchors and unique IDs: catch broken section references and widget collisions.
    const linkProblems = await page.evaluate(() => {
      const ids = [...document.querySelectorAll("[id]")].map(el => el.id);
      const duplicate = ids.filter((id, i) => ids.indexOf(id) !== i);
      const broken = [...document.querySelectorAll('a[href^="#"]')].filter(a => a.hash.length > 1 && !document.getElementById(decodeURIComponent(a.hash.slice(1)))).map(a => a.hash);
      return { duplicate, broken };
    });
    assert.deepEqual(linkProblems, { duplicate: [], broken: [] });
    assert.equal(await page.locator("#core-diagram").isVisible(), true);
    assert.equal(await page.locator("#fixed-diagram").isVisible(), false);
    await click("#architecture [data-next]");
    assert.match(await text("#architecture-step"), /2 \/ 6/);
    await page.locator("#architecture").screenshot({ path: path.join(qa, "architecture-core.png") });
    await page.selectOption("#architecture-mode", "fixed");
    assert.equal(await page.locator("#core-diagram").isVisible(), false);
    assert.equal(await page.locator("#fixed-diagram").isVisible(), true);
    await click("#architecture [data-next]");
    await page.locator("#architecture").screenshot({ path: path.join(qa, "architecture-fixed.png") });

    // Check every visible boundary against original fixture data, then seek backwards.
    for (const name of ["isa_example", "consecutive_mul"]) {
      await page.selectOption("#replay-case", name);
      const fixture = JSON.parse(fs.readFileSync(path.join(root, `tests/cases/${name}.json`), "utf8"));
      const trace = fs.readFileSync(path.join(root, fixture.expect.trace), "utf8").trim().split("\n").map(JSON.parse);
      const registers = { ...fixture.inputs.registers }, memory = { ...fixture.inputs.memory };
      for (const row of trace) {
        row.rf_commit.forEach(w => { registers[`r${w.reg}`] = w.value; });
        row.mem_commit.forEach(w => { memory[w.addr] = w.value; });
        await seek(row.cycle);
        const summary = await text("#replay-summary");
        assert.match(summary, new RegExp(`busy = ${Number(row.busy)}`));
        assert.match(summary, new RegExp(`done = ${Number(row.done)}`));
        assert.equal(await page.locator('#replay_table [aria-current="step"]').getAttribute("data-cycle"), String(row.cycle));
        const cells = await page.locator("#replay-state .state-cell").allInnerTexts();
        for (const [r, v] of Object.entries(registers)) assert.ok(cells.some(c => c === `${r}\n${v}`), `${name} E${row.cycle} ${r}=${v}`);
        for (const [a, v] of Object.entries(memory)) assert.ok(cells.some(c => c === `mem[${a}]\n${v}`), `${name} E${row.cycle} mem[${a}]=${v}`);
      }
      assert.equal(await page.locator("#replay [data-next]").isDisabled(), true);
      await click("#replay [data-reset]");
      assert.match(await text("#replay-state"), /未定義/);
      assert.equal(await page.locator("#replay [data-prev]").isDisabled(), true);
    }
    await page.selectOption("#replay-case", "isa_example");
    await seek(4);
    await page.locator("#replay").screenshot({ path: path.join(qa, "replay-e4.png") });
    // Native keyboard controls and slider.
    await page.locator("#replay-cycle").focus();
    await page.keyboard.press("Home");
    await page.keyboard.press("ArrowRight");
    assert.equal(await page.locator("#replay-cycle").inputValue(), "1");
    await click("#replay [data-reset]");
    await click("#replay [data-play]");
    await page.waitForFunction(() => document.querySelector("#replay-cycle").value === "1");
    await click("#replay [data-play]");
    await page.waitForTimeout(1450);
    assert.equal(await page.locator("#replay-cycle").inputValue(), "1");
    // Restart at the end, then verify finite playback stops at done without wrapping.
    await seek(7); await click("#replay [data-play]");
    assert.equal(await page.locator("#replay-cycle").inputValue(), "0");
    await page.waitForFunction(() => document.querySelector("#replay-cycle").value === "7" && document.querySelector("#replay [data-play]").getAttribute("aria-pressed") === "false", null, { timeout: 15000 });
    await page.waitForTimeout(1450);
    assert.equal(await page.locator("#replay-cycle").inputValue(), "7");

    // ISA version, malformed input, reserved bits, and byte order.
    await page.selectOption("#decoder-example", "0000000045123000");
    assert.match(await text("#decoder-status"), /未知opcode/);
    await page.selectOption("#decoder-isa", "mvp-1");
    assert.match(await text("#decoder-asm"), /MAC r5, r1, r2, r3/);
    assert.match(await text("#decoder-bytes"), /00 30 12 45 00 00 00 00/);
    assert.match(await text("#compute-fields"), /rs3/);
    await page.locator("#decoder").screenshot({ path: path.join(qa, "decoder-mac.png") });
    await page.locator("#decoder-hex").fill("xyz");
    assert.match(await text("#decoder-status"), /16桁/);
    await page.selectOption("#decoder-example", "0000000014310001");
    assert.match(await text("#decoder-status"), /予約bit/);

    for (const [name, rule] of [["raw_mul_next_cycle", "L3"], ["mul_then_add_port", "L5"], ["early_halt_ld", "L6"], ["mac_store_read_ports", "L11"]]) {
      await page.selectOption("#hazard-case", name);
      await click("#hazards [data-next]");
      assert.match(await text("#hazard-detail"), new RegExp(rule));
      assert.ok(await page.locator("#hazard-view .conflict").count() > 0);
      await click("#hazards [data-next]");
      assert.equal(await page.locator("#hazard-view .conflict").count(), 0);
      assert.match(await text("#hazard-step"), /配置を修正/);
    }
    await page.selectOption("#hazard-case", "mul_then_add_port"); await click("#hazards [data-next]");
    await page.locator("#hazards").screenshot({ path: path.join(qa, "hazard-l5.png") });
    await click("#compiler-flow [data-next]");
    assert.match(await text("#compiler-detail"), /2 \/ 6/);
    await page.locator("#compiler-flow [data-next]").focus();
    await page.keyboard.press("Enter");
    assert.match(await text("#compiler-detail"), /3 \/ 6/);

    // Motion preference removes moving dashes and the timeline transition.
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.reload();
    await click("#architecture [data-next]");
    await click("#architecture [data-play]");
    const animation = await page.locator("#core-diagram .active-flow").first().evaluate(el => getComputedStyle(el).animationName);
    assert.equal(animation, "none");
    await click("#architecture [data-play]");
    const transition = await page.locator("#replay-timeline [data-cursor]").evaluate(el => getComputedStyle(el).transitionDuration);
    assert.equal(transition, "0s");

    for (const width of [360, 768]) {
      await page.setViewportSize({ width, height: 900 });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `horizontal page overflow at ${width}px`);
      await page.locator("#replay").screenshot({ path: path.join(qa, `replay-${width}.png`) });
    }
    assert.deepEqual(external, [], "rendered HTML must not request external resources");
    assert.deepEqual(errors, [], "browser console and page errors");

    const plain = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 1280, height: 900 } });
    const staticPage = await plain.newPage();
    await staticPage.goto(url);
    assert.equal(await staticPage.locator("#core-diagram").isVisible(), true);
    assert.equal(await staticPage.locator("#fixed-diagram").isVisible(), false);
    assert.equal(await staticPage.locator("#replay_table tbody tr").count(), 8);
    assert.equal(await staticPage.locator("#replay [data-play]").isVisible(), false);
    assert.match(await staticPage.locator("#replay_table").innerText(), /mem\[1\] = 28/);
    await staticPage.locator("#replay").screenshot({ path: path.join(qa, "replay-no-javascript.png") });
    await plain.close();
    console.log("Browser checks passed: offline HTML, controls, all trace boundaries, ISA, hazards, keyboard, 360/768/1360px, reduced motion, no-JS fallback.");
    console.log(`Screenshots: ${qa}`);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
