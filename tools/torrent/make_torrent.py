#!/usr/bin/env python3
"""Cria um .torrent multiarquivo (BEP 3) com web seed (BEP 19) para a série de teste do BruDre.
Uso: make_torrent.py <pasta> <url_base_web_seed> <saida.torrent>"""
import hashlib, os, sys

def benc(v):
    if isinstance(v, int): return b"i%de" % v
    if isinstance(v, str): v = v.encode()
    if isinstance(v, bytes): return b"%d:%s" % (len(v), v)
    if isinstance(v, list): return b"l" + b"".join(benc(x) for x in v) + b"e"
    if isinstance(v, dict):
        return b"d" + b"".join(benc(k) + benc(v[k]) for k in sorted(v, key=lambda k: k.encode() if isinstance(k, str) else k)) + b"e"
    raise TypeError(type(v))

def main(folder, web, out):
    piece = 64 * 1024
    files = []
    for root, _, names in os.walk(folder):
        for n in sorted(names):
            p = os.path.join(root, n)
            files.append(os.path.relpath(p, folder).split(os.sep))
    files.sort()
    data, hashes, flist = b"", b"", []
    for parts in files:
        with open(os.path.join(folder, *parts), "rb") as f: blob = f.read()
        flist.append({"length": len(blob), "path": parts})
        data += blob
    for i in range(0, len(data), piece): hashes += hashlib.sha1(data[i:i + piece]).digest()
    info = {"name": os.path.basename(os.path.normpath(folder)), "piece length": piece, "pieces": hashes, "files": flist}
    t = {"announce": "udp://tracker.opentrackr.org:1337/announce", "url-list": [web], "created by": "BruDre tools", "info": info,
         "comment": "Série sintética de teste do BruDre Streaming (uso livre)"}
    raw = benc(t)
    open(out, "wb").write(raw)
    print("infohash", hashlib.sha1(benc(info)).hexdigest(), "files", len(flist), "bytes", len(raw))

if __name__ == "__main__": main(*sys.argv[1:4])
