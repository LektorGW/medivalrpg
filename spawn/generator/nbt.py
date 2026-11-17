# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Minimal NBT (Java edition, big endian) writer and reader with gzip support.

Values are plain Python objects wrapped in small typed classes where the NBT
type is ambiguous (Byte/Short/Int/Long/...). dict -> Compound, str -> String.
"""
from __future__ import annotations

import gzip
import io
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class _Num:
    __slots__ = ("v",)

    def __init__(self, v):
        self.v = v

    def __eq__(self, o):
        return type(self) is type(o) and self.v == o.v

    def __hash__(self):
        return hash((type(self).__name__, self.v))

    def __repr__(self):
        return f"{type(self).__name__}({self.v!r})"


class Byte(_Num): pass
class Short(_Num): pass
class Int(_Num): pass
class Long(_Num): pass
class Float(_Num): pass
class Double(_Num): pass


class ByteArray(bytes):
    pass


class IntArray(list):
    pass


class LongArray(list):
    pass


class List(list):
    """Typed NBT list. ``elem_type`` is required for empty lists."""

    def __init__(self, items=(), elem_type=None):
        super().__init__(items)
        self.elem_type = elem_type


def _type_of(v) -> int:
    if isinstance(v, Byte): return BYTE
    if isinstance(v, Short): return SHORT
    if isinstance(v, Int): return INT
    if isinstance(v, Long): return LONG
    if isinstance(v, Float): return FLOAT
    if isinstance(v, Double): return DOUBLE
    if isinstance(v, ByteArray): return BYTE_ARRAY
    if isinstance(v, str): return STRING
    if isinstance(v, IntArray): return INT_ARRAY
    if isinstance(v, LongArray): return LONG_ARRAY
    if isinstance(v, List): return LIST
    if isinstance(v, dict): return COMPOUND
    raise TypeError(f"Unsupported NBT value: {v!r} ({type(v).__name__})")


def _w_str(out, s: str):
    b = s.encode("utf-8")  # NBT uses modified UTF-8; identical for BMP chars without NUL
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def _w_payload(out, t: int, v):
    if t == BYTE: out.write(struct.pack(">b", v.v))
    elif t == SHORT: out.write(struct.pack(">h", v.v))
    elif t == INT: out.write(struct.pack(">i", v.v))
    elif t == LONG: out.write(struct.pack(">q", v.v))
    elif t == FLOAT: out.write(struct.pack(">f", v.v))
    elif t == DOUBLE: out.write(struct.pack(">d", v.v))
    elif t == BYTE_ARRAY:
        out.write(struct.pack(">i", len(v))); out.write(bytes(v))
    elif t == STRING: _w_str(out, v)
    elif t == INT_ARRAY:
        out.write(struct.pack(">i", len(v))); out.write(struct.pack(f">{len(v)}i", *v))
    elif t == LONG_ARRAY:
        out.write(struct.pack(">i", len(v))); out.write(struct.pack(f">{len(v)}q", *v))
    elif t == LIST:
        et = v.elem_type if v.elem_type is not None else (_type_of(v[0]) if v else END)
        for item in v:
            if _type_of(item) != et:
                raise TypeError(f"Heterogeneous NBT list: {v!r}")
        out.write(struct.pack(">bi", et, len(v)))
        for item in v:
            _w_payload(out, et, item)
    elif t == COMPOUND:
        for k, item in v.items():
            it = _type_of(item)
            out.write(struct.pack(">b", it)); _w_str(out, k); _w_payload(out, it, item)
        out.write(b"\x00")
    else:
        raise TypeError(t)


def write(path: str, root: dict, root_name: str = ""):
    buf = io.BytesIO()
    buf.write(struct.pack(">b", COMPOUND)); _w_str(buf, root_name); _w_payload(buf, COMPOUND, root)
    # mtime=0 keeps the file byte-for-byte reproducible
    with open(path, "wb") as f:
        with gzip.GzipFile(fileobj=f, mode="wb", mtime=0) as gz:
            gz.write(buf.getvalue())


class _Reader:
    def __init__(self, data: bytes):
        self.d = data; self.p = 0

    def take(self, n):
        b = self.d[self.p:self.p + n]
        if len(b) != n:
            raise ValueError("Truncated NBT")
        self.p += n
        return b

    def u(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def s(self):
        return self.take(self.u(">H")).decode("utf-8")

    def payload(self, t):
        if t == BYTE: return Byte(self.u(">b"))
        if t == SHORT: return Short(self.u(">h"))
        if t == INT: return Int(self.u(">i"))
        if t == LONG: return Long(self.u(">q"))
        if t == FLOAT: return Float(self.u(">f"))
        if t == DOUBLE: return Double(self.u(">d"))
        if t == BYTE_ARRAY: return ByteArray(self.take(self.u(">i")))
        if t == STRING: return self.s()
        if t == INT_ARRAY:
            n = self.u(">i"); return IntArray(struct.unpack(f">{n}i", self.take(4 * n)))
        if t == LONG_ARRAY:
            n = self.u(">i"); return LongArray(struct.unpack(f">{n}q", self.take(8 * n)))
        if t == LIST:
            et = self.u(">b"); n = self.u(">i")
            return List([self.payload(et) for _ in range(n)], elem_type=et)
        if t == COMPOUND:
            out = {}
            while True:
                it = self.u(">b")
                if it == END:
                    return out
                k = self.s()
                out[k] = self.payload(it)
        raise ValueError(f"Bad NBT tag type {t}")


def read(path: str):
    """Returns (root_name, root_compound). Accepts gzip or raw NBT."""
    with open(path, "rb") as f:
        raw = f.read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    r = _Reader(raw)
    t = r.u(">b")
    if t != COMPOUND:
        raise ValueError("Root tag is not a compound")
    name = r.s()
    root = r.payload(COMPOUND)
    if r.p != len(raw):
        raise ValueError("Trailing bytes after NBT root")
    return name, root


def encode_varints(values) -> bytes:
    out = bytearray()
    for v in values:
        while True:
            b = v & 0x7F
            v >>= 7
            if v:
                out.append(b | 0x80)
            else:
                out.append(b)
                break
    return bytes(out)


def decode_varints(data: bytes):
    vals, cur, shift = [], 0, 0
    for b in data:
        cur |= (b & 0x7F) << shift
        if b & 0x80:
            shift += 7
            if shift > 35:
                raise ValueError("VarInt too long")
        else:
            vals.append(cur); cur = 0; shift = 0
    if shift:
        raise ValueError("Truncated VarInt")
    return vals
