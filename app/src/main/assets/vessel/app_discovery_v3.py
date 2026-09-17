#!/usr/bin/env python3
import base64
import datetime
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

CACHE_VERSION = 5
CACHE_PATH = "/var/cache/vessel/app-catalog-v5.json"
ICON_CACHE_DIR = "/var/cache/vessel/icons-v5"
ENV = dict(os.environ, LC_ALL="C", LANG="C")
PACKAGE_RE = re.compile(r"[a-z0-9][a-z0-9+.-]{0,127}$")

POPULAR = [
    ("firefox-esr", "Firefox ESR", "Web browser", "Internet"),
    ("vlc", "VLC", "Media player", "Media"),
    ("libreoffice-writer", "LibreOffice Writer", "Word processor", "Office"),
    ("libreoffice-calc", "LibreOffice Calc", "Spreadsheet", "Office"),
    ("gimp", "GIMP", "Image editor", "Graphics"),
    ("inkscape", "Inkscape", "Vector graphics editor", "Graphics"),
    ("krita", "Krita", "Digital painting", "Graphics"),
    ("kate", "Kate", "Text editor", "Development"),
    ("okular", "Okular", "Document viewer", "Office"),
    ("dolphin", "Dolphin", "File manager", "Utilities"),
    ("konsole", "Konsole", "Terminal emulator", "Development"),
    ("gwenview", "Gwenview", "Image viewer", "Graphics"),
    ("ark", "Ark", "Archive manager", "Utilities"),
    ("kcalc", "KCalc", "Calculator", "Utilities"),
    ("audacity", "Audacity", "Audio editor", "Media"),
    ("filezilla", "FileZilla", "FTP client", "Internet"),
    ("transmission-qt", "Transmission", "BitTorrent client", "Internet"),
    ("keepassxc", "KeePassXC", "Password manager", "Utilities"),
    ("mpv", "mpv", "Media player", "Media"),
    ("qbittorrent", "qBittorrent", "BitTorrent client", "Internet"),
    ("thunderbird", "Thunderbird", "Email client", "Internet"),
    ("blender", "Blender", "3D creation suite", "Graphics"),
    ("obs-studio", "OBS Studio", "Screen recording and streaming", "Media"),
    ("kdenlive", "Kdenlive", "Video editor", "Media"),
    ("gitg", "gitg", "Git repository viewer", "Development"),
]
RANK = {pkg: index for index, (pkg, _, _, _) in enumerate(POPULAR)}
POPULAR_META = {pkg: (name, summary, category) for pkg, name, summary, category in POPULAR}


def run(args, timeout=10):
    try:
        return subprocess.run(
            args, env=ENV, text=True, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, timeout=timeout, check=False,
        ).stdout
    except Exception:
        return ""


def scalar(value, default=""):
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, dict):
        for key in ("C", "en", "en_US", "en-GB"):
            if value.get(key):
                return scalar(value[key], default)
        for item in value.values():
            text = scalar(item, "")
            if text:
                return text
    if isinstance(value, list):
        for item in value:
            text = scalar(item, "")
            if text:
                return text
    return default


def friendly_category(values):
    c = set(values or [])
    if c & {"Network", "WebBrowser", "Email", "Chat", "InstantMessaging"}: return "Internet"
    if c & {"Office", "WordProcessor", "Spreadsheet", "Presentation"}: return "Office"
    if c & {"AudioVideo", "Audio", "Video", "Player", "Recorder", "Music"}: return "Media"
    if c & {"Graphics", "Photography", "RasterGraphics", "VectorGraphics", "2DGraphics", "3DGraphics"}: return "Graphics"
    if c & {"Game"}: return "Games"
    if c & {"Development", "IDE", "GUIDesigner"}: return "Development"
    if c & {"Utility", "System", "FileManager", "Archiving", "Calculator", "TextEditor"}: return "Utilities"
    return "Other"


def category_from_section(section):
    s = (section or "").lower()
    if any(x in s for x in ("web", "net", "mail")): return "Internet"
    if any(x in s for x in ("office", "text")): return "Office"
    if any(x in s for x in ("sound", "video", "multimedia")): return "Media"
    if any(x in s for x in ("graphics", "photo")): return "Graphics"
    if "game" in s: return "Games"
    if any(x in s for x in ("devel", "debug")): return "Development"
    if any(x in s for x in ("utils", "admin")): return "Utilities"
    return "Other"


def release_timestamp(releases):
    newest = 0
    for rel in releases if isinstance(releases, list) else []:
        if not isinstance(rel, dict):
            continue
        for key in ("unix-timestamp", "timestamp"):
            try:
                newest = max(newest, int(rel.get(key, 0) or 0))
            except Exception:
                pass
        date = scalar(rel.get("date"))
        if date:
            try:
                dt = datetime.datetime.strptime(date[:10], "%Y-%m-%d").replace(tzinfo=datetime.timezone.utc)
                newest = max(newest, int(dt.timestamp()))
            except Exception:
                pass
    return newest


def open_text(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "rt", encoding="utf-8", errors="replace")


def add(out, pkg, name="", summary="", category="Other", app_id="", icon="", release=0):
    pkg = (pkg or "").split(":", 1)[0].strip()
    if not PACKAGE_RE.fullmatch(pkg):
        return
    fallback = POPULAR_META.get(pkg, (pkg.replace("-", " ").title(), "Debian application", category))
    item = {
        "pkg": pkg,
        "id": app_id or "",
        "name": name or fallback[0],
        "summary": summary or fallback[1],
        "category": category if category != "Other" else fallback[2],
        "release": int(release or 0),
        "rank": RANK.get(pkg, 10000),
        "icon": icon or pkg,
        "size": 0,
    }
    old = out.get(pkg)
    if old is None or (item["release"], bool(item["id"])) > (old.get("release", 0), bool(old.get("id"))):
        out[pkg] = item


def catalog_files():
    roots = (
        "/var/cache/swcatalog/xml", "/var/lib/app-info/xmls",
        "/var/cache/swcatalog/yaml", "/var/lib/swcatalog/yaml", "/var/lib/app-info/yaml",
    )
    files = []
    for root in roots:
        try:
            for name in os.listdir(root):
                path = os.path.join(root, name)
                if os.path.isfile(path):
                    files.append(path)
        except OSError:
            pass
    return sorted(set(files))


def read_yaml_file(path, out):
    try:
        import yaml
    except Exception:
        return
    try:
        with open_text(path) as fh:
            for raw in yaml.safe_load_all(fh):
                if not isinstance(raw, dict):
                    continue
                typ = scalar(raw.get("Type") or raw.get("type")).lower()
                if typ and typ not in ("desktop-application", "desktop", "generic"):
                    continue
                pkg = scalar(raw.get("Package") or raw.get("package")).split(",", 1)[0]
                cats = raw.get("Categories") or raw.get("categories") or []
                if isinstance(cats, str):
                    cats = [x for x in re.split(r"[;,]", cats) if x]
                add(
                    out, pkg, scalar(raw.get("Name") or raw.get("name")),
                    scalar(raw.get("Summary") or raw.get("summary")), friendly_category(cats),
                    scalar(raw.get("ID") or raw.get("id")), scalar(raw.get("Icon") or raw.get("icon")),
                    release_timestamp(raw.get("Releases") or raw.get("releases") or []),
                )
    except Exception:
        pass


def read_xml_file(path, out):
    try:
        with open_text(path) as fh:
            for _, comp in ET.iterparse(fh, events=("end",)):
                if comp.tag.rsplit("}", 1)[-1] != "component":
                    continue
                def child(name):
                    node = next((n for n in list(comp) if n.tag.rsplit("}", 1)[-1] == name), None)
                    return (node.text or "").strip() if node is not None else ""
                pkg = child("pkgname")
                if pkg:
                    cats = [n.text.strip() for n in comp.iter() if n.tag.rsplit("}", 1)[-1] == "category" and n.text]
                    rels = [dict(n.attrib) for n in comp.iter() if n.tag.rsplit("}", 1)[-1] == "release"]
                    icon = next(((n.text or "").strip() for n in comp.iter() if n.tag.rsplit("}", 1)[-1] == "icon" and n.text), "")
                    add(out, pkg, child("name"), child("summary"), friendly_category(cats), child("id"), icon, release_timestamp(rels))
                comp.clear()
    except Exception:
        pass


def build_cache():
    apps = {}
    for path in catalog_files():
        lower = path.lower()
        if ".xml" in lower:
            read_xml_file(path, apps)
        elif any(x in lower for x in (".yml", ".yaml")):
            read_yaml_file(path, apps)
    for pkg, name, summary, category in POPULAR:
        add(apps, pkg, name, summary, category)
    os.makedirs(os.path.dirname(CACHE_PATH), exist_ok=True)
    payload = {"version": CACHE_VERSION, "built_at": int(time.time()), "apps": list(apps.values())}
    fd, tmp = tempfile.mkstemp(prefix="app-catalog-v5-", suffix=".json", dir=os.path.dirname(CACHE_PATH))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, CACHE_PATH)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    return payload


def load_cache():
    # Do not stat/glob every AppStream source on every UI interaction. Package
    # operations explicitly invalidate this cache when metadata can change.
    try:
        with open(CACHE_PATH, "r", encoding="utf-8") as fh:
            payload = json.load(fh)
        if payload.get("version") == CACHE_VERSION and isinstance(payload.get("apps"), list):
            return payload
    except Exception:
        pass
    return build_cache()


def installed_packages():
    out = {}
    try:
        with open("/var/lib/dpkg/status", "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except Exception:
        return out
    for block in text.split("\n\n"):
        pkg = status = ""
        size = 0
        for line in block.splitlines():
            if line.startswith("Package: "): pkg = line[9:].strip()
            elif line.startswith("Status: "): status = line[8:].strip()
            elif line.startswith("Installed-Size: "):
                try: size = int(line[16:].strip())
                except Exception: pass
        if PACKAGE_RE.fullmatch(pkg) and status == "install ok installed":
            out[pkg] = size
    return out


def supplement_search(apps, query):
    if not query:
        return
    text = run(["apt-cache", "search", "--names-only", query], 5)
    found = []
    summaries = {}
    for line in text.splitlines():
        if " - " not in line:
            continue
        pkg, summary = line.split(" - ", 1)
        pkg = pkg.strip().split(":", 1)[0]
        if PACKAGE_RE.fullmatch(pkg) and pkg not in found:
            found.append(pkg)
            summaries[pkg] = summary.strip()
        if len(found) >= 40:
            break
    if not found:
        return
    meta = run(["apt-cache", "show", "--no-all-versions", *found], 7)
    details = {}
    for block in meta.split("\n\n"):
        pkg = desc = section = ""
        size = 0
        for line in block.splitlines():
            if line.startswith("Package: "): pkg = line[9:].strip()
            elif line.startswith("Description: "): desc = line[13:].strip()
            elif line.startswith("Section: "): section = line[9:].strip()
            elif line.startswith("Installed-Size: "):
                try: size = int(line[16:].strip())
                except Exception: pass
        if PACKAGE_RE.fullmatch(pkg):
            details[pkg] = (desc, section, size)
    for pkg in found:
        if pkg in apps:
            continue
        desc, section, size = details.get(pkg, (summaries.get(pkg, "Debian package"), "", 0))
        add(apps, pkg, pkg.replace("-", " ").title(), desc or summaries.get(pkg, "Debian package"), category_from_section(section))
        apps[pkg]["size"] = size


def build_icon_index():
    index = {}
    roots = (
        "/var/cache/app-info/icons", "/var/lib/app-info/icons",
        "/var/cache/swcatalog/icons", "/var/lib/swcatalog/icons",
        "/usr/share/pixmaps", "/usr/share/icons/hicolor", "/usr/share/icons/breeze",
    )
    for root in roots:
        if not os.path.isdir(root):
            continue
        for base, _, files in os.walk(root):
            for name in files:
                lower = name.lower()
                if not lower.endswith((".png", ".svg")):
                    continue
                path = os.path.join(base, name)
                try:
                    if os.path.getsize(path) > 512 * 1024:
                        continue
                except OSError:
                    continue
                stem = os.path.splitext(name)[0]
                old = index.get(name) or index.get(stem)
                # Prefer ready-to-send PNG over SVG if both exist.
                if old is None or (old.lower().endswith(".svg") and lower.endswith(".png")):
                    index[name] = path
                    index[stem] = path
    return index


def icon_file(icon, index):
    if not icon:
        return ""
    if os.path.isabs(icon) and os.path.isfile(icon):
        path = icon
    else:
        name = os.path.basename(icon)
        path = index.get(name) or index.get(os.path.splitext(name)[0]) or ""
    if not path:
        return ""
    if path.lower().endswith(".png"):
        return path
    os.makedirs(ICON_CACHE_DIR, exist_ok=True)
    try:
        stamp = f"{path}:{os.path.getmtime(path)}:{os.path.getsize(path)}".encode()
    except OSError:
        return ""
    out = os.path.join(ICON_CACHE_DIR, hashlib.sha256(stamp).hexdigest()[:24] + ".png")
    if os.path.isfile(out) and os.path.getsize(out) > 0:
        return out
    try:
        subprocess.run(
            ["rsvg-convert", "-w", "96", "-h", "96", "-o", out, path],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3, check=False,
        )
        if os.path.isfile(out) and 0 < os.path.getsize(out) <= 512 * 1024:
            return out
    except Exception:
        pass
    return ""


def b64_text(value):
    return base64.b64encode((value or "").encode("utf-8")).decode("ascii")


def b64_file(path):
    if not path:
        return ""
    try:
        with open(path, "rb") as fh:
            return base64.b64encode(fh.read()).decode("ascii")
    except Exception:
        return ""


def hot_score(app, days):
    now = int(time.time())
    release = int(app.get("release", 0) or 0)
    age = max(0, now - release) if release else days * 86400 * 2
    freshness = max(0.0, 1.0 - age / float(days * 86400))
    rank = int(app.get("rank", 10000) or 10000)
    popularity = max(0.0, 1.0 - min(rank, 10000) / 10000.0)
    installed_bonus = 0.05 if app.get("installed") else 0.0
    return freshness * 4.0 + popularity + installed_bonus


def main():
    query = (sys.argv[1] if len(sys.argv) > 1 else "").strip()
    sort_mode = (sys.argv[2] if len(sys.argv) > 2 else "POPULAR").upper()
    category = sys.argv[3] if len(sys.argv) > 3 else "All"
    payload = load_cache()
    by_pkg = {x.get("pkg"): dict(x) for x in payload.get("apps", []) if PACKAGE_RE.fullmatch(x.get("pkg", ""))}
    supplement_search(by_pkg, query)
    installed = installed_packages()
    apps = []
    q = query.lower()
    for item in by_pkg.values():
        pkg = item["pkg"]
        item["installed"] = pkg in installed
        if item["installed"] and installed[pkg] > 0:
            item["size"] = installed[pkg]
        if q and q not in (pkg + " " + item.get("name", "") + " " + item.get("summary", "") + " " + item.get("id", "")).lower():
            continue
        if category != "All" and item.get("category", "Other") != category:
            continue
        apps.append(item)

    if sort_mode == "NEW": apps.sort(key=lambda a: (-int(a.get("release", 0)), a.get("name", "").lower()))
    elif sort_mode == "HOT_WEEK": apps.sort(key=lambda a: (-hot_score(a, 7), -int(a.get("release", 0)), a.get("name", "").lower()))
    elif sort_mode == "HOT_MONTH": apps.sort(key=lambda a: (-hot_score(a, 30), -int(a.get("release", 0)), a.get("name", "").lower()))
    elif sort_mode == "HOT_YEAR": apps.sort(key=lambda a: (-hot_score(a, 365), -int(a.get("release", 0)), a.get("name", "").lower()))
    elif sort_mode == "INSTALLED": apps.sort(key=lambda a: (not a.get("installed", False), a.get("name", "").lower()))
    elif sort_mode == "SIZE": apps.sort(key=lambda a: (-int(a.get("size", 0)), a.get("name", "").lower()))
    elif sort_mode == "AZ": apps.sort(key=lambda a: a.get("name", "").lower())
    else: apps.sort(key=lambda a: (int(a.get("rank", 10000)), -int(a.get("release", 0)), a.get("name", "").lower()))

    # One index walk is dramatically cheaper than globbing the whole icon tree
    # separately for every card. Limit payload size so the RPC/UI stays snappy.
    visible = apps[:64]
    icon_index = build_icon_index()
    for app in visible:
        path = icon_file(app.get("icon", ""), icon_index)
        print("VESSEL_APP\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
            app["pkg"], 1 if app.get("installed") else 0, int(app.get("size", 0)), int(app.get("release", 0)),
            int(app.get("rank", 10000)), b64_text(app.get("category", "Other")), b64_text(app.get("id", "")),
            b64_text(app.get("name", app["pkg"])), b64_text(app.get("summary", "Debian application")), b64_file(path),
        ))


if __name__ == "__main__":
    main()
