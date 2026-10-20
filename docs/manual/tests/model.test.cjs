const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const model = require("../assets/model.js");
const root = path.resolve(__dirname, "../../..");
const json = p => JSON.parse(fs.readFileSync(path.join(root, p), "utf8"));
const sandbox = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../assets/data.js"), "utf8"), sandbox);
// Normalize objects from the separate VM realm before deep equality assertions.
const data = JSON.parse(JSON.stringify(sandbox.window.VLIW_DATA));

test("embedded inputs are the real machine descriptions and fixtures", () => {
  assert.deepEqual(data.machines["mvp-0"], json("config/machine.json"));
  assert.deepEqual(data.machines["mvp-1"], json("config/machine-mvp1.json"));
  for (const [name, replay] of Object.entries(data.replays)) {
    assert.deepEqual(replay.case, json(`tests/cases/${name}.json`));
    const golden = fs.readFileSync(path.join(root, replay.case.expect.trace), "utf8").trim().split("\n").map(JSON.parse);
    assert.deepEqual(replay.trace, golden);
  }
  for (const [name, fixture] of Object.entries(data.hazards)) {
    assert.deepEqual(fixture, json(`tests/cases/${name}.json`));
  }
});

test("a load stays undefined until its exact commit boundary; seeking is reversible", () => {
  const replay = data.replays.isa_example;
  assert.equal(model.snapshot(replay, 0).registers.r1, undefined);
  assert.equal(model.snapshot(replay, 1).registers.r1, undefined);
  assert.equal(model.snapshot(replay, 2).registers.r1, 7);
  assert.equal(model.snapshot(replay, 3).registers.r3, undefined);
  assert.equal(model.snapshot(replay, 4).registers.r3, 21);
  assert.equal(model.snapshot(replay, 5).memory[1], undefined);
  assert.equal(model.snapshot(replay, 6).memory[1], 28);
  assert.equal(model.snapshot(replay, 7).row.done, true);
  assert.equal(model.snapshot(replay, 0).memory[1], undefined);
  assert.throws(() => model.snapshot(replay, 8), RangeError);
});

test("both replays end at the existing expected values and h+1 cycles", () => {
  for (const replay of Object.values(data.replays)) {
    const cycles = replay.case.expect.cycles;
    const state = model.snapshot(replay, cycles);
    for (const [r, v] of Object.entries(replay.case.expect.registers)) assert.equal(state.registers[r], v);
    for (const [a, v] of Object.entries(replay.case.expect.memory ?? {})) assert.equal(state.memory[a], v);
    assert.equal(replay.trace.length, cycles + 1);
    assert.equal(state.row.issue, null);
    assert.equal(state.row.busy, false);
    const halt = model.operations(replay, data.machines["mvp-0"]).find(o => o.mnemonic === "HALT");
    assert.equal(halt.issue + 1, cycles);
  }
});

test("timeline endpoints agree with golden commits, including back-to-back MUL", () => {
  for (const replay of Object.values(data.replays)) {
    for (const op of model.operations(replay, data.machines["mvp-0"])) {
      const row = replay.trace[op.commit];
      if (op.mnemonic === "HALT") assert.equal(row.done, true);
      else if (op.mnemonic === "ST") assert.ok(row.mem_commit.length > 0);
      else {
        const destination = Number(op.asm.match(/r(\d+)/)[1]);
        assert.ok(row.rf_commit.some(w => w.reg === destination && w.port === op.slot));
      }
    }
  }
  const mul = model.operations(data.replays.consecutive_mul, data.machines["mvp-0"]);
  assert.deepEqual(mul.filter(o => o.mnemonic === "MUL").map(o => [o.issue, o.commit]), [[0, 2], [1, 3], [2, 4], [3, 5]]);
});

test("all hand-computed golden hex words decode to the golden trace", () => {
  const machine = data.machines["mvp-0"];
  data.goldenHex.forEach((hex, cycle) => {
    const d = model.decode(hex, machine);
    assert.deepEqual(d.errors, []);
    const issue = data.replays.isa_example.trace[cycle].issue;
    assert.equal(d.compute.toLowerCase(), issue.compute);
    assert.equal(d.memory.toLowerCase(), issue.memory);
  });
  assert.equal(model.decode("1100000000000000", machine).bytes, "00 00 00 00 00 00 00 11");
  assert.equal(model.decode("2400800000000000", machine).fields.memory.addr, 1);
});

test("MAC fields and ISA gating match the documented independent example", () => {
  const hex = "0000000045123000";
  const mac = model.decode(hex, data.machines["mvp-1"]);
  assert.equal(mac.compute, "MAC r5, r1, r2, r3");
  assert.deepEqual(mac.errors, []);
  assert.ok(model.decode(hex, data.machines["mvp-0"]).errors.some(e => e.includes("未知opcode")));
  assert.equal(mac.bytes, "00 30 12 45 00 00 00 00");
});

test("L1 errors and malformed inputs never silently appear legal", () => {
  const machine = data.machines["mvp-0"];
  for (const hex of ["0000000014310001", "0000000000000001", "11000000f0000000", "00000000f1000000", "0000000010011000", "2100800100000000"]) {
    assert.ok(model.decode(hex, machine).errors.length > 0, hex);
  }
  for (const text of ["1", "0x1100000000000000", "g100000000000000", "", "00000000000000000"]) {
    assert.throws(() => model.decode(text, machine));
  }
  const small = { ...machine, register_file: { ...machine.register_file, count: 3 } };
  assert.ok(model.decode("0000000033120000", small).errors.some(e => e.includes("範囲外")));
});
