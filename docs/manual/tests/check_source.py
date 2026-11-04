"""Check the portable Quarto source and the existing hand-written trace."""
import json
import re
from pathlib import Path

MANUAL = Path(__file__).resolve().parents[1]
ROOT = MANUAL.parent.parent
sources = list(MANUAL.glob('*.qmd'))
text = '\n'.join(p.read_text() for p in sources)
assert '{=html}' not in text
assert not re.search(r'<(?:script|style|svg|div|button|input)\b', text, re.I)
assert not list(MANUAL.glob('assets/*')), 'no custom browser assets'
assert text.count('```{mermaid}') == 14
assert text.count('.panel-tabset') == 4
assert 'pre-render' not in (MANUAL / '_quarto.yml').read_text()
case = json.loads((ROOT / 'tests/cases/isa_example.json').read_text())
trace = [json.loads(line) for line in (ROOT / case['expect']['trace']).read_text().splitlines()]
rows = []
for line in (MANUAL / '_replay.qmd').read_text().splitlines():
    if re.match(r'^\| [0-7] \|', line):
        rows.append([cell.strip() for cell in line.strip('|').split('|')])
assert len(rows) == len(trace) == 8
regs = dict(case['inputs']['registers'])
mem = dict(case['inputs']['memory'])
for row, event in zip(rows, trace):
    for w in event['rf_commit']:
        regs[f"r{w['reg']}"] = w['value']
    for w in event['mem_commit']:
        mem[str(w['addr'])] = w['value']
    expected = [event['cycle'], *[regs.get(r, '未定義') for r in ('r1', 'r2', 'r3', 'r4')], mem.get('1', '未定義'), int(event['busy']), int(event['done'])]
    assert row == list(map(str, expected)), (row, expected)
print('Quarto source: 14 Mermaid cells, 4 native tabsets, no custom HTML/JS; all golden boundaries agree.')
