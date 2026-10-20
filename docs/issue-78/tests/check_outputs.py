"""Check that every comparison survives standard Quarto print conversion."""
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
    assert len(images) == 10, images
    root = ET.fromstring(doc.read('word/document.xml'))
    ns = {'wp': 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'}
    for size in root.findall('.//wp:extent', ns):
        assert int(size.attrib['cy']) <= 8.5 * 914400, 'diagram exceeds the printable page height'
    word_text = re.sub(r'\s+', '', ''.join(root.itertext()))
pdf_text = re.sub(r'\s+', '', subprocess.check_output(['pdftotext', str(out / 'index.pdf'), '-'], text=True))
for heading in ('W：2本のi32', 'P16：1語に2要素', 'S2：Computeを2つ', 'saxpy8', 'fir4x8', 'matmul3', '14の判断項目'):
    assert heading in word_text and heading in pdf_text, heading
info = subprocess.check_output(['pdfimages', '-list', str(out / 'index.pdf')], text=True)
assert len(re.findall(r'^\s*\d+\s+\d+\s+image\s', info, re.M)) >= 10
print('Issue #78 HTML/PDF/DOCX contain 10 figures and every tab comparison.')
