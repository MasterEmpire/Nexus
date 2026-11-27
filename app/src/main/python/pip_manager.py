import io
import importlib
import importlib.metadata
import json
import os
import re
import shutil
import sys
import zipfile
import requests

_MARKER_ENV = {
    "python_version": f"{sys.version_info.major}.{sys.version_info.minor}",
    "python_full_version": f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
    "os_name": os.name,
    "sys_platform": sys.platform,
    "platform_system": "Linux",
    "implementation_name": sys.implementation.name,
}


def normalize_name(name):
    return re.sub(r"[-_.]+", "-", name.strip()).lower()


def get_site_dir():
    base = None
    try:
        from com.chaquo.python import Python
        app = Python.getPlatform().getApplication()
        if app is not None:
            base = str(app.getFilesDir().getAbsolutePath())
    except Exception:
        pass
    if not base:
        base = os.environ.get("HOME")
    if not base or not os.access(base, os.W_OK):
        base = "/data/data/com.conduit.nexus/files"
    site_dir = os.path.join(base, "pip_packages")
    os.makedirs(site_dir, exist_ok=True)
    return site_dir


def ensure_site_dir():
    site_dir = get_site_dir()
    if site_dir not in sys.path:
        sys.path.insert(0, site_dir)
    return site_dir


def _manifest_path():
    return os.path.join(get_site_dir(), ".nexus_pip_manifest.json")


def _load_manifest():
    path = _manifest_path()
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def _save_manifest(data):
    path = _manifest_path()
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)


def _emit(cb, text):
    if cb is not None:
        try:
            cb.onOutput(text)
        except Exception:
            pass


def _get_installed_map():
    ensure_site_dir()
    site_dir = os.path.realpath(get_site_dir())
    installed = {}
    for dist in importlib.metadata.distributions():
        raw_name = dist.metadata.get("Name") or ""
        if not raw_name:
            continue
        norm = normalize_name(raw_name)
        try:
            dist_root = os.path.realpath(str(dist.locate_file("")))
            source = "user" if dist_root.startswith(site_dir) else "bundled"
        except Exception:
            source = "bundled"
        installed[norm] = {
            "name": raw_name,
            "norm": norm,
            "version": dist.version or "0.0.0",
            "source": source,
        }
    return installed


def _eval_marker(marker_expr):
    if not marker_expr:
        return True
    if "extra ==" in marker_expr or "extra==" in marker_expr:
        return False

    # Evaluate simple environment comparisons safely
    clauses = re.split(r"\s+(?:and|or)\s+", marker_expr.strip())
    for clause in clauses:
        m = re.match(
            r"""^\s*([a-zA-Z_]+)\s*(==|!=|<=|>=|<|>|in|not in)\s*['"]([^'"]+)['"]\s*$""",
            clause,
        )
        if not m:
            continue
        var, op, val = m.groups()
        actual = _MARKER_ENV.get(var)
        if actual is None:
            continue
        if op == "==" and actual != val:
            return False
        if op == "!=" and actual == val:
            return False
        if op == "<" and actual >= val:
            return False
        if op == "<=" and actual > val:
            return False
        if op == ">" and actual <= val:
            return False
        if op == ">=" and actual < val:
            return False
        if op == "in" and actual not in val:
            return False
        if op == "not in" and actual in val:
            return False
    return True


def _parse_req(req_str):
    parts = req_str.split(";", 1)
    spec = parts[0].strip()
    marker = parts[1].strip() if len(parts) > 1 else ""
    if not _eval_marker(marker):
        return None
    m = re.match(r"^([A-Za-z0-9_.-]+)(?:\[[^\]]*\])?(.*)$", spec)
    if not m:
        return None
    pkg = m.group(1)
    ver_spec = m.group(2).strip()
    pinned_ver = None
    eq_match = re.match(r"^==\s*([A-Za-z0-9_.+-]+)$", ver_spec)
    if eq_match:
        pinned_ver = eq_match.group(1)
    return pkg, pinned_ver


def _pick_pure_wheel(urls):
    best = None
    for item in urls:
        if item.get("yanked"):
            continue
        fname = item.get("filename", "")
        if fname.endswith("-none-any.whl"):
            if "-py3-" in fname or "-py2.py3-" in fname:
                return item
            best = best or item
    return best


def _extract_wheel(whl_bytes, site_dir):
    extracted_paths = []
    real_site = os.path.realpath(site_dir)
    with zipfile.ZipFile(io.BytesIO(whl_bytes)) as zf:
        for member in zf.infolist():
            if member.is_dir():
                continue
            rel = member.filename.replace("\\", "/")
            if rel.startswith("/") or ".." in rel.split("/"):
                continue
            # Remap .data/purelib/* directly into site_dir
            parts = rel.split("/")
            if len(parts) >= 3 and parts[0].endswith(".data") and parts[1] == "purelib":
                rel = "/".join(parts[2:])
            elif len(parts) >= 2 and parts[0].endswith(".data"):
                continue

            dest = os.path.realpath(os.path.join(real_site, rel))
            if not dest.startswith(real_site + os.sep):
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with zf.open(member) as src, open(dest, "wb") as dst:
                shutil.copyfileobj(src, dst)
            extracted_paths.append(os.path.relpath(dest, real_site))
    return extracted_paths


def install_package(spec_input, progress_callback=None):
    ensure_site_dir()
    site_dir = get_site_dir()
    tokens = [t for t in re.split(r"[\s,]+", (spec_input or "").strip()) if t and not t.startswith("-")]
    if not tokens:
        return "No package name provided."

    logs = []
    visited = set()
    installed_map = _get_installed_map()
    manifest = _load_manifest()

    def log(msg):
        logs.append(msg)
        _emit(progress_callback, msg)

    def _install_one(raw_req, is_root=False):
        parsed = _parse_req(raw_req)
        if not parsed:
            return True
        pkg_name, pinned_ver = parsed
        norm = normalize_name(pkg_name)

        if norm in visited:
            return True
        visited.add(norm)

        existing = installed_map.get(norm)
        if existing and (not pinned_ver or existing["version"] == pinned_ver):
            if is_root:
                log(f"Requirement already satisfied: {existing['name']} ({existing['version']}) [{existing['source']}]")
            return True

        url = f"https://pypi.org/pypi/{pkg_name}/{pinned_ver}/json" if pinned_ver else f"https://pypi.org/pypi/{pkg_name}/json"
        log(f"Looking up {pkg_name}{f'=={pinned_ver}' if pinned_ver else ''} on PyPI...")
        try:
            resp = requests.get(url, timeout=20)
            if resp.status_code == 404:
                log(f"Error: Package '{pkg_name}' not found on PyPI.")
                return False
            resp.raise_for_status()
            data = resp.json()
        except Exception as e:
            log(f"Network error fetching '{pkg_name}': {e}")
            return False

        info = data.get("info", {})
        real_name = info.get("name", pkg_name)
        version = info.get("version", "0.0.0")
        urls = data.get("urls", [])

        wheel = _pick_pure_wheel(urls)
        if not wheel:
            log(
                f"Blocked: '{real_name}' has no pure-Python wheel on PyPI (requires native C/C++ code). "
                "Add it to app/build.gradle and rebuild the APK to use it."
            )
            return False

        for dep in info.get("requires_dist") or []:
            if not _install_one(dep, is_root=False):
                return False

        log(f"Downloading {wheel['filename']}...")
        try:
            w_resp = requests.get(wheel["url"], timeout=45)
            w_resp.raise_for_status()
            files = _extract_wheel(w_resp.content, site_dir)
        except Exception as e:
            log(f"Failed installing {real_name}: {e}")
            return False

        manifest[norm] = {
            "name": real_name,
            "version": version,
            "files": files,
        }
        _save_manifest(manifest)
        installed_map[norm] = {"name": real_name, "norm": norm, "version": version, "source": "user"}
        log(f"Installed {real_name} {version} ({len(files)} files)")
        return True

    ok = True
    for tok in tokens:
        if not _install_one(tok, is_root=True):
            ok = False

    importlib.invalidate_caches()
    if ok:
        log("Done! Ready to import.")
    return "\n".join(logs)


def uninstall_package(pkg_name):
    ensure_site_dir()
    site_dir = get_site_dir()
    norm = normalize_name(pkg_name)
    manifest = _load_manifest()

    entry = manifest.get(norm)
    if not entry:
        installed = _get_installed_map()
        if norm in installed and installed[norm]["source"] == "bundled":
            return f"'{installed[norm]['name']}' is bundled inside the APK and cannot be uninstalled here."
        return f"Package '{pkg_name}' is not installed in user packages."

    removed = 0
    for rel in entry.get("files", []):
        full = os.path.join(site_dir, rel)
        if os.path.isfile(full):
            try:
                os.remove(full)
                removed += 1
            except Exception:
                pass
        parent = os.path.dirname(full)
        while parent and os.path.realpath(parent) != os.path.realpath(site_dir):
            try:
                if not os.listdir(parent):
                    os.rmdir(parent)
                    parent = os.path.dirname(parent)
                else:
                    break
            except Exception:
                break

    manifest.pop(norm, None)
    _save_manifest(manifest)
    importlib.invalidate_caches()
    return f"Uninstalled {entry['name']} ({entry['version']}) - removed {removed} files."


def list_packages_json():
    installed = _get_installed_map()
    items = sorted(installed.values(), key=lambda x: (0 if x["source"] == "user" else 1, x["norm"]))
    return json.dumps(items)


def list_packages():
    items = json.loads(list_packages_json())
    lines = [f"{it['name']}=={it['version']} ({it['source']})" for it in items]
    return "\n".join(lines)


def handle_pip_command(cmd):
    parts = cmd.strip().split()
    if len(parts) < 2:
        return "Usage: !pip install <pkg> | !pip uninstall <pkg> | !pip list"
    sub = parts[1].lower()
    args = " ".join(parts[2:])
    if sub == "install":
        return install_package(args)
    if sub == "uninstall":
        return uninstall_package(args)
    if sub == "list":
        return list_packages()
    return f"Unsupported pip command: '{sub}'. Use install, uninstall, or list."


ensure_site_dir()