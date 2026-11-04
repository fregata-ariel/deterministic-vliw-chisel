"""Check provenance and numeric transcription without rerunning proposal experiments."""
import json
import re
from pathlib import Path

doc = Path(__file__).resolve().parents[1]
text = (doc / 'index.qmd').read_text()
source = json.loads((doc / 'source-manifest.json').read_text())
assert source['issue']['number'] == 78 and source['issue']['comment_count'] == 2
for comment in source['comments']:
    assert len(comment['body_sha256']) == 64
    assert comment['url'] in text
assert '{=html}' not in text
assert not re.search(r'<(?:script|style|svg|div|button|input)\b', text, re.I)
cells = re.findall(r'```\{mermaid\}\n([\s\S]*?)```', text)
assert len(cells) == 10
assert all('accTitle:' in cell and 'accDescr:' in cell for cell in cells)
assert text.count('.panel-tabset') == 2
charts = [cell for cell in cells if 'xychart-beta' in cell]
assert len(charts) == 3
order = ['M1', 'WR', 'WF', 'S2', 'P16']
for kernel, chart in enumerate(charts):
    actual = json.loads('[' + re.search(r'bar \[([^]]+)\]', chart)[1] + ']')
    assert actual == [source['list_cycles'][key][kernel] for key in order]
    assert 'x-axis [M1, WR, WF, S2, P16]' in chart
rows = [line.strip('|').split('|') for line in text.splitlines() if re.match(r'^\| (M1|WR|WF|S2|P16|WFa|WF4) \|', line)]
assert len(rows) == 7
for row in rows:
    key = row[0].strip()
    assert list(map(int, row[-3:])) == source['list_cycles'][key]
loop_rows = [line.strip('|').split('|') for line in text.splitlines() if re.match(r'^\| (saxpy8|fir4x8|matmul3)\([im]\)：', line)]
assert len(loop_rows) == 4
for row, evidence in zip(loop_rows, source['loop_mii'].values()):
    assert list(map(float, row[1:])) == [evidence[key] for key in ['M1', 'S2', 'WR', 'WF', 'P16']]
print('Issue #78: 10 native diagrams, 2 tabsets; charts and tables agree with the proposal snapshot.')
