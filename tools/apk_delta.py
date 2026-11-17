"""Bounded, lossless APK delta: reuse unchanged ZIP compressed payloads, never resign.

Gzip-decoded wire format (all integers unsigned big endian):
GYEOTD1\\n, base SHA-256 (32 bytes), target SHA-256 (32 bytes), target size u64;
then COPY (0, source offset u64, length u64), DATA (1, length u64, bytes), END (2).
No bytes may follow END. APKs and patches are capped at 512 MiB; at most 100000
operations and ZIP entries. Payloads are copied as bytes, never extracted/executed.
The caller must independently trust the expected target hash and APK signature.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import time
import zipfile
import zlib

MAGIC = b'GYEOTD1\n'
MAX_APK = 512 * 1024 * 1024
MAX_OPERATIONS = 100000
CHUNK = 65536
TIMEOUT = 120


def _size(path):
    size = Path(path).stat().st_size
    if not 0 < size <= MAX_APK:
        raise ValueError('File size outside supported range')
    return size


def digest(path):
    with open(path, 'rb') as source:
        return hashlib.file_digest(source, 'sha256').digest()


def _read(source, length):
    data = source.read(length)
    if len(data) != length:
        raise ValueError('Truncated delta or APK')
    return data


def _u64(source):
    return struct.unpack('>Q', _read(source, 8))[0]


def _guard(deadline, cancelled):
    if time.monotonic() > deadline:
        raise TimeoutError('APK delta deadline exceeded')
    if cancelled and cancelled():
        raise InterruptedError('APK delta cancelled')


def _transfer(source, target, length, deadline, cancelled=None, hasher=None):
    while length:
        _guard(deadline, cancelled)
        data = _read(source, min(CHUNK, length))
        target.write(data)
        if hasher is not None:
            hasher.update(data)
        length -= len(data)


def _payloads(path, deadline):
    """Validate ZIP ranges, hashing compressed bytes without inflating entries."""
    total = _size(path)
    entries = {}
    spans = []
    with zipfile.ZipFile(path) as archive, open(path, 'rb') as source:
        infos = archive.infolist()
        if len(infos) > MAX_OPERATIONS:
            raise ValueError('Too many ZIP entries')
        for entry in infos:
            _guard(deadline, None)
            if entry.filename in entries or entry.flag_bits & 1:
                raise ValueError('Duplicate or encrypted ZIP entry')
            if entry.header_offset < 0 or entry.header_offset + 30 > archive.start_dir:
                raise ValueError('Invalid ZIP local header range')
            source.seek(entry.header_offset)
            header = _read(source, 30)
            if header[:4] != b'PK\x03\x04':
                raise ValueError('Invalid ZIP local header')
            flags, method = struct.unpack_from('<HH', header, 6)
            name_length, extra_length = struct.unpack_from('<HH', header, 26)
            if flags != entry.flag_bits or method != entry.compress_type:
                raise ValueError('ZIP header mismatch')
            name = _read(source, name_length).decode('utf-8' if flags & 0x800 else 'cp437')
            if name != entry.orig_filename:
                raise ValueError('ZIP name mismatch')
            start = entry.header_offset + 30 + name_length + extra_length
            end = start + entry.compress_size
            if start > end or end > min(total, archive.start_dir):
                raise ValueError('Invalid ZIP payload range')
            spans.append((entry.header_offset, end))
            source.seek(start)
            hasher = hashlib.sha256()
            remaining = entry.compress_size
            while remaining:
                _guard(deadline, None)
                data = _read(source, min(CHUNK, remaining))
                hasher.update(data)
                remaining -= len(data)
            entries[entry.filename] = (start, entry.compress_size, hasher.digest())
        spans.sort()
        if any(right[0] < left[1] for left, right in zip(spans, spans[1:])):
            raise ValueError('Overlapping ZIP entry ranges')
    return entries


def _temporary(destination, forbidden):
    destination = Path(destination)
    if destination.resolve() in {Path(p).resolve() for p in forbidden}:
        raise ValueError('Output must not overwrite an input')
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.' + destination.name + '.', dir=destination.parent)
    return os.fdopen(fd, 'wb'), Path(name)


def create(base, target, patch, timeout=TIMEOUT):
    deadline = time.monotonic() + timeout
    _size(base)
    target_size = _size(target)
    old = _payloads(base, deadline)
    new = _payloads(target, deadline)
    ranges = []
    for name, (offset, size, checksum) in new.items():
        previous = old.get(name)
        if size and previous and previous[1:] == (size, checksum):
            ranges.append((offset, previous[0], size))
    ranges.sort()
    base_hash, target_hash = digest(base), digest(target)
    raw, temporary = _temporary(patch, (base, target))
    operations = reused = 0
    try:
        with raw, open(target, 'rb') as source:
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw, compresslevel=6, mtime=0) as out:
                out.write(MAGIC + base_hash + target_hash + struct.pack('>Q', target_size))
                position = 0
                for offset, base_offset, size in ranges + [(target_size, 0, 0)]:
                    _guard(deadline, None)
                    if offset > position:
                        operations += 1
                        out.write(b'\x01' + struct.pack('>Q', offset - position))
                        source.seek(position)
                        _transfer(source, out, offset - position, deadline)
                    if size:
                        operations += 1
                        out.write(b'\x00' + struct.pack('>QQ', base_offset, size))
                        reused += size
                    if operations > MAX_OPERATIONS:
                        raise ValueError('Too many delta operations')
                    position = offset + size
                out.write(b'\x02')
        _size(temporary)
        temporary.replace(patch)
    finally:
        temporary.unlink(missing_ok=True)
    return {'baseSha256': base_hash.hex(), 'targetSha256': target_hash.hex(),
            'targetSize': target_size, 'patchSha256': digest(patch).hex(),
            'patchSize': Path(patch).stat().st_size, 'reusedBytes': reused,
            'operations': operations, 'format': 'gyeot-delta-v1'}


class _GzipReader:
    """One bounded gzip member, no optional fields, concatenation or trailing data."""
    def __init__(self, path):
        self.source = open(path, 'rb')
        header = self.source.read(10)
        if len(header) != 10 or header[:4] != b'\x1f\x8b\x08\x00':
            self.source.close()
            raise ValueError('Unsupported gzip header')
        self.pending = header
        self.decoder = zlib.decompressobj(31)
        self.done = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.source.close()

    def read(self, length):
        result = bytearray()
        while len(result) < length and not self.done:
            if not self.pending:
                self.pending = self.source.read(CHUNK)
                if not self.pending:
                    raise ValueError('Truncated gzip member')
            part = self.decoder.decompress(self.pending, length - len(result))
            result.extend(part)
            self.pending = self.decoder.unconsumed_tail
            if self.decoder.eof:
                if self.decoder.unused_data or self.pending or self.source.read(1):
                    raise ValueError('Trailing gzip data or additional member')
                self.done = True
        return bytes(result)


def apply(base, patch, output, expected_target_sha256=None, timeout=TIMEOUT, cancelled=None):
    """Reference applier; the Android client also verifies package/signing identity."""
    deadline = time.monotonic() + timeout
    base_size = _size(base)
    _size(patch)
    base_hash = digest(base)
    _guard(deadline, cancelled)
    raw, temporary = _temporary(output, (base, patch))
    try:
        with raw, open(base, 'rb') as source, _GzipReader(patch) as delta:
            if _read(delta, 8) != MAGIC:
                raise ValueError('Unsupported delta format')
            if _read(delta, 32) != base_hash:
                raise ValueError('Wrong base APK')
            target_hash = _read(delta, 32)
            if expected_target_sha256 is not None and target_hash.hex() != expected_target_sha256.lower():
                raise ValueError('Untrusted target APK hash')
            target_size = _u64(delta)
            if not 0 < target_size <= MAX_APK:
                raise ValueError('Invalid target size')
            written = operations = 0
            hasher = hashlib.sha256()
            while True:
                _guard(deadline, cancelled)
                op = _read(delta, 1)[0]
                if op == 2:
                    break
                operations += 1
                if operations > MAX_OPERATIONS:
                    raise ValueError('Too many delta operations')
                if op == 0:
                    offset, length = _u64(delta), _u64(delta)
                    if offset > base_size or length > base_size - offset:
                        raise ValueError('COPY exceeds base APK')
                    source.seek(offset)
                    data_source = source
                elif op == 1:
                    length = _u64(delta)
                    data_source = delta
                else:
                    raise ValueError('Unknown delta operation')
                if not 0 < length <= target_size - written:
                    raise ValueError('Operation exceeds target size')
                _transfer(data_source, raw, length, deadline, cancelled, hasher)
                written += length
            if written != target_size or delta.read(1):
                raise ValueError('Wrong output length or trailing delta bytes')
            if hasher.digest() != target_hash:
                raise ValueError('Target APK checksum mismatch')
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return {'targetSha256': target_hash.hex(), 'targetSize': target_size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('create', 'apply'):
        p = sub.add_parser(command)
        p.add_argument('base', type=Path)
        p.add_argument('input', type=Path, help='Target APK for create; patch for apply')
        p.add_argument('output', type=Path)
    args = parser.parse_args()
    print(json.dumps((create if args.command == 'create' else apply)(args.base, args.input, args.output), sort_keys=True))


if __name__ == '__main__':
    main()
