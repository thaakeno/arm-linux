#!/usr/bin/env python3
import base64
import datetime
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

POPULAR = [
    "firefox-esr", "vlc", "libreoffice-writer", "gimp", "inkscape", "kate",
    "okular", "dolphin", "konsole", "gwenview", "ark", "kcalc", "audacity",
    "filezilla", "transmission-qt", "keepassxc", "mpv", "qbittorrent",
    "thunderbird", "krita",
]
RANK = {pkg: i for i, pkg in enumerate(POPULAR)}
ENV = dict(os.environ, LC_ALL="C", LANG="C")


def run(args, timeout=20):
    try:
        p = subprocess.run(args, env=ENV, text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.DEVNULL, timeout=timeout, check=False)
        return p.stdout
    except Exception:
        return ""


def parse_search(text):
    out = []
    for block in re.split(r"\n\s*---\s*\n", text.strip()):
        item = {}
        for line in block.splitlines():
            if ":" not in line:
                continue
            k, v = line.split(":", 1)
            item[k.strip()] = v.strip()
        ident = item.get("Identifier", "")
        if "[desktop-application]" not in ident:
            continue
        item["Identifier"] = ident.split(" [", 1)[0].strip()
        pkg = item.get("Package", "").split(",", 1)[0].strip()
        if not pkg:
            continue
        item["Package"] = pkg
        out.append(item)
    return out


def dump_meta(component_id):
    xml = run(["appstreamcli", "dump", component_id], 12)
    start = xml.find("<component")
    if start < 0:
        return [], 0, ""
    try:
        root = ET.fromstring(xml[start:])
    except Exception:
        return [], 0, ""
    cats = [x.text.strip() for x in root.findall(".//categories/category") if x.text and x.text.strip()]
    newest = 0
    for rel in root.findall(".//releases/release"):
        ts = rel.attrib.get("timestamp", "")
        date = rel.attrib.get("date", "")
        try:
            newest = max(newest, int(ts))
        except Exception:
            pass
        if date:
            try:
                newest = max(newest, int(datetime.datetime.strptime(date[:10], "%Y-%m-%d").replace(tzinfo=datetime.timezone.utc).timestamp()))
            except Exception:
                pass
    icon = ""
    for node in root.findall(".//icon"):
        if node.text and node.text.strip():
            icon = node.text.strip()
            if node.attrib.get("type") in ("cached", "stock"):
                break
    return cats, newest, icon


def friendly_category(categories):
    c = set(categories)
    if c & {"Network", "WebBrowser", "Email", "Chat", "InstantMessaging"}:
        return "Internet"
    if c & {"Office", "WordProcessor", "Spreadsheet", "Presentation"}:
        return "Office"
    if c & {"AudioVideo", "Audio", "Video", "Player", "Recorder", "Music"}:
        return "Media"
    if c & {"Graphics", "Photography", "RasterGraphics", "VectorGraphics", "2DGraphics", "3DGraphics"}:
        return "Graphics"
    if c & {"Game"}:
        return "Games"
    if c & {"Development", "IDE", "GUIDesigner"}:
        return "Development"
    if c & {"Utility", "System", "FileManager", "Archiving", "Calculator", "TextEditor"}:
        return "Utilities"
    return "Other"


def apt_meta(pkg):
    text = run(["apt-cache", "show", "--no-all-versions", pkg], 10)
    desc = "Debian application"
    size = 0
    for line in text.splitlines():
        if line.startswith("Description") and ": " in line:
            desc = line.split(": ", 1)[1].strip() or desc
            break
    m = re.search(r"^Installed-Size:\s*(\d+)", text, re.M)
    if m:
        size = int(m.group(1))
    installed = subprocess.run(["dpkg-query", "-W", "-f=${db:Status-Abbrev}", pkg],
                               env=ENV, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               text=True, check=False).stdout.startswith("ii")
    return desc, size, installed


def icon_file(icon):
    if not icon:
        return ""
    names = [os.path.basename(icon)]
    if not any(n.lower().endswith((".png", ".svg", ".xpm")) for n in names):
        names += [icon + ".png"]
    patterns = []
    for name in names:
        patterns += [
            f"/var/cache/app-info/icons/*/64x64/{name}",
            f"/var/cache/app-info/icons/*/128x128/{name}",
            f"/var/cache/swcatalog/icons/*/64x64/{name}",
            f"/var/cache/swcatalog/icons/*/128x128/{name}",
            f"/usr/share/pixmaps/{name}",
            f"/usr/share/icons/hicolor/*/apps/{name}",
            f"/usr/share/icons/breeze/*/apps/{name}",
        ]
    for pattern in patterns:
        for path in glob.glob(pattern):
            if os.path.isfile(path) and path.lower().endswith(".png") and os.path.getsize(path) <= 256 * 1024:
                return path
    return ""


def b64_text(value):
    return base64.b64encode((value or "").encode("utf-8")).decode("ascii")


def b64_file(path):
    if not path:
        return ""
    try:
        with open(path, "rb") as f:
            return base64.b64encode(f.read()).decode("ascii")
    except Exception:
        return ""


def exact_for_package(pkg):
    blocks = parse_search(run(["appstreamcli", "search", "--details", "--no-color", pkg], 15))
    exact = [x for x in blocks if x.get("Package") == pkg]
    if exact:
        return exact[0]
    return blocks[0] if blocks else {"Package": pkg, "Name": pkg.replace("-", " ").title(), "Summary": ""}


def main():
    query = sys.argv[1] if len(sys.argv) > 1 else ""
    sort_mode = (sys.argv[2] if len(sys.argv) > 2 else "POPULAR").upper()
    category = sys.argv[3] if len(sys.argv) > 3 else "All"

    if query.strip():
        blocks = parse_search(run(["appstreamcli", "search", "--details", "--no-color", query.strip()], 20))[:50]
    else:
        blocks = [exact_for_package(pkg) for pkg in POPULAR]

    seen = set()
    apps = []
    for block in blocks:
        pkg = block.get("Package", "").strip()
        if not pkg or pkg in seen or not re.fullmatch(r"[a-z0-9][a-z0-9+.-]{0,127}", pkg):
            continue
        seen.add(pkg)
        desc, size, installed = apt_meta(pkg)
        component_id = block.get("Identifier", "")
        cats, release_ts, dump_icon = dump_meta(component_id) if component_id else ([], 0, "")
        friendly = friendly_category(cats)
        if category != "All" and friendly != category:
            continue
        icon = block.get("Icon", "") or dump_icon
        icon_path = icon_file(icon)
        name = block.get("Name", "").strip() or pkg.replace("-", " ").title()
        summary = block.get("Summary", "").strip() or desc
        apps.append({
            "pkg": pkg, "id": component_id, "name": name, "summary": summary,
            "installed": installed, "size": size, "category": friendly,
            "release": release_ts, "rank": RANK.get(pkg, 10000), "icon": icon_path,
        })

    if sort_mode == "NEW":
        apps.sort(key=lambda a: (-a["release"], a["name"].lower()))
    elif sort_mode == "INSTALLED":
        apps.sort(key=lambda a: (not a["installed"], a["name"].lower()))
    elif sort_mode == "SIZE":
        apps.sort(key=lambda a: (-a["size"], a["name"].lower()))
    elif sort_mode == "AZ":
        apps.sort(key=lambda a: a["name"].lower())
    else:
        apps.sort(key=lambda a: (a["rank"], a["name"].lower()))

    for a in apps[:50]:
        print("VESSEL_APP\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
            a["pkg"], 1 if a["installed"] else 0, a["size"], a["release"], a["rank"],
            b64_text(a["category"]), b64_text(a["id"]), b64_text(a["name"]),
            b64_text(a["summary"]), b64_file(a["icon"])))


if __name__ == "__main__":
    main()
