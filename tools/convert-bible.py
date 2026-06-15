#!/usr/bin/env python3
"""
Download 6 Bible translations from seven1m/open-bibles (XML) and produce
compact JSON for bundling as Android app assets.

Output: app/src/main/assets/bible/{code}.json  (one per translation)

Output JSON shape:
{
  "GEN": {
    "1": [{"verse": 1, "text": "..."}, ...],
    "2": [...]
  },
  ...
}
"""

import json, os, sys, urllib.request, xml.etree.ElementTree as ET
from pathlib import Path

BASE_URL = "https://raw.githubusercontent.com/seven1m/open-bibles/master/"
OUTPUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/bible"

TRANS = {
    "asv":    ("eng-asv.zefania.xml",  "zefania"),
    "bbe":    ("eng-bbe.usfx.xml",     "usfx"),
    "kjv":    ("eng-kjv.osis.xml",     "osis"),
    "web":    ("eng-web.usfx.xml",     "usfx"),
    "ylt":    ("eng-ylt.zefania.xml",  "zefania"),
    "oeb-us": ("eng-us-oeb.osis.xml",  "osis"),
}

# Every known book-name alias -> canonical 3-letter code
BOOK = {
    # Standard 3-letter USFX/SWORD codes (also cover Zefania bsname)
    "gen":"GEN","exo":"EXO","lev":"LEV","num":"NUM","deu":"DEU",
    "jos":"JOS","jdg":"JDG","rut":"RUT",
    "1sa":"1SA","2sa":"2SA","1ki":"1KI","2ki":"2KI",
    "1ch":"1CH","2ch":"2CH","ezr":"EZR","neh":"NEH","est":"EST",
    "job":"JOB","psa":"PSA","pro":"PRO","ecc":"ECC","sng":"SNG",
    "isa":"ISA","jer":"JER","lam":"LAM","ezk":"EZK","dan":"DAN",
    "hos":"HOS","jol":"JOL","amo":"AMO","oba":"OBA","jon":"JON",
    "mic":"MIC","nam":"NAM","hab":"HAB","zep":"ZEP","hag":"HAG",
    "zec":"ZEC","mal":"MAL",
    "mat":"MAT","mrk":"MRK","luk":"LUK","jhn":"JHN","act":"ACT",
    "rom":"ROM","1co":"1CO","2co":"2CO","gal":"GAL","eph":"EPH",
    "php":"PHP","col":"COL","1th":"1TH","2th":"2TH","1ti":"1TI",
    "2ti":"2TI","tit":"TIT","phm":"PHM","heb":"HEB","jas":"JAS",
    "1pe":"1PE","2pe":"2PE","1jn":"1JN","2jn":"2JN","3jn":"3JN",
    "jud":"JUD","rev":"REV",
    # OSIS book IDs
    "exod":"EXO","deut":"DEU","josh":"JOS","judg":"JDG",
    "1sam":"1SA","2sam":"2SA","1kgs":"1KI","2kgs":"2KI",
    "1chr":"1CH","2chr":"2CH","ezra":"EZR","esth":"EST",
    "ps":"PSA","prov":"PRO","eccl":"ECC","song":"SNG",
    "ezek":"EZK","hos":"HOS","joel":"JOL","obad":"OBA","jonah":"JON",
    "mic":"MIC","nah":"NAM","zeph":"ZEP","zech":"ZEC",
    "matt":"MAT","mark":"MRK",
    "1cor":"1CO","2cor":"2CO","phil":"PHP","1thess":"1TH","2thess":"2TH",
    "1tim":"1TI","2tim":"2TI","titus":"TIT","phlm":"PHM",
    "1pet":"1PE","2pet":"2PE","1john":"1JN","2john":"2JN","3john":"3JN",
    "jude":"JUD","philem":"PHM",
    # Full English names
    "genesis":"GEN","exodus":"EXO","leviticus":"LEV","numbers":"NUM",
    "deuteronomy":"DEU","joshua":"JOS","judges":"JDG","ruth":"RUT",
    "1 samuel":"1SA","2 samuel":"2SA","1 kings":"1KI","2 kings":"2KI",
    "1 chronicles":"1CH","2 chronicles":"2CH","ezra":"EZR","nehemiah":"NEH",
    "esther":"EST","job":"JOB","psalms":"PSA","psalm":"PSA","proverbs":"PRO",
    "ecclesiastes":"ECC","song of solomon":"SNG","song of songs":"SNG",
    "isaiah":"ISA","jeremiah":"JER","lamentations":"LAM","ezekiel":"EZK",
    "daniel":"DAN","hosea":"HOS","joel":"JOL","amos":"AMO","obadiah":"OBA",
    "jonah":"JON","micah":"MIC","nahum":"NAM","habakkuk":"HAB",
    "zephaniah":"ZEP","haggai":"HAG","zechariah":"ZEC","malachi":"MAL",
    "matthew":"MAT","mark":"MRK","luke":"LUK","john":"JHN","acts":"ACT",
    "romans":"ROM","1 corinthians":"1CO","2 corinthians":"2CO",
    "galatians":"GAL","ephesians":"EPH","philippians":"PHP","colossians":"COL",
    "1 thessalonians":"1TH","2 thessalonians":"2TH","1 timothy":"1TI",
    "2 timothy":"2TI","titus":"TIT","philemon":"PHM","hebrews":"HEB",
    "james":"JAS","1 peter":"1PE","2 peter":"2PE","1 john":"1JN",
    "2 john":"2JN","3 john":"3JN","jude":"JUD",
    "revelation":"REV","revelation of jesus christ":"REV",
}

CANONICAL = set(BOOK.values())

APOCRYPHA = {
    "tob","jdt","est hgr","wis","sir","bar","epjer","prazar","sus","bel",
    "1macc","2macc","1esd","prman","2esd","3macc","4macc",
    "1enoch","jub","testxii","ps151",
    "esthgr","addesth","lje","s3y","1ma","2ma","1es","man","ps2","3ma","2es","4ma",
    "esg","glo","frt",
}

def norm_book(name):
    """Map any known book-name variant to standard 3-letter code.  Returns None for non-canonical books."""
    key = name.strip().lower()
    if key in APOCRYPHA:
        return None
    if key in BOOK:
        return BOOK[key]
    # Try first 3 letters
    if len(key) >= 3 and key[:3] in BOOK and key[:3] not in APOCRYPHA:
        return BOOK[key[:3]]
    return None

OSIS_NS = "http://www.bibletechnologies.net/2003/OSIS/namespace"

# ---- Zefania ----
def parse_zefania(root):
    bible = {}
    for book in root.findall("BIBLEBOOK"):
        bid = norm_book(book.get("bname", ""))
        if not bid:
            continue
        chs = {}
        for ch in book.findall("CHAPTER"):
            cn = ch.get("cnumber", "")
            vs = []
            for v in ch.findall("VERS"):
                t = (v.text or "").strip()
                if t:
                    vs.append({"verse": int(v.get("vnumber", "0")), "text": t})
            if vs:
                chs[cn] = vs
        if chs:
            bible[bid] = chs
    return bible

# ---- USFX ----
def parse_usfx(root):
    bible = {}
    for book in root.findall("book"):
        bid = norm_book(book.get("id", ""))
        if not bid:
            continue
        chs = {}
        cur_ch = None
        cur_vs = []
        in_verse = False
        vnum = None
        parts = []
        for el in book.iter():
            if el.tag == "c":
                if cur_ch is not None and cur_vs:
                    chs[str(cur_ch)] = list(cur_vs)
                cur_ch = int(el.get("id", "0"))
                cur_vs = []
                in_verse = False
                vnum = None
                parts = []
            elif el.tag == "v":
                if in_verse and vnum is not None:
                    text = " ".join("".join(parts).split())
                    if text:
                        cur_vs.append({"verse": vnum, "text": text})
                in_verse = True
                vnum = int(el.get("id", "0"))
                parts = []
                if el.tail:
                    parts.append(el.tail)
            elif el.tag == "ve":
                if in_verse and vnum is not None:
                    text = " ".join("".join(parts).split())
                    if text:
                        cur_vs.append({"verse": vnum, "text": text})
                in_verse = False
                vnum = None
                parts = []
            elif in_verse:
                if el.text:
                    parts.append(el.text)
                if el.tail:
                    parts.append(el.tail)
        if cur_ch is not None and cur_vs:
            chs[str(cur_ch)] = list(cur_vs)
        if chs:
            bible[bid] = chs
    return bible

# ---- OSIS ----
def parse_osis(root):
    bible = {}
    # Find all book divs
    for book_div in root.findall(f".//{{{OSIS_NS}}}div[@type='book']"):
        bid = norm_book(book_div.get("osisID", ""))
        if not bid:
            continue
        chs = {}
        cur_ch = None
        cur_vs = []
        in_verse = False
        vnum = None
        parts = []
        for el in book_div.iter():
            tag = el.tag
            if isinstance(tag, str) and tag.startswith("{"):
                local = tag.split("}", 1)[1]
                if local == "chapter":
                    if "sID" in el.attrib:
                        if cur_ch is not None and cur_vs:
                            chs[str(cur_ch)] = list(cur_vs)
                        cur_ch = int(el.get("n"))
                        cur_vs = []
                        in_verse = False
                        vnum = None
                        parts = []
                    # skip end markers (eID only)
                elif local == "verse":
                    if in_verse and vnum is not None and parts:
                        text = " ".join("".join(parts).split())
                        if text:
                            cur_vs.append({"verse": vnum, "text": text})
                    if "sID" in el.attrib and "n" in el.attrib:
                        in_verse = True
                        vnum = int(el.get("n", "0"))
                        parts = []
                        if el.tail:
                            parts.append(el.tail)
                    elif "eID" in el.attrib:
                        if in_verse and vnum is not None and parts:
                            text = " ".join("".join(parts).split())
                            if text:
                                cur_vs.append({"verse": vnum, "text": text})
                        in_verse = False
                        vnum = None
                        parts = []
                elif in_verse:
                    if el.text:
                        parts.append(el.text)
                    if el.tail:
                        parts.append(el.tail)
            elif in_verse:
                if el.text:
                    parts.append(el.text)
                if el.tail:
                    parts.append(el.tail)
        if cur_ch is not None and cur_vs:
            chs[str(cur_ch)] = list(cur_vs)
        if chs:
            bible[bid] = chs
    return bible

def _patch_bbe_xml(text: bytes) -> bytes:
    """BBE USFX is missing ALL closing tags.  Insert </book> between books."""
    s = text.decode("utf-8")
    if "</book>" in s or "</usfx>" in s:
        return text
    # Replace each <book ...> except the first with </book><book ...>
    # This properly siblings them instead of nesting
    import re
    s = re.sub(r"<book ", "</book>\n<book ", s, count=0)  # replace all
    # First is wrong — remove the leading </book>
    s = s.replace("</book>\n<book ", "<book ", 1)
    s += "\n</book>\n</usfx>\n"
    return s.encode("utf-8")

def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for code, (fname, fmt) in TRANS.items():
        url = BASE_URL + fname
        print(f"[{code}] {url}")
        try:
            raw = urllib.request.urlopen(url, timeout=30).read()
        except Exception as e:
            print(f"  FAIL download: {e}")
            continue
        # Fix truncated XML files (BBE is missing all closing tags)
        if fmt == "usfx":
            raw = _patch_bbe_xml(raw)
        try:
            root = ET.fromstring(raw)
            if fmt == "zefania":
                data = parse_zefania(root)
            elif fmt == "usfx":
                data = parse_usfx(root)
            elif fmt == "osis":
                data = parse_osis(root)
            else:
                raise ValueError(f"unknown format {fmt}")
        except Exception as e:
            print(f"  FAIL parse: {e}")
            import traceback; traceback.print_exc()
            continue
        path = OUTPUT / f"{code}.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        bc = len(data)
        cc = sum(len(v) for v in data.values())
        sz = os.path.getsize(path)
        print(f"  OK  {bc} books, {cc} chapters, {sz:,} B -> {path}")

if __name__ == "__main__":
    main()
