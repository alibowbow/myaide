#!/usr/bin/env python3
"""Prepares the PRoot launcher the in-app Linux environment runs on (LinuxRuntime).

Downloads the pinned Termux arm64 packages of proot and the two libraries it needs, checks their SHA-256, and writes
them as native libraries Android installs with the APK (app/src/main/jniLibs/arm64-v8a), since only files named lib*.so
are installed there and only from there may an app run a program:

  libproot.so          proot (its libtalloc.so.2 dependency renamed to libtalloc.so)
  libproot_loader.so   proot's loader (static)
  libtalloc.so         talloc (its soname renamed to libtalloc.so)
  libandroid-shmem.so  System V shared memory for Android

The renames are done in place in the ELF string table (the new name is shorter and padded with NUL bytes), so no offset
moves. Run it again only to move to newer packages: update the pins below. Sources: github.com/termux/proot,
github.com/termux/termux-packages (licenses in app/src/main/assets/legal).
"""
import hashlib, io, os, subprocess, sys, tarfile, tempfile, urllib.request

REPO = "https://packages.termux.dev/apt/termux-main/"
PACKAGES = {
    "proot": ("pool/main/p/proot/proot_5.1.107.102_aarch64.deb", "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"),
    "libtalloc": ("pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb", "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"),
    "libandroid-shmem": ("pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"),
}
PREFIX = "data/data/com.termux/files/usr/"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "jniLibs", "arm64-v8a")
WANTED = {  # package, path inside it -> library name, (old, new) string to rename
    ("proot", "bin/proot"): ("libproot.so", (b"libtalloc.so.2\x00", b"libtalloc.so\x00\x00\x00")),
    ("proot", "libexec/proot/loader"): ("libproot_loader.so", None),
    ("libtalloc", "lib/libtalloc.so.2.5.0"): ("libtalloc.so", (b"libtalloc.so.2\x00", b"libtalloc.so\x00\x00\x00")),
    ("libandroid-shmem", "lib/libandroid-shmem.so"): ("libandroid-shmem.so", None),
}


def deb_files(data):
    """The members of a .deb (an ar archive) and the files of its data.tar.*."""
    if not data.startswith(b"!<arch>\n"): raise SystemExit("not a .deb")
    at, members = 8, {}
    while at < len(data):
        header = data[at:at + 60]; name = header[:16].decode().strip().rstrip("/"); size = int(header[48:58].decode().strip())
        members[name] = data[at + 60:at + 60 + size]; at += 60 + size + (size & 1)
    payload = next(v for k, v in members.items() if k.startswith("data.tar"))
    if payload[:6] == b"\xfd7zXZ\x00":  # data.tar.xz
        payload = subprocess.run(["xz", "-dc"], input=payload, capture_output=True, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(payload)) as tar:
        return {m.name.lstrip("./"): tar.extractfile(m).read() for m in tar.getmembers() if m.isfile()}


def main():
    os.makedirs(OUT, exist_ok=True)
    for package, (path, digest) in PACKAGES.items():
        data = urllib.request.urlopen(REPO + path, timeout=60).read()
        if hashlib.sha256(data).hexdigest() != digest: raise SystemExit(f"{package}: SHA-256 does not match the pin")
        files = deb_files(data)
        for (owner, inside), (library, rename) in WANTED.items():
            if owner != package: continue
            body = files[PREFIX + inside]
            if rename:
                old, new = rename
                if body.count(old) != 1: raise SystemExit(f"{library}: expected one {old!r}")
                body = body.replace(old, new)
            with open(os.path.join(OUT, library), "wb") as out: out.write(body)
            print(f"{library}: {len(body)} bytes, sha256 {hashlib.sha256(body).hexdigest()}")


if __name__ == "__main__":
    sys.exit(main())
