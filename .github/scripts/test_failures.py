import glob
import re
import xml.etree.ElementTree as ET

out = []
for p in glob.glob("app/build/test-results/**/*.xml", recursive=True):
    try:
        root = ET.parse(p).getroot()
    except Exception:
        continue
    for case in root.iter("testcase"):
        for tag in ("failure", "error"):
            for f in case.findall(tag):
                text = (f.get("message") or "") + " | " + " ".join((f.text or "").split("\n")[:6])
                text = re.sub(r"\s+", " ", text)[:420]
                out.append("%s: %s" % (case.get("name"), text))
print("%0A".join(out[:6]).replace("%", "%25").replace("%250A", "%0A"))
