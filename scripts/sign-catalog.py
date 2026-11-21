import argparse
import base64
import getpass
import json
from pathlib import Path
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.serialization.pkcs12 import load_key_and_certificates


def sign(source, keystore, destination, password):
    catalog = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(catalog["revision"], int) or catalog["revision"] < 1:
        raise ValueError("Invalid catalog revision")
    text = json.dumps(catalog, separators=(",", ":"), ensure_ascii=False)
    key, _, _ = load_key_and_certificates(keystore.read_bytes(), password)
    message = b"volna-profile-catalog-v1\0" + text.encode()
    if isinstance(key, rsa.RSAPrivateKey):
        signature = key.sign(message, padding.PKCS1v15(), hashes.SHA256())
    else:
        signature = key.sign(message, ec.ECDSA(hashes.SHA256()))
    envelope = json.dumps({"catalog": text, "signature": base64.b64encode(signature).decode()}, ensure_ascii=False)
    if len(envelope.encode()) > 65536:
        raise ValueError("Catalog exceeds 64 KiB")
    destination.write_text(envelope, encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for option in ("source", "keystore", "destination"):
        parser.add_argument(option, type=Path)
    args = parser.parse_args()
    sign(args.source, args.keystore, args.destination, getpass.getpass("APK keystore password: ").encode())
