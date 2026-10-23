import os
import re

p = "app/build/reports/lint-results-debug.xml"
out = []
if os.path.exists(p):
    t = open(p, encoding="utf8").read()
    for m in re.finditer(r'<issue\s+id="([^"]+)"\s+severity="Error".*?<location\s+file="([^"]+)"(?:\s+line="(\d+)")?', t, re.S):
        out.append("%s %s:%s" % (m.group(1), m.group(2).split("/main/")[-1], m.group(3)))
print("%0A".join(out[:10]))
