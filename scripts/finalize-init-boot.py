#!/usr/bin/env python3
"""Finalize and check a release init_boot image so that it matches the stock shape.

When signing target-files, releasetools rebuilds IMAGES/init_boot.img from the signed
ramdisk. Two things then differ from the stock image, and this tool restores them:

* releasetools passes mkbootimg_init_args before mkbootimg_version_args
  (common.py _BuildBootableImage), so the platform --os_version/--os_patch_level
  override the explicit zeros from BOARD_MKBOOTIMG_INIT_ARGS. The Make build uses the
  opposite order. The stock header carries zeros; only these four bytes are rewritten.
* BOARD_AVB_ENABLE is false, so nothing adds a footer. The stock image carries a hash
  footer with algorithm NONE and the stock salt and properties, and the bootloader reads
  AVB metadata, so the same footer is added here.

The stock values come from the init_boot section of config/device/<device>.json.
``finalize`` writes a new image and never overwrites; ``check`` validates a finalized
image, optionally against the release fingerprint. Works offline only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

CONFIG = Path(__file__).resolve().parents[1] / "config/device"
HEADER_SIZE_V4 = 1584
HEADER_OS_FIELD = slice(16, 20)
LZ4_LEGACY_MAGIC = bytes.fromhex("02214c18")

# Contents of the generic ramdisk (generic_ramdisk.mk) that this tool expects.
BINARIES = ("init", "system/bin/snapuserd_ramdisk", "system/bin/toolbox_ramdisk")
REGULAR = {*BINARIES, "system/etc/init/snapuserd.rc", "system/etc/ramdisk/build.prop"}
LINKS = {
    **{
        f"system/bin/{name}": "toolbox_ramdisk"
        for name in ("getevent", "getprop", "modprobe", "setprop", "start", "stop")
    },
    "system/bin/snapuserd": "snapuserd_ramdisk",
}
RELEASE_FINGERPRINT = re.compile(
    r"(?P<brand>[^/]+)/[^/]+/(?P<device>[^/:]+):(?P<release>[^/]+)/[^/:]+/"
    r"(?P<incremental>[0-9]+):(?P<type>user|userdebug)/release-keys"
)


@dataclass(frozen=True)
class InitBoot:
    partition_size: int
    page_size: int
    header_version: int
    salt: str
    properties: dict[str, str]


def load_init_boot(device: str) -> InitBoot:
    section = json.loads((CONFIG / f"{device}.json").read_text())["init_boot"]
    return InitBoot(
        partition_size=section["partition_size"],
        page_size=section["page_size"],
        header_version=section["header_version"],
        salt=section["avb"]["salt"],
        properties=section["avb"]["properties"],
    )


def require(ok: object, message: str) -> None:
    if not ok:
        raise ValueError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def header(spec: InitBoot, data: bytes, any_os_version: bool = False) -> dict[str, int]:
    """Parse the header of a kernel-less init_boot image with an empty command line."""
    page = spec.page_size
    require(len(data) >= page and data[:8] == b"ANDROID!", "Invalid boot image")
    kernel, ramdisk_size, os_version, header_size = struct.unpack_from("<4I", data, 8)
    version = struct.unpack_from("<I", data, 40)[0]
    require(
        (kernel, header_size, version) == (0, HEADER_SIZE_V4, spec.header_version),
        f"Expected header v{spec.header_version}, no kernel, header size {HEADER_SIZE_V4}",
    )
    require(any_os_version or os_version == 0, "Expected zero header OS/SPL")
    require(
        not any(data[24:40]) and not any(data[44:1580]),
        "Nonempty reserved/cmdline fields",
    )
    require(struct.unpack_from("<I", data, 1580)[0] == 0, "Unexpected boot signature")
    require(
        0 < ramdisk_size <= spec.partition_size - page
        and len(data) >= page + ramdisk_size,
        "Ramdisk exceeds image",
    )
    require(data[page : page + 4] == LZ4_LEGACY_MAGIC, "Expected legacy LZ4 ramdisk")
    return {
        "version": version,
        "kernel_size": kernel,
        "ramdisk_size": ramdisk_size,
        "os_version_patch_packed": os_version,
        "header_size": header_size,
    }


def cpio(data: bytes) -> dict[str, dict]:
    """Parse a newc archive, rejecting unsafe paths and duplicate files."""
    entries: dict[str, dict] = {}
    offset = 0
    while True:
        require(
            offset + 110 <= len(data) and data[offset : offset + 6] == b"070701",
            "Invalid CPIO header",
        )
        fields = [
            int(data[offset + 6 + i * 8 : offset + 14 + i * 8], 16) for i in range(13)
        ]
        mode, uid, gid, size, name_size = (
            fields[1],
            fields[2],
            fields[3],
            fields[6],
            fields[11],
        )
        offset += 110
        require(
            0 < name_size <= 4096 and offset + name_size <= len(data),
            "Invalid CPIO name",
        )
        require(data[offset + name_size - 1] == 0, "Unterminated CPIO name")
        name = data[offset : offset + name_size - 1].decode()
        require(
            not name.startswith("/") and ".." not in name.split("/"), "Unsafe CPIO path"
        )
        offset = (offset + name_size + 3) & ~3
        require(offset + size <= len(data), "Truncated CPIO payload")
        content = data[offset : offset + size]
        offset = (offset + size + 3) & ~3
        if name == "TRAILER!!!":
            require(
                size == 0 and not any(data[offset:]), "Unexpected trailing CPIO data"
            )
            return entries
        require(
            name not in entries
            or (stat.S_ISDIR(mode) and stat.S_ISDIR(entries[name]["mode"])),
            f"Duplicate non-directory CPIO entry: {name}",
        )
        entries[name] = {
            "mode": mode,
            "uid": uid,
            "gid": gid,
            "data": content,
            "size": size,
            "sha256": digest(content),
        }


def check_static_elf(data: bytes) -> None:
    """Ramdisk executables run before any linker is available."""
    require(
        len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01",
        "Expected ELF64 little endian",
    )
    kind, machine = struct.unpack_from("<HH", data, 16)
    require(kind in (2, 3) and machine == 183, "Expected AArch64 executable")
    table = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    require(
        entry_size == 56
        and 0 < count < 100
        and table + entry_size * count <= len(data),
        "Invalid ELF program headers",
    )
    for index in range(count):
        offset = table + index * entry_size
        segment = struct.unpack_from("<I", data, offset)[0]
        require(segment != 3, "Ramdisk executable has PT_INTERP")
        if segment == 2:
            dynamic = struct.unpack_from("<Q", data, offset + 8)[0]
            dynamic_size = struct.unpack_from("<Q", data, offset + 32)[0]
            require(
                dynamic_size % 16 == 0 and dynamic + dynamic_size <= len(data),
                "Invalid ELF dynamic table",
            )
            for position in range(dynamic, dynamic + dynamic_size, 16):
                tag = struct.unpack_from("<Q", data, position)[0]
                if tag == 0:
                    break
                require(tag != 1, "Ramdisk executable has DT_NEEDED")


def check_identity(props: dict[str, str], fingerprint: str) -> None:
    """The ramdisk must describe the same release as the signed system."""
    match = RELEASE_FINGERPRINT.fullmatch(fingerprint)
    if match is None:
        raise ValueError(f"Unexpected release fingerprint: {fingerprint}")
    expected = {
        "ro.bootimage.build.fingerprint": fingerprint,
        "ro.product.bootimage.device": match["device"],
        "ro.product.bootimage.brand": match["brand"],
        "ro.bootimage.build.version.incremental": match["incremental"],
        "ro.bootimage.build.version.release": match["release"],
        "ro.bootimage.build.type": match["type"],
    }
    wrong = sorted(key for key, value in expected.items() if props.get(key) != value)
    require(not wrong, "Ramdisk identity differs from the release: " + ", ".join(wrong))


def inspect(
    spec: InitBoot,
    data: bytes,
    lz4: Path,
    fingerprint: str | None = None,
    any_os_version: bool = False,
) -> dict:
    parsed = header(spec, data, any_os_version)
    with tempfile.TemporaryDirectory(prefix="init-boot-inspect.") as tmp:
        compressed, archive = Path(tmp) / "ramdisk.lz4", Path(tmp) / "ramdisk.cpio"
        start = spec.page_size
        compressed.write_bytes(data[start : start + parsed["ramdisk_size"]])
        subprocess.run(
            [str(lz4), "-d", str(compressed), str(archive)],
            check=True,
            capture_output=True,
        )
        require(
            archive.stat().st_size < 64 << 20, "Unexpected uncompressed ramdisk size"
        )
        entries = cpio(archive.read_bytes())
    files = {name for name, entry in entries.items() if stat.S_ISREG(entry["mode"])}
    require(files == REGULAR, f"Unexpected or missing regular files: {files ^ REGULAR}")
    links = {
        name: entry["data"].decode()
        for name, entry in entries.items()
        if stat.S_ISLNK(entry["mode"])
    }
    require(links == LINKS, "Ramdisk symlinks changed")
    for name in BINARIES:
        entry = entries[name]
        expected_mode = 0o750 if name == "init" else 0o755
        require(
            entry["mode"] & 0o7777 == expected_mode
            and entry["uid"] == entry["gid"] == 0,
            f"Incorrect executable mode or owner: {name}",
        )
        check_static_elf(entry["data"])
    props: dict[str, str] = {}
    for line in entries["system/etc/ramdisk/build.prop"]["data"].decode().splitlines():
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        require(key not in props, f"Duplicate property: {key}")
        props[key] = value
    if fingerprint:
        check_identity(props, fingerprint)
    index = {
        name: {key: value for key, value in entry.items() if key != "data"}
        for name, entry in entries.items()
    }
    return {"header": parsed, "properties": props, "entries": index, "symlinks": links}


def run(tool: Path, *args: object) -> str:
    result = subprocess.run(
        [str(tool), *map(str, args)], capture_output=True, text=True, check=True
    )
    return result.stdout + result.stderr


def add_footer(spec: InitBoot, image: Path, avbtool: Path) -> None:
    command: list[object] = [
        "add_hash_footer",
        "--image",
        image,
        "--partition_name",
        "init_boot",
        "--partition_size",
        spec.partition_size,
        "--algorithm",
        "NONE",
        "--salt",
        spec.salt,
    ]
    for key, value in spec.properties.items():
        command += ["--prop", f"{key}:{value}"]
    run(avbtool, *command)


def check_footer(spec: InitBoot, image: Path, avbtool: Path) -> str:
    data = image.read_bytes()
    require(
        len(data) == spec.partition_size and data[-64:-60] == b"AVBf",
        "Invalid full-partition image or footer",
    )
    info = run(avbtool, "info_image", "--image", image)
    # verify_image also hashes <dir>/<partition>.img next to its input, which in a
    # release images/ directory is the raw signer output, so check an isolated copy.
    with tempfile.TemporaryDirectory(prefix="init-boot-verify.") as tmp:
        isolated = Path(tmp) / "init_boot.img"
        isolated.write_bytes(data)
        run(avbtool, "verify_image", "--image", isolated)
    require(
        re.search(r"^Algorithm:\s+NONE$", info, re.MULTILINE),
        "Unexpected AVB algorithm",
    )
    require(
        re.search(rf"^\s+Salt:\s+{spec.salt}$", info, re.MULTILINE),
        "Unexpected hash descriptor salt",
    )
    for key, value in spec.properties.items():
        require(
            f"Prop: {key} -> '{value}'" in info, f"Missing stock AVB property: {key}"
        )
    return info


def check_final(
    spec: InitBoot,
    image: Path,
    lz4: Path,
    avbtool: Path,
    fingerprint: str | None = None,
) -> dict:
    """Validate a finalized image and return the inspection report."""
    info = check_footer(spec, image, avbtool)
    data = image.read_bytes()
    return {
        "sha256": digest(data),
        "size": spec.partition_size,
        "inspection": inspect(spec, data, lz4, fingerprint),
        "avb_properties": spec.properties,
        "signature_algorithm": "NONE",
        "avb_info": info,
    }


def normalize_header(spec: InitBoot, raw: bytes) -> tuple[bytes, int]:
    """Zero the packed OS/SPL word; every other byte stays as the signer wrote it."""
    parsed = header(spec, raw, any_os_version=True)
    page = spec.page_size
    padded = (page + parsed["ramdisk_size"] + page - 1) // page * page
    require(
        len(raw) == padded and raw[-64:-60] != b"AVBf",
        "Expected a raw signer image without footer or trailing data",
    )
    normalized = raw[: HEADER_OS_FIELD.start] + bytes(4) + raw[HEADER_OS_FIELD.stop :]
    return normalized, parsed["os_version_patch_packed"]


def finalize(spec: InitBoot, args: argparse.Namespace) -> None:
    require(
        not args.out.exists() and not args.json.exists(),
        "Refusing to overwrite outputs",
    )
    raw = args.raw.read_bytes()
    inspect(spec, raw, args.lz4, args.fingerprint, any_os_version=True)
    normalized, original_os = normalize_header(spec, raw)
    pending = args.out.with_name(args.out.name + ".pending")
    require(not pending.exists(), f"Stale pending image: {pending}")
    pending.write_bytes(normalized)
    add_footer(spec, pending, args.avbtool)
    require(
        pending.read_bytes()[: len(normalized)] == normalized,
        "Adding the footer changed the image content",
    )
    report = check_final(spec, pending, args.lz4, args.avbtool, args.fingerprint)
    pending.rename(args.out)
    report.update(
        raw_sha256=digest(raw),
        signer_header_os_version_patch_packed=original_os,
        fingerprint=args.fingerprint,
    )
    args.json.write_text(json.dumps(report, indent=2) + "\n")
    summary = {key: report[key] for key in ("sha256", "raw_sha256", "fingerprint")}
    print(json.dumps(summary, indent=2))


def check(spec: InitBoot, args: argparse.Namespace) -> None:
    report = check_final(spec, args.image, args.lz4, args.avbtool, args.fingerprint)
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + "\n")
    print(
        json.dumps(
            {"sha256": report["sha256"], "status": "finalized_init_boot_ok"}, indent=2
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Finalize or check a release init_boot image."
    )
    parser.add_argument("--device", required=True, help="device name, such as NX809J")
    modes = parser.add_subparsers(dest="mode", required=True)
    final = modes.add_parser(
        "finalize", help="zero the header OS/SPL and add the stock footer"
    )
    final.add_argument(
        "--raw",
        type=Path,
        required=True,
        help="IMAGES/init_boot.img of signed target-files",
    )
    final.add_argument("--out", type=Path, required=True)
    final.add_argument("--json", type=Path, required=True)
    final.add_argument(
        "--fingerprint", required=True, help="release system fingerprint"
    )
    final.set_defaults(func=finalize)
    checker = modes.add_parser("check", help="validate a finalized image")
    checker.add_argument("--image", type=Path, required=True)
    checker.add_argument("--fingerprint")
    checker.add_argument("--json", type=Path)
    checker.set_defaults(func=check)
    for mode in (final, checker):
        mode.add_argument("--lz4", type=Path, required=True)
        mode.add_argument("--avbtool", type=Path, required=True)
    args = parser.parse_args()
    try:
        args.func(load_init_boot(args.device), args)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
