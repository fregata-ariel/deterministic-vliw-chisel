"""Verify that standard print outputs retain every diagram and tab stage."""
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

out = Path(__file__).resolve().parents[1] / '_output'
for ext in ('html', 'pdf', 'docx'):
    assert (out / f'index.{ext}').stat().st_size > 10000
with ZipFile(out / 'index.docx') as doc:
    images = [n for n in doc.namelist() if n.startswith('word/media/')]
    assert len(images) == 14, images
    xml = ET.fromstring(doc.read('word/document.xml'))
    # Leave room for captions on a portrait page; width alone cannot prevent a tall plot clipping.
    ns = {'wp': 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'}
    for size in xml.findall('.//wp:extent', ns):
        assert int(size.attrib['cy']) <= 8.5 * 914400, 'diagram exceeds the printable page height'
    text = ''.join(xml.itertext())
    for heading in ('E0 発行', 'E2 乗算', 'E4 加算', 'E5 保存', 'E7 終了'):
        assert heading in text, heading
pdf_text = subprocess.check_output(['pdftotext', str(out / 'index.pdf'), '-'], text=True)
compact = re.sub(r'\s+', '', pdf_text)
for heading in ('E0発行', 'E2乗算', 'E4加算', 'E5保存', 'E7終了'):
    assert heading in compact, heading
info = subprocess.check_output(['pdfimages', '-list', str(out / 'index.pdf')], text=True)
images = [line for line in info.splitlines() if re.match(r'\s*\d+\s+\d+\s+image\s', line)]
assert len(images) >= 14, info
print('HTML/PDF/DOCX created; PDF and DOCX contain all 14 diagrams and all execution-stage headings.')
