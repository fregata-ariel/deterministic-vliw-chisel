/* Offline, dependency-free controls for the Quarto manual. */
(function () {
  "use strict";
  const data = window.VLIW_DATA, model = window.VLIWManual;
  if (!data || !model) return; // Static diagrams and tables remain readable.
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const escape = s => String(s).replace(/[&<>"']/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const subscript = n => String(n).replace(/\d/g, c => "₀₁₂₃₄₅₆₇₈₉"[Number(c)]);
  const controllers = [];

  // A finite step-through: no automatic motion on load, no looping after the last step.
  function sequence(root, maximum, render) {
    let step = 0, timer = null;
    const play = $("[data-play]", root), prev = $("[data-prev]", root), next = $("[data-next]", root);
    const limit = () => typeof maximum === "function" ? maximum() : maximum;
    function stop() {
      clearInterval(timer); timer = null;
      root.classList.remove("is-playing");
      play.textContent = "再生"; play.setAttribute("aria-pressed", "false");
    }
    function paint() {
      render(step); prev.disabled = step === 0; next.disabled = step === limit();
    }
    function go(value) { stop(); step = Math.max(0, Math.min(limit(), value)); paint(); }
    play.addEventListener("click", () => {
      if (timer !== null) { stop(); return; }
      if (step === limit()) step = 0;
      root.classList.add("is-playing");
      play.textContent = "一時停止"; play.setAttribute("aria-pressed", "true");
      paint();
      timer = setInterval(() => {
        step += 1; paint();
        if (step >= limit()) stop();
      }, 1300);
    });
    prev.addEventListener("click", () => go(step - 1));
    next.addEventListener("click", () => go(step + 1));
    $("[data-reset]", root).addEventListener("click", () => go(0));
    play.setAttribute("aria-pressed", "false");
    $$(".js-only", root).forEach(el => { el.hidden = false; });
    controllers.push({ stop });
    if (typeof IntersectionObserver !== "undefined") {
      new IntersectionObserver(entries => {
        if (!entries[0].isIntersecting) stop();
      }).observe(root);
    }
    paint();
    return { go, stop };
  }

  const architecture = $("#architecture");
  const architectureDetails = {
    core: [
      ["ホストが準備する", "idle中にホストが命令メモリ・入力レジスタ・Scratchpadを書き込み、lengthを与える。startの受理境界がE₀になる。"],
      ["命令を取り出して分解する", "busy中、PCの指す64bit bundleを組合せ読出しのMemから取り出す。DecoderがComputeとMemoryの欄を分解する。分岐はなくPCは毎cycle進む。"],
      ["発行サイクルの値を読む", "RFは3本の組合せ読出しポートを持つ。mvp-1のポート2はMACのrs3とSTのソースで共有する。WriteReservationsは違反を検出し、ストール制御には使わない。"],
      ["2つのスロットへ発行する", "ComputeとMemoryは各1操作を同じcycleに発行できる。Computeは入力値から結果を計算して予約表へ、Memoryは選択したbankへアクセスする。"],
      ["固定境界で書き戻す", "ComputeとLDは別々のRF書戻しポートを使う。各ポートは1境界に1結果だけ。STはScratchpadを更新する。結果が見える境界は発行cycle + latency。"],
      ["HALTの次の境界でdone", "合法なHALTの発行時点で未完了操作はない。cycle hのHALTによりE_(h+1)でbusy=0、done=1。doneは1cycleだけ。内部のdrain待ちはない。"]
    ],
    fixed: [
      ["ホストがメモリを準備する", "FixedHostIOはidle中のScratchpadアクセスとstartを持つ。命令語をロードせず、静的なFixedPlanは回路生成時に与える。"],
      ["カウンタから定数制御を作る", "命令メモリとDecoderの代わりに、cycleが計画の発行時刻に一致するかを判定する。enable、select、即値addrは計画から固定される。"],
      ["値レジスタから選ぶ", "汎用RFの代わりにStoragePolicyで割り当てた値レジスタを置く。operand MUXは各入力が必要とするレジスタだけから選択する。"],
      ["Coreと同じスロットを使う", "ComputeSlotとMemorySlotを再利用する。現在のFixedPlanが表現する演算はADD/SUB/MUL/LD/ST。MACを使う計画には対応していない。"],
      ["同じ固定境界に保存する", "生産者の発行時刻 + latencyの境界で値レジスタまたはScratchpadに保存する。ByLifetimeでは同じポートの値を寿命に応じて共有する。"],
      ["最後のcommitの次にdone", "最後の書込みがE_Tに着地する。cycle TをCoreのHALTに相当するcycleとして確保し、E_(T+1)でdone。同じスケジュールならCoreと同じcycle数になる。"]
    ]
  };
  const architectureControl = sequence(architecture, 5, step => {
    const mode = $("#architecture-mode").value;
    $("#core-diagram").toggleAttribute("hidden", mode !== "core");
    $("#fixed-diagram").toggleAttribute("hidden", mode !== "fixed");
    const svg = $(`#${mode}-diagram`);
    $$("[data-node]", svg).forEach(el => el.classList.toggle("active", el.dataset.node.split(",").includes(String(step))));
    $$("[data-path]", svg).forEach(el => el.classList.toggle("active-flow", Number(el.dataset.path) === step));
    $("#architecture-step").textContent = `${step + 1} / 6 — ${architectureDetails[mode][step][0]}`;
    $("#architecture-detail").textContent = architectureDetails[mode][step][1];
  });
  $("#architecture-mode").addEventListener("change", () => architectureControl.go(0));

  const replayRoot = $("#replay"), machine = data.machines["mvp-0"];
  let replay = data.replays.isa_example;
  let ops = model.operations(replay, machine);
  const svgNS = "http://www.w3.org/2000/svg";
  function svgElement(tag, attrs, text) {
    const el = document.createElementNS(svgNS, tag);
    Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
    if (text !== undefined) el.textContent = text;
    return el;
  }
  function drawTimeline() {
    const svg = $("#replay-timeline"), max = replay.trace.length - 1;
    svg.replaceChildren();
    svg.setAttribute("viewBox", `0 0 820 ${90 + ops.length * 48}`);
    svg.append(svgElement("title", { id: "timeline-title" }, "発行からcommitまでの固定レイテンシ"));
    svg.append(svgElement("desc", { id: "timeline-desc" }, ops.map(o => `${o.asm}: cycle ${o.issue}で発行、E${o.commit}で${o.mnemonic === "HALT" ? "done" : "commit"}`).join("。")));
    const x = s => 190 + s * (590 / max);
    for (let s = 0; s <= max; s++) {
      svg.append(svgElement("line", { x1: x(s), x2: x(s), y1: 42, y2: 65 + ops.length * 48, stroke: "#ced9e3" }));
      svg.append(svgElement("text", { x: x(s), y: 26, "text-anchor": "middle" }, `E${subscript(s)}`));
    }
    ops.forEach((op, index) => {
      const y = 65 + index * 48;
      const color = op.slot === "memory" ? "#087d62" : op.mnemonic === "HALT" ? "#52667b" : "#086e92";
      svg.append(svgElement("text", { x: 6, y: y + 5 }, op.asm.toUpperCase()));
      svg.append(svgElement("line", { x1: x(op.issue), x2: x(op.commit), y1: y, y2: y, stroke: color, "stroke-width": 9, "stroke-linecap": "round", opacity: ".22" }));
      svg.append(svgElement("circle", { cx: x(op.issue), cy: y, r: 5, fill: "white", stroke: color, "stroke-width": 2 }));
      svg.append(svgElement("rect", { x: x(op.commit) - 4, y: y - 4, width: 8, height: 8, fill: color, transform: `rotate(45 ${x(op.commit)} ${y})` }));
      const token = svgElement("circle", { cx: 0, cy: y, r: 6, fill: color, "data-token": index });
      token.style.transition = "transform 450ms ease";
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) token.style.transition = "none";
      svg.append(token);
    });
    svg.append(svgElement("line", { x1: 0, x2: 0, y1: 36, y2: 65 + ops.length * 48, stroke: "#142b43", "stroke-width": 2, "stroke-dasharray": "3 4", "data-cursor": "" }));
    const cursor = $("[data-cursor]", svg);
    cursor.style.transition = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "none" : "transform 450ms ease";
    const tbody = $("#replay_table tbody");
    tbody.innerHTML = replay.trace.map(row => {
      const commits = [...row.rf_commit.map(w => `r${w.reg} = ${w.value} (${w.port})`), ...row.mem_commit.map(w => `mem[${w.addr}] = ${w.value}`), ...(row.done ? ["done = 1 / busy = 0"] : [])];
      return `<tr data-cycle="${row.cycle}"><th scope="row">${row.cycle}</th><td>${escape(row.issue?.compute.toUpperCase() ?? "—")}</td><td>${escape(row.issue?.memory.toUpperCase() ?? "—")}</td><td>${escape(commits.join(" / ") || "—")}</td></tr>`;
    }).join("");
  }
  function renderReplay(cycle) {
    const state = model.snapshot(replay, cycle), row = state.row;
    const max = replay.trace.length - 1;
    const x = s => 190 + s * (590 / max);
    $("#replay-cycle").max = max;
    $("#replay-cycle").value = cycle;
    $("#replay-cycle-value").textContent = `E${subscript(cycle)}`;
    $("#replay-summary").innerHTML = `<span>境界 <strong>E${subscript(cycle)}</strong></span><span>PC = ${row.pc ?? "—"}</span><span>busy = ${Number(row.busy)}</span><span>done = ${Number(row.done)}</span>`;
    const registerNames = [...new Set([...Object.keys(replay.case.inputs.registers), ...Object.keys(replay.case.expect.registers)])].sort((a, b) => Number(a.slice(1)) - Number(b.slice(1)));
    const addresses = [...new Set([...Object.keys(replay.case.inputs.memory), ...Object.keys(replay.case.expect.memory ?? {})])].sort((a, b) => Number(a) - Number(b));
    $("#replay-state").innerHTML = registerNames.map(name => `<div class="state-cell${row.rf_commit.some(w => `r${w.reg}` === name) ? " changed" : ""}">${escape(name)}<span class="value">${state.registers[name] ?? "未定義"}</span></div>`).join("") +
      addresses.map(addr => `<div class="state-cell memory${row.mem_commit.some(w => String(w.addr) === addr) ? " changed" : ""}">mem[${addr}]<span class="value">${state.memory[addr] ?? "未定義"}</span></div>`).join("");
    $$("#replay_table [data-cycle]").forEach(el => {
      el.classList.toggle("selected", Number(el.dataset.cycle) === cycle);
      if (Number(el.dataset.cycle) === cycle) el.setAttribute("aria-current", "step");
      else el.removeAttribute("aria-current");
    });
    $("[data-cursor]", $("#replay-timeline")).style.transform = `translateX(${x(cycle)}px)`;
    $$("[data-token]", $("#replay-timeline")).forEach((el, index) => {
      const op = ops[index];
      el.style.transform = `translateX(${x(Math.min(op.commit, Math.max(op.issue, cycle)))}px)`;
      el.style.opacity = cycle < op.issue ? "0" : "1";
    });
    const commits = [...row.rf_commit.map(w => `r${w.reg}=${w.value}`), ...row.mem_commit.map(w => `mem[${w.addr}]=${w.value}`)].join("、");
    const pending = ops.filter(o => o.mnemonic !== "HALT" && o.issue < cycle && cycle < o.commit).map(o => `${o.asm.toUpperCase()}（E${subscript(o.commit)}で完了）`).join("、");
    $("#replay-detail").textContent = row.done ? `E${subscript(cycle)}: done=1、busy=0。実行は${replay.case.expect.cycles} cycles。表示されるtrace行は開始行を含め${replay.trace.length}行。` :
      `cycle ${cycle}: ${commits ? `境界で${commits}を反映。` : "この境界での値の更新なし。"} 発行: ${row.issue.compute.toUpperCase()} | ${row.issue.memory.toUpperCase()}。${pending ? `未完了: ${pending}。` : ""}${row.issue.compute === "halt" ? "全操作が完了済みなのでHALTを発行できる。" : ""}`;
    $("#replay-source").textContent = `データ: tests/cases/${replay.case.name}.json / ${replay.source}。教材で使う値を表示。「未定義」は未初期化であり0ではない。図はgolden traceの再生。`;
  }
  drawTimeline();
  const replayControl = sequence(replayRoot, () => replay.trace.length - 1, renderReplay);
  $("#replay-cycle").addEventListener("input", e => replayControl.go(Number(e.target.value)));
  $("#replay-case").addEventListener("change", e => {
    replay = data.replays[e.target.value]; ops = model.operations(replay, machine);
    drawTimeline(); replayControl.go(0);
  });

  const decoderRoot = $("#decoder");
  function bitField(bits, name, value, kind = "") {
    const [high, low] = bits.split(":").map(Number);
    return `<div class="bit-field ${kind}" style="--bits:${high - low + 1}"><small>${bits}</small>${escape(name)}<strong>${escape(value)}</strong></div>`;
  }
  function renderDecode() {
    try {
      const result = model.decode($("#decoder-hex").value.trim(), data.machines[$("#decoder-isa").value]);
      const c = result.fields.compute, m = result.fields.memory;
      let computeFields = bitField("31:28", "opcode", `${c.opcode} / ${result.computeName ?? "?"}`);
      const isMac = result.computeName === "MAC", isEmpty = ["NOP", "HALT"].includes(result.computeName);
      if (isEmpty) {
        computeFields += bitField("27:0", "未使用", `0x${(Number(BigInt(`0x${$("#decoder-hex").value.trim()}`) & 0xfffffffn)).toString(16)}`, "reserved");
      } else {
        computeFields += ["rd", "rs1", "rs2"].map((name, index) => bitField(["27:24", "23:20", "19:16"][index], name, c[name])).join("");
        if (isMac) computeFields += bitField("15:12", "rs3", c.rs3);
        const mask = isMac ? 0xfffn : 0xffffn;
        computeFields += bitField(isMac ? "11:0" : "15:0", "reserved", `0x${(BigInt(`0x${$("#decoder-hex").value.trim()}`) & mask).toString(16)}`, "reserved");
      }
      $("#compute-fields").innerHTML = computeFields;
      $("#memory-fields").innerHTML = bitField("31:28", "opcode", m.opcode, "memory") + bitField("27:24", "LD: rd / ST: rs", m.reg, "memory") + bitField("23:15", "word addr", m.addr, "memory") +
        bitField("14:0", "reserved", `0x${((BigInt(`0x${$("#decoder-hex").value.trim()}`) >> 32n) & 0x7fffn).toString(16)}`, "reserved");
      $("#decoder-asm").textContent = `${result.compute} | ${result.memory}`;
      $("#decoder-bytes").textContent = `.bin: ${result.bytes}`;
      $("#decoder-status").textContent = result.errors.length ? `L1違反: ${result.errors.join("、")}。` : "単一bundleのL1フィールド検査: 違反なし。プログラム全体の合法性は別途verifierで検査する。";
      $("#decoder-status").classList.toggle("decode-error", result.errors.length > 0);
      $("#decoder-hex").setAttribute("aria-invalid", String(result.errors.length > 0));
    } catch (error) {
      $("#compute-fields").replaceChildren(); $("#memory-fields").replaceChildren();
      $("#decoder-asm").textContent = "入力を確認してください";
      $("#decoder-bytes").textContent = "";
      $("#decoder-status").textContent = error.message;
      $("#decoder-status").classList.add("decode-error");
      $("#decoder-hex").setAttribute("aria-invalid", "true");
    }
  }
  $("#decoder-example").addEventListener("change", e => { $("#decoder-hex").value = e.target.value; renderDecode(); });
  $("#decoder-hex").addEventListener("input", renderDecode);
  $("#decoder-isa").addEventListener("change", renderDecode);
  $$(".js-only", decoderRoot).forEach(el => { el.hidden = false; });
  renderDecode();

  const hazardRoot = $("#hazards");
  const hazardDetails = {
    raw_mul_next_cycle: [
      "MULはcycle 0に発行する。L=2なのでr3が確定するのはE₂。",
      "cycle 1のSTは書込み区間[0,2)の途中でr3を読む。r3の初期値があってもL3に違反する。",
      "STをcycle 2、HALTをcycle 3へ移す。E₂からr3を読み、STがE₃で完了した後にHALTを発行する。"
    ],
    mul_then_add_port: [
      "cycle 0のMULとcycle 1のADD。発行スロットは重ならず、読出し先にも依存はない。",
      "MULのL=2とADDのL=1により、どちらもE₂のCompute書戻しポートを要求する。宛先が違ってもL5に違反する。",
      "cycle 1をNOPにし、ADDをcycle 2へ移す。MULはE₂、ADDはE₃へ分散でき、cycle 3のHALTはそのままでよい。"
    ],
    early_halt_ld: [
      "cycle 0のLDはE₂でr1へ結果をcommitする。",
      "cycle 1のHALT発行時にはLDが未完了。done予定がE₂でもL6に違反する。HALTは自動drain待ちをしない。",
      "cycle 1をNOPにし、HALTをcycle 2へ移す。r1はE₂から見え、E₃でdoneになる。"
    ],
    mac_store_read_ports: [
      "mvp-1のMACはrs1、rs2、rs3を発行時に読む。STもソースを同じcycleに読む。",
      "MACの3読出し + STの1読出し = 4。既定RFの3本を超え、L11に違反する。同じレジスタを繰り返し読んでも欄ごとに数える。",
      "STをcycle 1へ分ける。この例ではSTのr5は独立しており、MACとSTの両方がE₂で完了する。cycle 2のHALTは合法。read_ports=4の設定は現行RTLの範囲外。"
    ]
  };
  function hazardGrid(name, step) {
    const repair = step === 2;
    let rows;
    if (name === "raw_mul_next_cycle") rows = [
      ["操作 / 時刻", "cycle 0", "cycle 1", "E₂ / cycle 2", "cycle 3"],
      ["MUL → r3", "○ 発行", "書込み中", "◆ commit", "—"],
      ["ST [0], r3", "—", repair ? "NOP" : "× r3を読む", repair ? "○ ここで発行" : "r3が見える", repair ? "HALT" : "—"]
    ];
    else if (name === "mul_then_add_port") rows = [
      ["操作 / 境界", "cycle 0", "cycle 1", "E₂", "E₃"],
      ["MUL", "○ 発行", "—", "◆ Compute WB", "—"],
      ["ADD", "—", repair ? "NOP" : "○ 発行", repair ? "cycle 2に発行" : "× Compute WB", repair ? "◆ Compute WB" : "—"]
    ];
    else if (name === "early_halt_ld") rows = [
      ["操作 / 時刻", "cycle 0", "cycle 1", "E₂ / cycle 2", "E₃"],
      ["LD", "○ 発行", "未完了", "◆ commit", "—"],
      ["HALT", "—", repair ? "NOP" : "× 早いHALT", repair ? "○ 発行" : "done予定", repair ? "◆ done" : "—"]
    ];
    else rows = [
      ["RF read port", "0", "1", "2", "不足分"],
      ["MAC（cycle 0）", "rs1 = r1", "rs2 = r2", "rs3 = r3", "—"],
      [repair ? "ST（cycle 1）" : "ST（cycle 0）", "—", "—", repair ? "rs = r5" : "× rs = r5", repair ? "別cycleに分離" : "4 > 3"]
    ];
    return `<div class="hazard-grid">${rows.map((row, r) => row.map((cell, c) => `<div class="${r === 0 ? "head" : cell.includes("×") && step > 0 ? "conflict" : cell.includes("◆") ? "reserved" : ""}">${escape(step === 0 && cell.includes("×") ? "要求を確認 →" : cell)}</div>`).join("")).join("")}</div>`;
  }
  const hazardControl = sequence(hazardRoot, 2, step => {
    const name = $("#hazard-case").value;
    const example = data.hazards[name];
    $("#hazard-step").textContent = `${step + 1} / 3 — ${["発行を見る", "衝突を確認する", "配置を修正する"][step]}`;
    $("#hazard-view").innerHTML = hazardGrid(name, step);
    $("#hazard-detail").textContent = hazardDetails[name][step];
    $("#hazard-program").textContent = example.program.join("\n");
    $("#hazard-source").textContent = `元ケース: tests/cases/${name}.json / 期待する違反: ${example.expect.verdict.illegal.join(", ")}。コード欄は元の不正プログラム。図の3段目と本文で修正案を示す。`;
  });
  $("#hazard-case").addEventListener("change", () => hazardControl.go(0));

  const compilerDetails = [
    "配列・スカラーをScratchpadへ配置する。Loweringが式を即値アドレスを使う形に変換する。",
    "Dagが値と依存辺を作る。mvp-1ではFusionが条件を満たすMULとADDをMACにまとめる。値はまだ物理レジスタではない。",
    "ListSchedulerが依存距離、スロット、演算器のII、将来の書戻しポート、RF読出し本数を満たす発行cycleを選ぶ。依存待ちはNOPとして命令列に現れる。",
    "RegisterAllocatorが値の寿命を使って物理レジスタを割り当てる。足りなければ再配置やLDのrematerializationを試す。一般的なspillは行わない。",
    "KernelCompiler.verifiedがBundleCodecで符号化した最終命令列を独立Verifierへ渡す。不正ならエラーにし、命令語を返さない。",
    "ホストが命令と入力をロードして実行する。ReferenceModelとChisel RTLのtraceをcrosscheckがサイクル単位で照合する。コンパイル経路とは別の検証段階。"
  ];
  sequence($("#compiler-flow"), 5, step => {
    $$("#compiler-flow [data-phase]").forEach(el => {
      el.classList.toggle("current", Number(el.dataset.phase) === step);
      el.classList.toggle("done", Number(el.dataset.phase) < step);
    });
    $("#compiler-detail").textContent = `${step + 1} / 6 — ${compilerDetails[step]}`;
  });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) controllers.forEach(c => c.stop());
  });
  window.addEventListener("pagehide", () => controllers.forEach(c => c.stop()));
})();
