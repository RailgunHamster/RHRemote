"""Package built Ubuntu 24.04 amd64 client/server executables without user data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def write(root, name, content, executable=False):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")
    path.chmod(0o755 if executable else 0o644)


def binary(source, target):
    with source.open("rb") as stream:
        header = stream.read(20)
    if header[:4] != b"\x7fELF" or header[4:6] != b"\x02\x01" or int.from_bytes(header[18:20], "little") != 62:
        raise RuntimeError(f"Not an amd64 Linux executable: {source.name}")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    target.chmod(0o755)
    subprocess.run(["strip", "--strip-unneeded", str(target)], check=True)


def dependencies(stage, executables, extra):
    # Let Ubuntu determine linked ABI packages instead of embedding libraries
    # from the build machine. QML modules are runtime imports, hence explicit.
    write(stage, "debian/control", "Source: rhremote\nMaintainer: RHRemote <rhremote@users.noreply.github.com>\nSection: net\nPriority: optional\n\nPackage: rhremote\nArchitecture: amd64\nDescription: RHRemote\n")
    result = subprocess.run(["dpkg-shlibdeps", "--ignore-missing-info", "-O", *["-e" + str(p) for p in executables]], cwd=stage, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    linked = next(line.removeprefix("shlibs:Depends=") for line in result.stdout.splitlines() if line.startswith("shlibs:Depends="))
    # debian/control is build metadata, not a payload path.
    (stage / "debian/control").unlink()
    (stage / "debian").rmdir()
    return ", ".join([linked, *extra])


def build(stage, output, package, version, description, depends):
    total = sum(p.stat().st_size for p in stage.rglob("*") if p.is_file())
    write(stage, "DEBIAN/control", f"Package: {package}\nVersion: {version}\nArchitecture: amd64\nMaintainer: RHRemote <rhremote@users.noreply.github.com>\nSection: net\nPriority: optional\nInstalled-Size: {(total + 1023) // 1024}\nDepends: {depends}\nHomepage: https://github.com/RailgunHamster/RHRemote\nDescription: {description}\n Ubuntu 24.04 amd64, X11 desktop session.\n")
    target = output / f"{package}_{version}_ubuntu24.04_amd64.deb"
    if target.exists():
        raise RuntimeError("Refusing to replace an existing package: " + target.name)
    subprocess.run(["dpkg-deb", "--root-owner-group", "-Zxz", "-z6", "--build", str(stage), str(target)], check=True)
    return {"name": target.name, "bytes": target.stat().st_size, "sha256": hashlib.file_digest(target.open("rb"), "sha256").hexdigest()}


def common(stage, package):
    directory = stage / "usr/share/doc" / package
    directory.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "LICENSE", directory / "copyright")
    icon = stage / "usr/share/icons/hicolor/scalable/apps/rhremote.svg"
    icon.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "client/shared/icons/rhremote.svg", icon)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["client", "engine", "host", "sunshine", "display", "assets", "output"]:
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    if os.name != "posix" or not Path("/etc/os-release").is_file():
        raise RuntimeError("Run packaging inside Ubuntu")
    release = Path("/etc/os-release").read_text()
    if 'ID=ubuntu' not in release or 'VERSION_ID="24.04"' not in release:
        raise RuntimeError("This package ABI is validated only on Ubuntu 24.04")
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:[+~][A-Za-z0-9.]+)?", args.version):
        raise RuntimeError("Invalid Debian version")
    if args.output.exists():
        raise RuntimeError("Refusing to replace an existing output directory")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    result = []
    with tempfile.TemporaryDirectory(prefix="rhremote-deb-", dir=args.output.parent) as folder:
        base = Path(folder)
        output = base / "output"
        output.mkdir()
        client = base / "client"
        app = client / "usr/lib/rhremote/client"
        binary(args.client, app / "RHRemote")
        binary(args.engine, app / "engine/RHRemote")
        write(client, "usr/bin/rhremote", '#!/bin/sh\nexec /usr/lib/rhremote/client/RHRemote "$@"\n', True)
        write(client, "usr/share/applications/rhremote-client.desktop", "[Desktop Entry]\nType=Application\nName=RHRemote-Moonlight\nExec=rhremote\nIcon=rhremote\nTerminal=false\nCategories=Network;RemoteAccess;\nStartupWMClass=RHRemote\n")
        common(client, "rhremote-client")
        depends = dependencies(client, [app / "RHRemote", app / "engine/RHRemote"], ["qml6-module-qtquick", "qml6-module-qtquick-window", "qml6-module-qtquick-controls", "qml6-module-qtquick-templates", "qml6-module-qtquick-layouts", "qml6-module-qtqml-workerscript", "qml6-module-qtqml-models", "libva-wayland2", "libsecret-1-0", "gnome-keyring", "fonts-noto-cjk", "xdg-utils"])
        result.append(build(client, output, "rhremote-client", args.version, "RHRemote-Moonlight remote desktop client", depends))

        server = base / "server"
        app = server / "usr/lib/rhremote/server"
        binary(args.host, app / "RHRemoteHost")
        binary(args.sunshine, app / "sunshine")
        binary(args.display, app / "RHRemoteDisplay")
        if not (args.assets / "web/index.html").is_file():
            raise RuntimeError("Built Sunshine web assets are missing")
        shutil.copytree(args.assets, server / "usr/share/rhremote-sunshine")
        common(server, "rhremote-server")
        shutil.copyfile(ROOT / "server/linux/rhremote-server", server / "usr/bin/rhremote-server")
        (server / "usr/bin/rhremote-server").chmod(0o755)
        for source, target in [("rhremote-sunshine.service", "usr/lib/systemd/user/rhremote-sunshine.service"), ("rhremote-server.desktop", "usr/share/applications/rhremote-server.desktop"), ("70-rhremote-input.rules", "usr/lib/udev/rules.d/70-rhremote-input.rules")]:
            write(server, target, (ROOT / "server/linux" / source).read_text())
        write(server, "usr/lib/modules-load.d/rhremote-input.conf", "uinput\n")
        write(server, "DEBIAN/postinst", "#!/bin/sh\nset -e\nif command -v udevadm >/dev/null 2>&1; then udevadm control --reload-rules || true; fi\nmodprobe uinput || true\nif [ -e /sys/class/misc/uinput ]; then udevadm trigger --action=change /sys/class/misc/uinput || true; fi\n# User services start only when explicitly requested in the desktop session.\nexit 0\n", True)
        depends = dependencies(server, [app / "RHRemoteHost", app / "sunshine", app / "RHRemoteDisplay"], ["python3", "systemd", "udev", "xdg-utils", "libxrandr2", "libxfixes3", "libxcb-shm0"])
        result.append(build(server, output, "rhremote-server", args.version, "RHRemote-Sunshine remote desktop server", depends))
        (output / "SHA256SUMS.txt").write_text("".join(item["sha256"] + "  " + item["name"] + "\n" for item in result), encoding="ascii")
        (output / "packages.json").write_text(json.dumps({"version": args.version, "platform": "ubuntu24.04-x11-amd64", "assets": result}, indent=2) + "\n")
        output.rename(args.output)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
