#!/usr/bin/env bash
set -euo pipefail

mkdir -p Android/ui-smoke
adb install Android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.personal.doselog.preview2/com.personal.doselog.MainActivity
sleep 3
adb shell pidof com.personal.doselog.preview2

# The emulator contains only a fictitious fixture; it never touches personal data.
adb shell am force-stop com.personal.doselog.preview2
adb shell run-as com.personal.doselog.preview2 sh -c 'cat > files/medicine-book.json' < Android/test-fixtures/preview.json
adb shell am start -n com.personal.doselog.preview2/com.personal.doselog.MainActivity
sleep 3
adb exec-out screencap -p > Android/ui-smoke/today-before.png
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb pull /sdcard/window.xml Android/ui-smoke/before.xml >/dev/null

tap=$(python3 - <<'PY'
import re, xml.etree.ElementTree as ET
root = ET.parse('Android/ui-smoke/before.xml').getroot()
nodes = [n for n in root.iter('node') if n.attrib.get('text', '').startswith('已服 1片')]
assert nodes, 'One-tap record button not visible in the actual app'
bounds = nodes[0].attrib['bounds']
x1,y1,x2,y2 = map(int, re.findall(r'\d+', bounds))
print((x1+x2)//2, (y1+y2)//2)
PY
)
adb shell input tap $tap
sleep 2
adb exec-out screencap -p > Android/ui-smoke/today-after.png
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb pull /sdcard/window.xml Android/ui-smoke/after.xml >/dev/null
adb shell run-as com.personal.doselog.preview2 cat files/medicine-book.json > Android/ui-smoke/saved.json
python3 - <<'PY'
import json, xml.etree.ElementTree as ET
data = json.load(open('Android/ui-smoke/saved.json', encoding='utf-8'))
assert len(data['doses']) == 1, 'Tap did not create exactly one actual dose record'
dose = data['doses'][0]
assert dose['medicineId'] == 'preview-medicine' and dose['planId'] == 'preview-plan'
assert dose['amount'] == '1' and dose['scheduledAt'] is not None and not dose['voided']
text = ' '.join(n.attrib.get('text', '') for n in ET.parse('Android/ui-smoke/after.xml').iter('node'))
assert '已记录' in text and '演示药品' in text, 'UI did not show the saved result'
print('PASS: launch, planned dose card, one tap, persisted medicine/dose/time/plan, updated UI')
PY
