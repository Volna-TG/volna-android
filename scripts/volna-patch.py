#!/usr/bin/env python3
import argparse
import base64
import getpass
import hashlib
import json
import re
import struct
from pathlib import Path
from zipfile import ZipFile

MAGIC = b"VLP1"
APK_LIMIT = 128 * 1024 * 1024
PATCH_LIMIT = 16 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def compressed_entries(path, data):
    with ZipFile(path) as archive:
        for entry in archive.infolist():
            offset = entry.header_offset
            if data[offset:offset + 4] != b"PK\x03\x04":
                raise ValueError("invalid APK ZIP entry")
            name_size, extra = struct.unpack_from("<HH", data, offset + 26)
            start = offset + 30 + name_size + extra
            end = start + entry.compress_size
            if end > len(data):
                raise ValueError("invalid APK ZIP range")
            yield entry.filename, start, end


def generate(source, target, old, new):
    entries = {name: (start, end) for name, start, end in compressed_entries(source, old)}
    reusable = []
    for name, start, end in compressed_entries(target, new):
        previous = entries.get(name)
        if previous is None or end - start < 1024:
            continue
        before, after = previous
        if new[start:end] == old[before:after]:
            reusable.append((start, end, before))
    commands = [MAGIC, struct.pack(">Q", len(new))]
    last = 0
    for start, end, before in sorted(reusable):
        if start < last:
            raise ValueError("overlapping APK entries")
        if start > last:
            commands.extend((struct.pack(">BI", 1, start - last), new[last:start]))
        commands.append(struct.pack(">BQI", 0, before, end - start))
        last = end
    if last < len(new):
        commands.extend((struct.pack(">BI", 1, len(new) - last), new[last:]))
    patch = b"".join(commands)
    if len(patch) > PATCH_LIMIT or len(patch) >= len(new):
        raise ValueError("patch is too large")
    return patch


def apply(old, patch):
    if len(patch) < 13 or len(patch) > PATCH_LIMIT or patch[:4] != MAGIC:
        raise ValueError("invalid patch")
    total = struct.unpack_from(">Q", patch, 4)[0]
    if not 0 < total <= APK_LIMIT:
        raise ValueError("invalid APK size")
    out = bytearray()
    cursor = 12
    while len(out) < total:
        op = patch[cursor]
        cursor += 1
        if op == 0:
            offset, length = struct.unpack_from(">QI", patch, cursor)
            cursor += 12
            if offset + length > len(old):
                raise ValueError("invalid source range")
            data = old[offset:offset + length]
        elif op == 1:
            length = struct.unpack_from(">I", patch, cursor)[0]
            cursor += 4
            data = patch[cursor:cursor + length]
            cursor += length
        else:
            raise ValueError("invalid patch command")
        if not length or len(data) != length or len(out) + length > total:
            raise ValueError("invalid patch length")
        out.extend(data)
    if cursor != len(patch):
        raise ValueError("unexpected trailing patch data")
    return out


def publish(args):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec, rsa, padding
    from cryptography.hazmat.primitives.serialization.pkcs12 import load_key_and_certificates

    if not re.fullmatch(r"[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}", args.version) or args.code <= 0:
        raise ValueError("invalid release version")
    new = args.target.read_bytes()
    if not 0 < len(new) <= APK_LIMIT:
        raise ValueError("invalid APK size")
    payload = {"schema": 1, "version": args.version, "versionCode": args.code,
               "apkSha256": digest(new), "apkSize": len(new), "patches": {}}
    args.out.mkdir(parents=True, exist_ok=True)
    for source in args.base:
        old = source.read_bytes()
        if not 0 < len(old) <= APK_LIMIT:
            raise ValueError("invalid base APK")
        base_hash = digest(old)
        patch = generate(source, args.target, old, new)
        if apply(old, patch) != new:
            raise ValueError("patch output mismatch")
        name = f"{base_hash[:12]}-{payload['apkSha256'][:12]}.vlp"
        (args.out / name).write_bytes(patch)
        payload["patches"][base_hash] = {"file": name, "sha256": digest(patch), "size": len(patch)}
    raw = json.dumps(payload, separators=(",", ":")).encode()
    key, _, _ = load_key_and_certificates(args.key.read_bytes(), getpass.getpass("APK keystore password: ").encode())
    data = b"volna-app-update-v1\0" + raw
    if isinstance(key, rsa.RSAPrivateKey):
        signature = key.sign(data, padding.PKCS1v15(), hashes.SHA256())
    elif isinstance(key, ec.EllipticCurvePrivateKey):
        signature = key.sign(data, ec.ECDSA(hashes.SHA256()))
    else:
        raise ValueError("unsupported APK signer")
    (args.out / "release.json").write_text(json.dumps({
        "payload": base64.b64encode(raw).decode(),
        "signature": base64.b64encode(signature).decode(),
    }, separators=(",", ":")))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    make = sub.add_parser("publish")
    make.add_argument("--version", required=True)
    make.add_argument("--code", type=int, required=True)
    for option in ("target", "key", "out"):
        make.add_argument("--" + option, type=Path, required=True)
    make.add_argument("--base", type=Path, action="append", required=True)
    restore = sub.add_parser("apply")
    for option in ("base", "patch", "out"):
        restore.add_argument("--" + option, type=Path, required=True)
    args = parser.parse_args()
    if args.command == "publish":
        publish(args)
    else:
        args.out.write_bytes(apply(args.base.read_bytes(), args.patch.read_bytes()))


if __name__ == "__main__":
    main()
