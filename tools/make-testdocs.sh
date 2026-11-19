#!/bin/bash
# Generates test documents for EasyDocs into the directory given as $1.
# Needs: python3 + reportlab, node, libreoffice (soffice).
set -e
OUT=${1:?usage: make-testdocs.sh <output-dir>}
SJS="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/excel/xlsx.full.min.js"
mkdir -p "$OUT" && cd "$OUT"
python3 - <<'PY'
from reportlab.lib.pagesizes import A4, landscape
from reportlab.pdfgen import canvas
from reportlab.lib.pdfencrypt import StandardEncryption
c = canvas.Canvas("sample.pdf", pagesize=A4)
for p in range(1, 6):
    size = landscape(A4) if p == 3 else A4
    c.setPageSize(size)
    c.setFont("Helvetica-Bold", 28); c.drawString(60, size[1]-90, f"EasyDocs sample - page {p}")
    c.setFont("Helvetica", 9)
    for i in range(60):
        c.drawString(60, size[1]-130-i*11, f"Line {i+1}: The quick brown fox jumps over the lazy dog. Small text to test zoom 0123456789.")
    c.setFillColorRGB(0.9, 0.3, 0.2); c.rect(60, 40, 120, 40, fill=1); c.setFillColorRGB(0, 0, 0)
    c.showPage()
c.save()
c = canvas.Canvas("large-1000-pages.pdf", pagesize=A4)
for p in range(1, 1001):
    c.setFont("Helvetica-Bold", 40); c.drawString(60, 760, f"Page {p} of 1000")
    c.setFont("Helvetica", 10)
    for i in range(50): c.drawString(60, 720-i*13, f"{p}.{i} Lorem ipsum dolor sit amet, consectetur adipiscing elit.")
    c.showPage()
c.save()
c = canvas.Canvas("protected.pdf", encrypt=StandardEncryption("secret", canPrint=0)); c.drawString(100, 700, "secret"); c.save()
PY
head -c 3000 /dev/urandom > broken.pdf
cat > styled.html <<'HT'
<html><body><table border="1">
<tr><td colspan="4" bgcolor="#1E5BD8"><font color="#FFFFFF"><b>Quarterly Sales Report (merged title)</b></font></td></tr>
<tr><td bgcolor="#FFF2CC"><b>Region</b></td><td bgcolor="#FFF2CC"><b>Q1</b></td><td bgcolor="#FFF2CC"><b>Q2</b></td><td bgcolor="#FFF2CC"><b>Date</b></td></tr>
<tr><td>North</td><td>1250.5</td><td>1400</td><td>2026-01-15</td></tr>
<tr><td>South</td><td>980</td><td>1105.25</td><td>2026-02-20</td></tr>
<tr><td bgcolor="#E2EFDA">East — Ünïcödé ✓ తెలుగు हिन्दी</td><td>1500</td><td>1620</td><td>2026-03-05</td></tr>
<tr><td>A very long text that should spill into the empty neighbouring cells like Excel does</td></tr>
</table></body></html>
HT
soffice --headless --infilter="HTML (StarCalc)" --convert-to xlsx:"Calc MS Excel 2007 XML" styled.html >/dev/null 2>&1
soffice --headless --infilter="HTML (StarCalc)" --convert-to xls:"MS Excel 97" styled.html >/dev/null 2>&1
rm -f styled.html
SJS="$SJS" node -e '
const XLSX=require(process.env.SJS), fs=require("fs");
const rows=[["ID","Name","Amount","Date","Status","City","Qty","Price","Total","Note"]];
for(let i=1;i<=200000;i++) rows.push([i,"Customer "+i,Math.round(Math.random()*1e6)/100,new Date(2026,0,1+(i%365)),i%3?"Paid":"Due","City "+(i%97),i%50,(i%999)/10,(i%50)*(i%999)/10,"row "+i]);
const wb=XLSX.utils.book_new();
XLSX.utils.book_append_sheet(wb,XLSX.utils.aoa_to_sheet(rows,{cellDates:true}),"Big data");
XLSX.utils.book_append_sheet(wb,XLSX.utils.aoa_to_sheet([["Second sheet"],[1,2,3]]),"Summary");
fs.writeFileSync("large-200k-rows.xlsx",XLSX.write(wb,{type:"buffer",bookType:"xlsx",compression:true}));
const wb2=XLSX.utils.book_new();
XLSX.utils.book_append_sheet(wb2,XLSX.utils.aoa_to_sheet(rows.slice(0,5001),{cellDates:true}),"Legacy");
fs.writeFileSync("legacy-5k.xls",XLSX.write(wb2,{type:"buffer",bookType:"biff8"}));'
ls -la
