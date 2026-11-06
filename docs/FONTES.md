# BruDre — Formatos de fontes externas

Em **Configurações → Gerenciar fontes → Adicionar fonte**, cole o endereço ou o magnet. O BruDre lê os primeiros bytes, identifica o formato, testa a fonte e só salva se ela estiver válida. Tudo é feito pelo controle remoto, sem computador e sem recompilar o app.

| Formato | Como é reconhecido | Onde aparece |
|---|---|---|
| Magnet link | `magnet:?xt=urn:btih:…` com hash válido | Início → "Minhas fontes (links)" |
| Arquivo .torrent | conteúdo bencode (`d…4:info…`) ou `application/x-bittorrent` | Minhas fontes (links) |
| Vídeo HTTP/HTTPS | assinatura MP4 (`ftyp`), MKV/WebM (EBML), MPEG-TS ou `video/*` | Minhas fontes (links) |
| HLS / M3U8 | `#EXTM3U` com `#EXT-X-STREAM-INF` ou `#EXT-X-TARGETDURATION` | Minhas fontes (links) |
| Playlist IPTV M3U | `#EXTM3U` com entradas `#EXTINF` | TV ao Vivo → "Minha lista" |
| Catálogo BruDre (JSON) | objeto JSON com a lista `"items"` (abaixo) | Início (uma linha por catálogo), Pesquisa e detalhes |
| Feed RSS | `<rss>`/`<channel>` com `<enclosure>` de vídeo ou .torrent | Início, Pesquisa |

O app recusa páginas web, executáveis (APK, EXE, ELF, DEX, ZIP), endereços locais, `http://` sem criptografia e formatos desconhecidos. Nenhum endereço é tratado como catálogo sem antes passar pela validação.

## Catálogo JSON do BruDre

```json
{
  "name": "Nome do catálogo",
  "items": [
    {
      "id": "identificador-unico",
      "title": "Nome do filme ou série",
      "year": "2010",
      "kind": "MOVIE",
      "synopsis": "Sinopse",
      "poster": "https://…/capa.jpg",
      "genres": ["Animação"],
      "license": "CC BY 3.0",
      "sources": [
        {"type": "http", "url": "https://…/video.mp4"},
        {"type": "hls", "url": "https://…/index.m3u8"},
        {"type": "magnet", "url": "magnet:?xt=urn:btih:…", "file": "Video.mp4"},
        {"type": "torrent", "url": "https://…/video.torrent", "file": "Video.mp4"}
      ],
      "subtitles": [{"lang": "pt", "label": "Português", "url": "https://…/legenda.srt"}],
      "episodes": [
        {"season": 1, "number": 1, "title": "Episódio 1", "sources": [{"type": "hls", "url": "https://…"}]}
      ]
    }
  ]
}
```

- `kind` aceita `MOVIE`, `SERIES` ou `KIDS`. Um título com `episodes` vira série.
- Todas as fontes de um título são combinadas automaticamente. A ordem de reprodução é HTTP/HLS → endereços alternativos → BitTorrent, e o torrent baixa só as partes necessárias para começar a tocar.
- Títulos sem capa recebem capa e sinopse do catálogo (TMDB) quando nome e ano coincidem. Títulos do catálogo do app com o mesmo nome e ano ganham o botão Assistir usando a fonte cadastrada.
- Limites: 2 MB e 500 títulos por catálogo. Itens adultos ou com fontes inseguras são descartados.

Exemplos prontos: [`samples/feeds/blender-open-movies.json`](../samples/feeds/blender-open-movies.json) e [`samples/feeds/blender-open-movies.rss`](../samples/feeds/blender-open-movies.rss) (obras abertas da Blender Foundation).

## Prioridade, testes e cópia de segurança

- **Subir/Descer** muda a prioridade. Quando a mesma obra existe em várias fontes, vale a de cima.
- **Testar** confere a conexão e a estrutura e guarda o resultado com data e hora. **Atualizar** descarta o cache e baixa a fonte de novo.
- **Exportar** grava `brudre-fontes.json` na pasta do app (`Android/data/com.brudre.streaming/files/`). **Importar** lê esse arquivo ou um endereço `https://` com o mesmo formato:

```json
{"format": "brudre-fontes", "version": 1, "sources": [
  {"name": "Filmes abertos", "url": "https://…/catalogo.json", "type": "CATALOG_JSON", "enabled": true, "priority": 0}
]}
```

As fontes ficam salvas no aparelho e continuam lá depois de reiniciar.

Use apenas fontes com conteúdo de distribuição autorizada. O BruDre não inclui nem recomenda indexadores de pirataria.


## API de pesquisa do BruDre (9.0)

Para fontes autorizadas com busca própria. Cadastre o endereço com marcadores; o BruDre troca os marcadores
pelos dados do título e espera **JSON no mesmo formato do catálogo** (`{"items": [...]}`, pode vir vazio).

```
https://minha-fonte.org/api/busca?q={query}&ano={year}&tmdb={tmdb}
https://minha-fonte.org/busca/{slug}.json
```

| Marcador | Valor |
|---|---|
| `{query}` | texto pesquisado (codificado) |
| `{slug}` | texto em minúsculas com hífens (`auto-da-compadecida`) |
| `{tmdb}` | `movie:603` ou `tv:1399`, quando o título vem do catálogo |
| `{year}` `{type}` `{season}` `{page}` | ano, `movie`/`tv`, temporada, página (1) |

Regras: só `https://`; o servidor não pode depender do texto pesquisado; resposta até 512 KB e 50 resultados;
12 s por fonte, até 3 fontes ao mesmo tempo; páginas HTML e RSS são recusadas.

## Campos opcionais dos itens (9.0)

| Campo | Uso |
|---|---|
| `"tmdb": "movie:10378"` (ou número + `"kind"`) | associação exata com o título do catálogo |
| `"language": "en"` | idioma original do áudio |
| `"audio": ["en", "pt"]` | idiomas de áudio disponíveis (com `pt` → selo PT-BR) |
| `"subtitles": [{"lang": "pt", "url": "https://…/x.srt"}]` | legendas (com `pt` → selo LEG PT) |

## Torrents do próprio usuário (9.0)

Não precisam de catálogo: Configurações → Gerenciar fontes → **BitTorrent** → "Colar magnet ou .torrent" /
"Importar arquivo .torrent", ou "Abrir com"/"Compartilhar" a partir de outro app. Torrents com vários vídeos
mostram a lista de episódios; só o vídeo escolhido é baixado. Legendas `.srt/.vtt/.ass` do torrent são carregadas.
Magnet com `xs=https://…/arquivo.torrent` baixa o `.torrent` quando ainda não há participantes (o hash é conferido).
Use apenas conteúdo com distribuição autorizada.
