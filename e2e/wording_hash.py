"""The hash of the alert wording, as EmergencyContacts.wording(...).hashCode() works it out on an
English phone, so the test can write an approval the app accepts."""
import re
import sys
import xml.etree.ElementTree as ET

NAMES = ["alert_text_sos", "alert_text_no_location", "alert_text_update", "alert_text_false", "alert_text_live"]


def android_string(raw):
    # Unquoted resource text: runs of whitespace become one space, then the escapes apply.
    raw = re.sub(r"\s+", " ", raw).strip()
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            n = raw[i + 1]
            if n == "u":
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "t": "\t"}.get(n, n))
            i += 2
            continue
        if c != '"':
            out.append(c)
        i += 1
    return "".join(out)


def java_hash(text):
    h = 0
    data = text.encode("utf-16-be")
    for k in range(0, len(data), 2):
        h = (31 * h + int.from_bytes(data[k:k + 2], "big")) & 0xFFFFFFFF
    return h - (1 << 32) if h >= 1 << 31 else h


root = ET.parse(sys.argv[1]).getroot()
strings = {e.get("name"): "".join(e.itertext()) for e in root.iter("string")}
print(java_hash("|".join(android_string(strings[n]) for n in NAMES)))
