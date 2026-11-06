#!/usr/bin/env python3
"""
ZenVault key tool — the tiny companion for anyone shipping their own build
of the ZenMusic library.

What it does
------------
  new      generate three fresh key fragments (A/B/C) and store them in a
           local fragments file (never commit that file!)
  kotlin   print the Kotlin `ZenVaultKey` snippet for your fragments
  seal     encrypt one secret into a `ZenVaultConsts` entry
  open     decrypt a sealed entry (round-trip check)
  print    show the fragments file

The vault key is derived exactly like the library does it:

    combined = A xor B xor C                  (32 bytes each)
    key      = SHA-256(combined + b"zenmusic.vault.v1")
    sealed   = base64( iv(12) + AES-256-GCM(key, iv, plaintext) + tag(16) )

Quick start
-----------
    pip install pycryptodome        # only needed for seal/open
    python3 tools/zenvault_keygen.py new
    python3 tools/zenvault_keygen.py kotlin
    python3 tools/zenvault_keygen.py seal it.host music.youtube.com
    python3 tools/zenvault_keygen.py open it.host "<paste the sealed string>"

Keep `zenvault_fragments.json` private (chmod 600, add it to .gitignore).
Losing the fragments means the sealed values can no longer be unsealed or
extended — rotate by re-sealing everything.
"""
import argparse
import base64
import hashlib
import json
import os
import secrets
import stat
import sys

DOMAIN = b"zenmusic.vault.v1"
FRAGMENTS_FILE = "zenvault_fragments.json"


def _load_fragments(path: str):
    if not os.path.exists(path):
        sys.exit(f"error: {path} not found — run `new` first")
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    try:
        return bytes.fromhex(data["A"]), bytes.fromhex(data["B"]), bytes.fromhex(data["C"])
    except KeyError as exc:
        sys.exit(f"error: {path} is missing {exc} (not a fragments file?)")


def derive_key(a: bytes, b: bytes, c: bytes) -> bytes:
    combined = bytes(x ^ y ^ z for x, y, z in zip(a, b, c))
    return hashlib.sha256(combined + DOMAIN).digest()


def _aes():
    try:
        from Crypto.Cipher import AES  # pycryptodome
        return AES
    except ImportError:
        sys.exit("error: `seal`/`open` need pycryptodome —  pip install pycryptodome")


def cmd_new(args):
    if os.path.exists(args.file) and not args.force:
        sys.exit(f"error: {args.file} already exists (use --force to overwrite)")
    frags = {"A": secrets.token_hex(32), "B": secrets.token_hex(32), "C": secrets.token_hex(32)}
    with open(args.file, "w", encoding="utf-8") as fh:
        json.dump(frags, fh, indent=2)
    os.chmod(args.file, stat.S_IRUSR | stat.S_IWUSR)  # 600
    key = derive_key(*(bytes.fromhex(v) for v in frags.values()))
    print(f"fragments written to {args.file} (mode 600)")
    print(f"vault key fingerprint: sha256:{hashlib.sha256(key).hexdigest()[:32]}")
    print("keep this file private — never commit it")


def cmd_kotlin(args):
    a, b, c = _load_fragments(args.file)
    hexa, hexb, hexc = a.hex(), b.hex(), c.hex()
    print("Replace the three fragment constants in")
    print("  zen-core/src/main/kotlin/com/zenmusic/core/vault/ZenVaultKey.kt\n")
    print(f'    private const val A = "{hexa}"')
    print()
    print(f'    private const val B = "{hexb}"')
    print()
    print(f'    private const val C = "{hexc}"')
    print()
    print("Then re-seal every ZenVaultConsts entry with `seal` (they are bound to this key).")


def cmd_seal(args):
    a, b, c = _load_fragments(args.file)
    key = derive_key(a, b, c)
    AES = _aes()
    iv = secrets.token_bytes(12)
    cipher = AES.new(key, AES.MODE_GCM, nonce=iv, mac_len=16)
    ct, tag = cipher.encrypt_and_digest(args.plaintext.encode("utf-8"))
    sealed = base64.b64encode(iv + ct + tag).decode("ascii")
    print(f'"{args.key_id}" to "{sealed}",')


def cmd_open(args):
    a, b, c = _load_fragments(args.file)
    key = derive_key(a, b, c)
    AES = _aes()
    raw = base64.b64decode(args.sealed)
    cipher = AES.new(key, AES.MODE_GCM, nonce=raw[:12], mac_len=16)
    try:
        plain = cipher.decrypt_and_verify(raw[12:-16], raw[-16:])
    except (ValueError, KeyError):
        sys.exit("error: wrong fragments (or corrupted value) — tag mismatch")
    print(plain.decode("utf-8"))


def cmd_print(args):
    a, b, c = _load_fragments(args.file)
    key = derive_key(a, b, c)
    for name, frag in (("A", a), ("B", b), ("C", c)):
        print(f"{name}: {frag.hex()}")
    print(f"vault key fingerprint: sha256:{hashlib.sha256(key).hexdigest()[:32]}")


def main():
    parser = argparse.ArgumentParser(description="ZenVault key tool")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("new", help="generate fresh fragments file")
    p.add_argument("--file", default=FRAGMENTS_FILE)
    p.add_argument("--force", action="store_true")
    p.set_defaults(fn=cmd_new)

    p = sub.add_parser("kotlin", help="print the Kotlin fragment snippet")
    p.add_argument("--file", default=FRAGMENTS_FILE)
    p.set_defaults(fn=cmd_kotlin)

    p = sub.add_parser("seal", help="seal one secret into a consts entry")
    p.add_argument("key_id", help="semantic id, e.g. it.host")
    p.add_argument("plaintext", help="the secret value")
    p.add_argument("--file", default=FRAGMENTS_FILE)
    p.set_defaults(fn=cmd_seal)

    p = sub.add_parser("open", help="decrypt a sealed value (round-trip check)")
    p.add_argument("key_id", help="semantic id (unused in crypto, for logging)")
    p.add_argument("sealed", help="the base64 sealed string")
    p.add_argument("--file", default=FRAGMENTS_FILE)
    p.set_defaults(fn=cmd_open)

    p = sub.add_parser("print", help="show fragments + key fingerprint")
    p.add_argument("--file", default=FRAGMENTS_FILE)
    p.set_defaults(fn=cmd_print)

    args = parser.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
