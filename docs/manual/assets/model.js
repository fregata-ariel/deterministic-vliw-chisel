/* Pure presentation helpers. Trace replay, not an ISA emulator or verifier. */
(function (root, factory) {
  const model = factory();
  if (typeof module === "object" && module.exports) module.exports = model;
  else root.VLIWManual = model;
})(typeof globalThis === "undefined" ? this : globalThis, function () {
  "use strict";
  function snapshot(replay, cycle) {
    if (!Number.isInteger(cycle) || cycle < 0 || cycle >= replay.trace.length) {
      throw new RangeError("cycle outside trace");
    }
    const registers = { ...replay.case.inputs.registers };
    const memory = { ...replay.case.inputs.memory };
    for (const row of replay.trace.slice(0, cycle + 1)) {
      row.rf_commit.forEach(w => { registers[`r${w.reg}`] = w.value; });
      row.mem_commit.forEach(w => { memory[w.addr] = w.value; });
    }
    return { registers, memory, row: replay.trace[cycle] };
  }

  // Field extraction uses BigInt: a 64-bit bundle must not pass through Number.
  function decode(hex, machine) {
    if (!/^[0-9a-fA-F]{16}$/.test(hex)) throw new Error("16桁の16進数が必要です");
    const word = BigInt(`0x${hex}`);
    const c = Number(word & 0xffffffffn);
    const m = Number(word >> 32n);
    const field = (v, shift, mask) => (v >>> shift) & mask;
    const fields = {
      compute: { opcode: field(c, 28, 15), rd: field(c, 24, 15),
        rs1: field(c, 20, 15), rs2: field(c, 16, 15), rs3: field(c, 12, 15) },
      memory: { opcode: field(m, 28, 15), reg: field(m, 24, 15), addr: field(m, 15, 511) }
    };
    const nameOf = (slot, opcode) => opcode === machine.nop_opcode ? "NOP" :
      Object.keys(machine.operations).find(n => machine.operations[n].slot === slot &&
        machine.operations[n].opcode === opcode);
    const cn = nameOf("compute", fields.compute.opcode);
    const mn = nameOf("memory", fields.memory.opcode);
    const errors = [];
    if (!cn) errors.push("Compute: 未知opcode");
    if (!mn) errors.push("Memory: 未知opcode");
    if (cn === "NOP" || cn === "HALT") {
      if ((c & 0x0fffffff) !== 0) errors.push("Compute: NOP/HALTの未使用bitが非ゼロ");
    } else if (cn) {
      if ((c & (cn === "MAC" ? 0xfff : 0xffff)) !== 0) errors.push("Compute: 予約bitが非ゼロ");
      ["rd", "rs1", "rs2", ...(cn === "MAC" ? ["rs3"] : [])].forEach(f => {
        if (fields.compute[f] >= machine.register_file.count) errors.push(`Compute: ${f}が範囲外`);
      });
    }
    if (mn === "NOP" ? (m & 0x0fffffff) !== 0 : (m & 0x7fff) !== 0) {
      errors.push("Memory: 未使用・予約bitが非ゼロ");
    }
    if (mn && mn !== "NOP" && fields.memory.reg >= machine.register_file.count) {
      errors.push("Memory: レジスタが範囲外");
    }
    if (cn === "HALT" && m !== 0) errors.push("HALTのMemory wordはNOPが必要");
    const f = fields.compute, g = fields.memory;
    const compute = !cn ? "?" : (cn === "NOP" || cn === "HALT") ? cn :
      `${cn} r${f.rd}, r${f.rs1}, r${f.rs2}${cn === "MAC" ? `, r${f.rs3}` : ""}`;
    const memory = !mn ? "?" : mn === "NOP" ? "NOP" : mn === "LD" ?
      `LD r${g.reg}, [${g.addr}]` : `ST [${g.addr}], r${g.reg}`;
    const bytes = Array.from({ length: 8 }, (_, i) =>
      Number((word >> BigInt(8 * i)) & 255n).toString(16).padStart(2, "0")).join(" ");
    return { fields, compute, memory, errors, bytes, computeName: cn };
  }

  function operations(replay, machine) {
    return replay.trace.flatMap(row => !row.issue ? [] :
      ["compute", "memory"].flatMap(slot => {
        const asm = row.issue[slot];
        const mnemonic = asm.split(" ")[0].toUpperCase();
        if (mnemonic === "NOP") return [];
        const op = machine.operations[mnemonic];
        const latency = mnemonic === "HALT" ? op.done_after_cycles : op.latency;
        return [{ asm, mnemonic, slot, issue: row.cycle, commit: row.cycle + latency }];
      }));
  }
  return { snapshot, decode, operations };
});
