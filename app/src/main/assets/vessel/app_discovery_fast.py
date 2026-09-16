#!/usr/bin/env python3
import base64
import glob
import os
import re
import subprocess
import sys

ENV = dict(os.environ, LC_ALL="C", LANG="C")
PACKAGE_RE = re.compile(r"[a-z0-9][a-z0-9+.-]{0,127}$")

POPULAR = [
    ("firefox-esr", "Firefox ESR", "Web browser", "Internet"),
    ("vlc", "VLC", "Media player", "Media"),
    ("libreoffice-writer", "LibreOffice Writer", "Word processor", "Office"),
    ("gimp", "GIMP", "Image editor", "Graphics"),
    ("inkscape", "Inkscape", "Vector graphics editor", "Graphics"),
    ("kate", "Kate", "Text editor", "Utilities"),
    ("okular", "Okular", "Document viewer", "Office"),
    ("dolphin", "Dolphin", "File manager", "Utilities"),
    ("konsole", "Konsole", "Terminal emulator", "Utilities"),
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
    ("krita", "Krita", "Digital painting", "Graphics"),
]
RANK = {pkg: i for i, (pkg, _, _, _) in enumerate(POPULAR)}


def run(args, timeout=20):
    try:
        p = subprocess.run(args, env=ENV, text=True, stdout=subprocess.PIPE,
                           stderr=subprocess.DEVNULL, timeout=timeout, check=False)
        return p.stdout
    except Exception:
        return ""


def installed_packages():
    out = {}
    try:
        text = open("/var/lib/dpkg/status", "r", encoding="utf-8", errors="replace").read()
    except Exception:
        return out
    for block in text.split("\n\n"):
        pkg = status = ""
        size = 0
        for line in block.splitlines():
            if line.startswith("Package: "):
                pkg = line[9:].strip()
            elif line.startswith("Status: "):
                status = line[8:].strip()
            elif line.startswith("Installed-Size: "):
                try: size = int(line[16:].strip())
                except Exception: pass
        if PACKAGE_RE.fullmatch(pkg) and status == "install ok installed":
            out[pkg] = size
    return out


def category_from_section(section):
    section = (section or "").lower()
    if any(x in section for x in ("web", "net", "mail")): return "Internet"
    if any(x in section for x in ("office", "text")): return "Office"
    if any(x in section for x in ("sound", "video", "multimedia")): return "Media"
    if any(x in section for x in ("graphics", "photo")): return "Graphics"
    if "game" in section: return "Games"
    if any(x in section for x in ("devel", "debug")): return "Development"
    return "Other"


def apt_meta(packages):
    result = {}
    packages = [p for p in packages if PACKAGE_RE.fullmatch(p)][:100]
    if not packages:
        return result
    text = run(["apt-cache", "show", "--no-all-versions", *packages], 20)
    for block in text.split("\n\n"):
        pkg = desc = section = ""
        size = 0
        for line in block.splitlines():
            if line.startswith("Package: "): pkg = line[9:].strip()
            elif line.startswith("Installed-Size: "):
                try: size = int(line[16:].strip())
                except Exception: pass
            elif line.startswith("Description: "): desc = line[13:].strip()
            elif line.startswith("Section: "): section = line[9:].strip()
        if PACKAGE_RE.fullmatch(pkg):
            result[pkg] = (size, desc, section)
    return result


def icon_file(icon):
    if not icon:
        return ""
    name = os.path.basename(icon)
    names = [name]
    if not name.lower().endswith((".png", ".svg", ".xpm")):
        names += [name + ".png", name + ".svg"]
    for n in names:
        for pattern in (
            f"/usr/share/pixmaps/{n}",
            f"/usr/share/icons/hicolor/*/apps/{n}",
            f"/usr/share/icons/breeze/*/apps/{n}",
            f"/var/cache/app-info/icons/*/64x64/{n}",
            f"/var/cache/swcatalog/icons/*/64x64/{n}",
        ):
            for path in glob.glob(pattern):
                if os.path.isfile(path) and path.lower().endswith(".png") and os.path.getsize(path) <= 256 * 1024:
                    return path
    return ""


def b64_text(value):
    return base64.b64encode((value or "").encode("utf-8")).decode("ascii")


def b64_file(path):
    if not path: return ""
    try:
        return base64.b64encode(open(path, "rb").read()).decode("ascii")
    except Exception:
        return ""


def installed_desktop_candidates(installed):
    paths = sorted(glob.glob("/usr/share/applications/*.desktop"))
    if not paths:
        return []
    owners = run(["dpkg-query", "-S", *paths], 20)
    by_path = {}
    for line in owners.splitlines():
        if ": " not in line: continue
        left, path = line.split(": ", 1)
        pkg = left.split(",", 1)[0].split(":", 1)[0]
        if PACKAGE_RE.fullmatch(pkg): by_path[path] = pkg
    out = []
    seen = set()
    for path in paths:
        pkg = by_path.get(path)
        if not pkg or pkg not in installed or pkg in seen:
            continue
        name = summary = icon = ""
        cats = []
        try:
            for line in open(path, "r", encoding="utf-8", errors="replace"):
                line = line.rstrip("\n")
                if line.startswith("Name=") and not name: name = line[5:].strip()
                elif line.startswith("Comment=") and not summary: summary = line[8:].strip()
                elif line.startswith("Icon=") and not icon: icon = line[5:].strip()
                elif line.startswith("Categories=") and not cats: cats = [x for x in line[11:].split(";") if x]
        except Exception:
            continue
        category = "Other"
        c = set(cats)
        if c & {"Network", "WebBrowser", "Email", "Chat", "InstantMessaging"}: category = "Internet"
        elif c & {"Office", "WordProcessor", "Spreadsheet", "Presentation"}: category = "Office"
        elif c & {"AudioVideo", "Audio", "Video", "Player", "Recorder", "Music"}: category = "Media"
        elif c & {"Graphics", "Photography", "RasterGraphics", "VectorGraphics"}: category = "Graphics"
        elif c & {"Game"}: category = "Games"
        elif c & {"Development", "IDE", "GUIDesigner"}: category = "Development"
        elif c & {"Utility", "System", "FileManager", "Archiving", "Calculator", "TextEditor"}: category = "Utilities"
        out.append({"pkg": pkg, "name": name or pkg.replace("-", " ").title(),
                    "summary": summary or "Installed Debian application", "category": category,
                    "rank": RANK.get(pkg, 10000), "icon": icon, "size": installed[pkg], "release": 0})
        seen.add(pkg)
    return out


def build_apps(query, sort_mode):
    installed = installed_packages()
    apps = []
    seen = set()

    for pkg, name, summary, category in POPULAR:
        apps.append({"pkg": pkg, "name": name, "summary": summary, "category": category,
                     "rank": RANK[pkg], "icon": pkg, "size": installed.get(pkg, 0), "release": 0})
        seen.add(pkg)

    for app in installed_desktop_candidates(installed):
        if app["pkg"] not in seen:
            apps.append(app); seen.add(app["pkg"])

    if query:
        text = run(["apt-cache", "search", "--names-only", query], 15)
        found = []
        summaries = {}
        for line in text.splitlines():
            if " - " not in line: continue
            pkg, summary = line.split(" - ", 1)
            pkg = pkg.strip().split(":", 1)[0]
            if PACKAGE_RE.fullmatch(pkg) and pkg not in found:
                found.append(pkg); summaries[pkg] = summary.strip()
            if len(found) >= 60: break
        meta = apt_meta(found)
        for pkg in found:
            if pkg in seen: continue
            size, desc, section = meta.get(pkg, (installed.get(pkg, 0), summaries.get(pkg, "Debian package"), ""))
            apps.append({"pkg": pkg, "name": pkg.replace("-", " ").title(),
                         "summary": desc or summaries.get(pkg, "Debian package"),
                         "category": category_from_section(section), "rank": 10000,
                         "icon": pkg, "size": size, "release": 0})
            seen.add(pkg)

    for app in apps:
        app["installed"] = app["pkg"] in installed
        if app["installed"] and installed[app["pkg"]] > 0:
            app["size"] = installed[app["pkg"]]

    if query:
        q = query.lower()
        apps = [a for a in apps if q in (a["pkg"] + " " + a["name"] + " " + a["summary"]).lower()]
    if sort_mode == "INSTALLED":
        apps = [a for a in apps if a["installed"]]
        apps.sort(key=lambda a: a["name"].lower())
    elif sort_mode == "SIZE":
        apps.sort(key=lambda a: (-int(a.get("size", 0)), a["name"].lower()))
    elif sort_mode == "AZ":
        apps.sort(key=lambda a: a["name"].lower())
    else:
        apps.sort(key=lambda a: (int(a.get("rank", 10000)), a["name"].lower()))
    return apps


def main():
    query = (sys.argv[1] if len(sys.argv) > 1 else "").strip()
    sort_mode = (sys.argv[2] if len(sys.argv) > 2 else "POPULAR").upper()
    category = sys.argv[3] if len(sys.argv) > 3 else "All"

    apps = build_apps(query, sort_mode)
    if category != "All":
        apps = [a for a in apps if a.get("category", "Other") == category]

    for app in apps[:80]:
        path = icon_file(app.get("icon", ""))
        print("VESSEL_APP\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
            app["pkg"], 1 if app.get("installed") else 0, int(app.get("size", 0)),
            int(app.get("release", 0)), int(app.get("rank", 10000)),
            b64_text(app.get("category", "Other")), b64_text(""), b64_text(app.get("name", "")),
            b64_text(app.get("summary", "Debian application")), b64_file(path)))


if __name__ == "__main__":
    main()
