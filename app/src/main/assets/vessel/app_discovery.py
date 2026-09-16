#!/usr/bin/env python3
import base64
import datetime
import glob
import gzip
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

CACHE_VERSION = 2
CACHE_PATH = "/var/cache/vessel/app-catalog-v2.json"
POPULAR = [
    "firefox-esr", "vlc", "libreoffice-writer", "gimp", "inkscape", "kate",
    "okular", "dolphin", "konsole", "gwenview", "ark", "kcalc", "audacity",
    "filezilla", "transmission-qt", "keepassxc", "mpv", "qbittorrent",
    "thunderbird", "krita",
]
RANK = {pkg: i for i, pkg in enumerate(POPULAR)}
ENV = dict(os.environ, LC_ALL="C", LANG="C")
PACKAGE_RE = re.compile(r"[a-z0-9][a-z0-9+.-]{0,127}$")


def run(args, timeout=30):
    try:
        p = subprocess.run(
            args,
            env=ENV,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
        )
        return p.stdout
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
            if key in value and value[key]:
                return scalar(value[key], default)
        for item in value.values():
            text = scalar(item, "")
            if text:
                return text
    if isinstance(value, list) and value:
        return scalar(value[0], default)
    return default


def package_name(value):
    if isinstance(value, list):
        value = value[0] if value else ""
    value = scalar(value).split(",", 1)[0].strip()
    return value if PACKAGE_RE.fullmatch(value) else ""


def categories(value):
    if isinstance(value, str):
        return [x.strip() for x in re.split(r"[;,]", value) if x.strip()]
    if isinstance(value, list):
        return [scalar(x) for x in value if scalar(x)]
    return []


def friendly_category(values):
    c = set(values)
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


def release_timestamp(value):
    newest = 0
    records = value if isinstance(value, list) else []
    for rel in records:
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
                newest = max(
                    newest,
                    int(datetime.datetime.strptime(date[:10], "%Y-%m-%d").replace(tzinfo=datetime.timezone.utc).timestamp()),
                )
            except Exception:
                pass
    return newest


def normalize_icon(value):
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, dict):
        for key in ("cached", "stock", "local", "remote"):
            text = scalar(value.get(key))
            if text:
                return text
        return scalar(value)
    if isinstance(value, list):
        for item in value:
            text = normalize_icon(item)
            if text:
                return text
    return ""


def source_mtime():
    paths = []
    for pattern in (
        "/var/lib/app-info/yaml/*",
        "/var/cache/swcatalog/yaml/*",
        "/var/lib/swcatalog/yaml/*",
        "/var/cache/swcatalog/xml/*",
        "/var/lib/app-info/xmls/*",
        "/var/lib/apt/lists/*",
    ):
        paths.extend(glob.glob(pattern))
    return int(max((os.path.getmtime(p) for p in paths if os.path.isfile(p)), default=0))


def open_text(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", errors="replace")
    return open(path, "rt", encoding="utf-8", errors="replace")


def add_component(out, raw):
    if not isinstance(raw, dict):
        return
    typ = scalar(raw.get("Type") or raw.get("type")).lower()
    if typ and typ not in ("desktop-application", "desktop", "generic"):
        return
    pkg = package_name(raw.get("Package") or raw.get("package"))
    if not pkg:
        return
    app_id = scalar(raw.get("ID") or raw.get("id"))
    name = scalar(raw.get("Name") or raw.get("name"), pkg.replace("-", " ").title())
    summary = scalar(raw.get("Summary") or raw.get("summary"), "Debian application")
    cats = categories(raw.get("Categories") or raw.get("categories"))
    icon = normalize_icon(raw.get("Icon") or raw.get("icon"))
    releases = raw.get("Releases") or raw.get("releases") or []
    item = {
        "pkg": pkg,
        "id": app_id,
        "name": name,
        "summary": summary,
        "category": friendly_category(cats),
        "release": release_timestamp(releases),
        "rank": RANK.get(pkg, 10000),
        "icon": icon,
        "size": 0,
    }
    old = out.get(pkg)
    if old is None or (not old.get("id") and app_id):
        out[pkg] = item


def read_yaml_catalog(out):
    try:
        import yaml
    except Exception:
        return False
    found = False
    paths = []
    for pattern in (
        "/var/lib/app-info/yaml/*.yml",
        "/var/lib/app-info/yaml/*.yml.gz",
        "/var/cache/swcatalog/yaml/*.yml",
        "/var/cache/swcatalog/yaml/*.yml.gz",
        "/var/lib/swcatalog/yaml/*.yml",
        "/var/lib/swcatalog/yaml/*.yml.gz",
    ):
        paths.extend(glob.glob(pattern))
    for path in sorted(set(paths)):
        try:
            with open_text(path) as fh:
                for doc in yaml.safe_load_all(fh):
                    if isinstance(doc, dict) and ("Package" in doc or "package" in doc):
                        add_component(out, doc)
                        found = True
        except Exception:
            continue
    return found


def read_xml_catalog(out):
    found = False
    paths = []
    for pattern in (
        "/var/cache/swcatalog/xml/*.xml",
        "/var/cache/swcatalog/xml/*.xml.gz",
        "/var/lib/app-info/xmls/*.xml",
        "/var/lib/app-info/xmls/*.xml.gz",
    ):
        paths.extend(glob.glob(pattern))
    for path in sorted(set(paths)):
        try:
            with open_text(path) as fh:
                root = ET.parse(fh).getroot()
            for comp in root.findall(".//component"):
                pkg_node = comp.find("pkgname")
                if pkg_node is None or not pkg_node.text:
                    continue
                raw = {
                    "Type": comp.attrib.get("type", ""),
                    "Package": pkg_node.text,
                    "ID": comp.findtext("id", ""),
                    "Name": comp.findtext("name", ""),
                    "Summary": comp.findtext("summary", ""),
                    "Categories": [x.text for x in comp.findall("./categories/category") if x.text],
                    "Icon": next((x.text for x in comp.findall("icon") if x.text), ""),
                    "Releases": [dict(x.attrib) for x in comp.findall("./releases/release")],
                }
                add_component(out, raw)
                found = True
        except Exception:
            continue
    return found


def apt_available():
    result = {}
    text = run(["apt-cache", "dumpavail"], 45)
    for block in text.split("\n\n"):
        pkg = ""
        size = 0
        desc = ""
        for line in block.splitlines():
            if line.startswith("Package: "):
                pkg = line[9:].strip()
            elif line.startswith("Installed-Size: "):
                try:
                    size = int(line[16:].strip())
                except Exception:
                    pass
            elif line.startswith("Description: "):
                desc = line[13:].strip()
        if PACKAGE_RE.fullmatch(pkg):
            result[pkg] = (size, desc)
    return result


def fallback_popular(out):
    for pkg in POPULAR:
        if pkg in out:
            continue
        out[pkg] = {
            "pkg": pkg,
            "id": "",
            "name": pkg.replace("-", " ").title(),
            "summary": "Debian application",
            "category": "Other",
            "release": 0,
            "rank": RANK[pkg],
            "icon": "",
            "size": 0,
        }


def build_cache():
    items = {}
    read_yaml_catalog(items)
    read_xml_catalog(items)
    available = apt_available()
    fallback_popular(items)
    for pkg, item in items.items():
        meta = available.get(pkg)
        if meta:
            item["size"] = meta[0]
            if (not item.get("summary") or item["summary"] == "Debian application") and meta[1]:
                item["summary"] = meta[1]
    payload = {
        "version": CACHE_VERSION,
        "source_mtime": source_mtime(),
        "apps": list(items.values()),
    }
    os.makedirs(os.path.dirname(CACHE_PATH), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix="app-catalog-", suffix=".json", dir=os.path.dirname(CACHE_PATH))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, CACHE_PATH)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    return payload


def load_cache():
    try:
        with open(CACHE_PATH, "r", encoding="utf-8") as fh:
            payload = json.load(fh)
        if payload.get("version") != CACHE_VERSION:
            raise ValueError("schema")
        if int(payload.get("source_mtime", -1)) != source_mtime():
            raise ValueError("stale")
        if not isinstance(payload.get("apps"), list):
            raise ValueError("bad apps")
        return payload
    except Exception:
        return build_cache()


def installed_packages():
    installed = {}
    try:
        with open("/var/lib/dpkg/status", "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except Exception:
        return installed
    for block in text.split("\n\n"):
        pkg = ""
        status = ""
        size = 0
        for line in block.splitlines():
            if line.startswith("Package: "):
                pkg = line[9:].strip()
            elif line.startswith("Status: "):
                status = line[8:].strip()
            elif line.startswith("Installed-Size: "):
                try:
                    size = int(line[16:].strip())
                except Exception:
                    pass
        if PACKAGE_RE.fullmatch(pkg) and status == "install ok installed":
            installed[pkg] = size
    return installed


def icon_file(icon):
    if not icon:
        return ""
    icon = os.path.basename(icon)
    names = [icon]
    if not icon.lower().endswith((".png", ".svg", ".xpm")):
        names += [icon + ".png", icon + ".svg"]
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
        with open(path, "rb") as fh:
            return base64.b64encode(fh.read()).decode("ascii")
    except Exception:
        return ""


def main():
    query = (sys.argv[1] if len(sys.argv) > 1 else "").strip().lower()
    sort_mode = (sys.argv[2] if len(sys.argv) > 2 else "POPULAR").upper()
    category = sys.argv[3] if len(sys.argv) > 3 else "All"
    payload = load_cache()
    installed = installed_packages()
    apps = []
    for raw in payload.get("apps", []):
        pkg = raw.get("pkg", "")
        if not PACKAGE_RE.fullmatch(pkg):
            continue
        item = dict(raw)
        item["installed"] = pkg in installed
        if item["installed"] and installed[pkg] > 0:
            item["size"] = installed[pkg]
        haystack = " ".join((pkg, item.get("name", ""), item.get("summary", ""), item.get("id", ""))).lower()
        if query and query not in haystack:
            continue
        if category != "All" and item.get("category", "Other") != category:
            continue
        apps.append(item)

    if sort_mode == "NEW":
        apps.sort(key=lambda a: (-int(a.get("release", 0)), a.get("name", "").lower()))
    elif sort_mode == "INSTALLED":
        apps.sort(key=lambda a: (not a.get("installed", False), a.get("name", "").lower()))
    elif sort_mode == "SIZE":
        apps.sort(key=lambda a: (-int(a.get("size", 0)), a.get("name", "").lower()))
    elif sort_mode == "AZ":
        apps.sort(key=lambda a: a.get("name", "").lower())
    else:
        apps.sort(key=lambda a: (int(a.get("rank", 10000)), a.get("name", "").lower()))

    for app in apps[:80]:
        path = icon_file(app.get("icon", ""))
        print("VESSEL_APP\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
            app["pkg"],
            1 if app.get("installed") else 0,
            int(app.get("size", 0)),
            int(app.get("release", 0)),
            int(app.get("rank", 10000)),
            b64_text(app.get("category", "Other")),
            b64_text(app.get("id", "")),
            b64_text(app.get("name", "") or app["pkg"].replace("-", " ").title()),
            b64_text(app.get("summary", "") or "Debian application"),
            b64_file(path),
        ))


if __name__ == "__main__":
    main()
