#!/usr/bin/env python3
"""
Servidor Torznab/Newznab de REFERÊNCIA do BruDre (só para testes no CI).

Implementa o protocolo publicado (Torznab 1.3: t=caps, search, tvsearch, movie; offset/limit; apikey;
<error code>) com um acervo AUTORIZADO e fixo:
  - a série sintética do próprio projeto (samples/test-torrent, uso livre), servida por este servidor em /dl/;
  - "Night of the Living Dead" (1968, domínio público) no Internet Archive.
Inclui de propósito itens que o app deve DESCARTAR: NZB (Usenet), categoria adulta (6000), software (4000),
sem semeadores. Não consulta nenhum indexador de terceiros.

Uso: torznab_server.py --port 8443 --apikey CHAVE [--cert c.pem --key k.pem] [--torrent arquivo.torrent]
"""
import argparse
import html
import os
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse, quote

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_HASH = "bf16e86e7636315da5c443296bee3dd58c051039"
LIMIT_MAX = 3

# (título, categorias, atributos extras, tipo da mídia: "dl" (este servidor) | url | "nzb" | None)
CATALOG = [
    ("BruDre.Teste.Serie.S01E01.720p.WEB", [5000, 5030], {"season": "1", "episode": "1", "seeders": "3", "peers": "4"}, "dl"),
    ("BruDre.Teste.Serie.S01E02.720p.WEB", [5000, 5030], {"season": "1", "episode": "2", "seeders": "2", "peers": "2"}, "dl"),
    ("BruDre.Teste.Serie.S02E01.720p.WEB", [5000, 5030], {"season": "2", "episode": "1", "seeders": "1", "peers": "1"}, "dl"),
    ("BruDre.Teste.Serie.S01E01.NZB", [5000], {"season": "1", "episode": "1", "grabs": "1"}, "nzb"),
    ("BruDre Teste Adulto", [6000], {"seeders": "9"}, "dl"),
    ("BruDre.Teste.Serie.S01E03.720p", [5000], {"season": "1", "episode": "3", "seeders": "0", "peers": "0"}, "dl"),
    ("BruDre Teste Software 1.0", [4000], {"seeders": "5"}, "dl"),
    ("BruDre.Teste.Serie.S02E01.HASH", [5000], {"season": "2", "episode": "1", "seeders": "1", "infohash": TEST_HASH}, None),
    ("Night.of.the.Living.Dead.1968.480p.Internet.Archive", [2000, 2030],
     {"seeders": "20", "peers": "25", "tmdbid": "10331", "imdb": "0063350", "year": "1968"},
     "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_archive.torrent"),
]

CAPS = """<?xml version="1.0" encoding="UTF-8"?>
<caps>
  <server version="1.3" title="BruDre Torznab de referencia (CI)" />
  <limits max="{max}" default="{max}" />
  <searching>
    <search available="yes" supportedParams="q" />
    <tv-search available="yes" supportedParams="q,season,ep,tmdbid" />
    <movie-search available="yes" supportedParams="q,imdbid,tmdbid" />
  </searching>
  <categories>
    <category id="2000" name="Movies"><subcat id="2030" name="Movies/SD" /></category>
    <category id="5000" name="TV"><subcat id="5030" name="TV/SD" /><subcat id="5080" name="TV/Documentary" /></category>
    <category id="4000" name="PC" />
    <category id="6000" name="XXX" />
  </categories>
</caps>
"""


def error(code, desc):
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<error code="{code}" description="{html.escape(desc)}" />\n'


def matches(title, attrs, cats, q):
    p = {k: v[0] for k, v in q.items()}
    words = [w for w in p.get("q", "").lower().replace(".", " ").split() if w]
    name = title.lower().replace(".", " ")
    if any(w not in name for w in words):
        return False
    t = p.get("t")
    if t == "tvsearch" and not any(c // 1000 == 5 for c in cats):
        return False
    if t == "movie" and not any(c // 1000 == 2 for c in cats):
        return False
    if "season" in p and attrs.get("season") and attrs["season"] != p["season"]:
        return False
    if "ep" in p and attrs.get("episode") and attrs["episode"] != p["ep"]:
        return False
    if "tmdbid" in p and attrs.get("tmdbid") and attrs["tmdbid"] != p["tmdbid"]:
        return False
    if "cat" in p:
        wanted = {int(x) for x in p["cat"].split(",") if x.isdigit()}
        if not any(c in wanted or (c // 1000) * 1000 in wanted for c in cats):
            return False
    return True


class Handler(BaseHTTPRequestHandler):
    apikey = ""
    torrent = b""

    def log_message(self, fmt, *a):
        # Nunca registra a apikey.
        sys.stderr.write("%s %s\n" % (self.command, self.path.split("apikey=")[0]))

    def send(self, code, body, ctype="application/rss+xml; charset=utf-8"):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def base(self):
        host = self.headers.get("Host", "localhost")
        return "https://" + host

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/dl/brudre-teste-serie.torrent":
            # Como o Jackett: o link de download carrega a chave; sem ela, 401 (prova que o app a resolve na hora)
            if q.get("jackett_apikey", [""])[0] != self.apikey:
                return self.send(401, b"unauthorized", "text/plain")
            return self.send(200, self.torrent, "application/x-bittorrent")
        if u.path == "/announce":
            # Tracker HTTPS mínimo (BEP 3/23) para o teste do anúncio pelo app: 2 participantes de exemplo (TEST-NET-3)
            if "info_hash" not in q or "peer_id" not in q:
                return self.send(200, b"d14:failure reason17:missing info_hashe", "text/plain")
            peers = bytes([203, 0, 113, 5, 0x1A, 0xE1, 203, 0, 113, 6, 0xC8, 0xD5])
            body = b"d8:completei3e10:incompletei1e8:intervali1800e5:peers12:" + peers + b"e"
            return self.send(200, body, "text/plain")
        if u.path.endswith(".nzb"):
            return self.send(200, b"<nzb/>", "application/x-nzb")
        if u.path not in ("/api", "/torznab/api", "/vazio/api", "/semvideo/api"):
            return self.send(404, error(202, "Use /api"), "application/xml")
        if q.get("apikey", [""])[0] != self.apikey:
            return self.send(200, error(100, "Incorrect user credentials"), "application/xml")
        t = q.get("t", [""])[0]
        if t == "caps":
            return self.send(200, CAPS.format(max=LIMIT_MAX), "application/xml")
        if t not in ("search", "tvsearch", "movie"):
            return self.send(200, error(202, "No such function (%s)" % t), "application/xml")
        try:
            offset = int(q.get("offset", ["0"])[0])
            limit = min(int(q.get("limit", [str(LIMIT_MAX)])[0]), LIMIT_MAX)
        except ValueError:
            return self.send(200, error(201, "Incorrect parameter"), "application/xml")
        found = [c for c in CATALOG if matches(c[0], c[2], c[1], q)]
        # Cenários de diagnóstico: /vazio = protocolo certo sem resultados; /semvideo = só itens não reproduzíveis
        if u.path.startswith("/vazio"):
            found = []
        elif u.path.startswith("/semvideo"):
            found = [c for c in CATALOG if c[3] == "nzb" or any(x // 1000 in (4, 6) for x in c[1])]
        page = found[offset:offset + limit]
        items = []
        for i, (title, cats, attrs, media) in enumerate(page):
            guid = "brudre-ci-%d-%s" % (offset + i, quote(title))
            link = enclosure = ""
            if media == "dl":
                link = self.base() + "/dl/brudre-teste-serie.torrent?jackett_apikey=" + quote(self.apikey)
                enclosure = '<enclosure url="%s" length="%d" type="application/x-bittorrent" />' % (html.escape(link), len(self.torrent))
            elif media == "nzb":
                link = self.base() + "/dl/brudre.nzb"
                enclosure = '<enclosure url="%s" length="100" type="application/x-nzb" />' % html.escape(link)
            elif media:
                link = media
                enclosure = '<enclosure url="%s" length="0" type="application/x-bittorrent" />' % html.escape(link)
            a = "".join('<torznab:attr name="category" value="%d" />' % c for c in cats)
            a += "".join('<torznab:attr name="%s" value="%s" />' % (k, html.escape(v)) for k, v in attrs.items())
            items.append(
                "<item><title>%s</title><guid>%s</guid>%s<comments>https://github.com/andrexjean/brudre-streaming</comments>"
                "<size>455866</size><description>Conteudo autorizado de teste do BruDre</description>%s%s%s</item>"
                % (html.escape(title), guid, ("<link>%s</link>" % html.escape(link)) if link else "",
                   "".join("<category>%d</category>" % c for c in cats), enclosure, a))
        body = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:torznab="http://torznab.com/schemas/2015/feed">'
            "<channel><title>BruDre Torznab CI</title><description>Acervo autorizado de teste</description>"
            '<torznab:response offset="%d" total="%d" />%s</channel></rss>\n' % (offset, len(found), "".join(items))
        )
        return self.send(200, body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8443)
    ap.add_argument("--apikey", required=True)
    ap.add_argument("--cert")
    ap.add_argument("--key")
    ap.add_argument("--torrent", default=os.path.join(ROOT, "samples", "test-torrent", "brudre-teste-serie.torrent"))
    args = ap.parse_args()
    Handler.apikey = args.apikey
    with open(args.torrent, "rb") as f:
        Handler.torrent = f.read()
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    if args.cert:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(args.cert, args.key)
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    print("Torznab de referência em :%d (%s)" % (args.port, "TLS" if args.cert else "HTTP p/ túnel"), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
