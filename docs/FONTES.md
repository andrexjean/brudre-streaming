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


## OpenSearch (9.1)

Cadastre o endereço da **descrição OpenSearch** (XML). O BruDre usa o modelo `<Url>` que responde **Atom ou RSS**
(resultados com `enclosure` de vídeo, magnet ou `.torrent`); modelos só HTML são recusados. Marcadores:
`{searchTerms}`, `{startPage?}`, `{startIndex?}`, `{count?}`. Exemplo: `samples/opensearch/descricao.xml`.

## API REST por descritor (9.1)

Para APIs JSON documentadas (ex.: um servidor de mídia próprio), cadastre o endereço de um descritor:

```json
{"format": "brudre-adapter", "version": 1, "name": "Meu servidor",
 "search": {"url": "https://host/api/busca?q={query}&p={page}", "items": "data.results", "hasMore": "data.has_more"},
 "lookup": {"url": "https://host/api/tmdb?id={tmdb}"},
 "fields": {"id": "uid", "title": "name", "year": "released", "tmdb": "ids.tmdb", "kind": "type",
            "season": "season", "episode": "episode", "series": "show", "language": "lang"},
 "kinds": {"movie": "MOVIE", "episode": "SERIES"},
 "media": [{"type": "http", "url": "{files.mp4}"}, {"type": "magnet", "url": "{magnet}"}],
 "headers": {"X-Api-Key": "…"}, "rateLimitPerMinute": 30}
```

- Caminhos com ponto (`ids.tmdb`).
- `{campo}` nos modelos é trocado pelo valor do item.
- Só https.
- Cabeçalhos permitidos (autenticação): `Authorization`, `X-Api-Key`, `X-Emby-Token`, `X-MediaBrowser-Token`, `X-Plex-Token`. Com cabeçalhos, redirecionamentos não são seguidos (o token não vai para outro servidor).
- Todos os itens passam pelas mesmas validações do catálogo JSON.

## Paginação (9.1)

- Catálogo JSON: `"next": "https://…/pagina-2.json"`.
- RSS/Atom: `<link rel="next" href="…"/>`.
- API de pesquisa: `{page}`, `{offset}`, `{limit}` (50 por página) e, na resposta, `"more": true|false` (opcional).
- Cada página é carregada só quando pedida, até 1.500 títulos por catálogo e 300 resultados na Pesquisa.

## Duplicadas e validação (9.1)

- O mesmo endereço (com variação de maiúsculas, barra final ou ordem de parâmetros) ou o mesmo hash de magnet é recusado como duplicado.
- Fonte salva sem validar fica desativada. O teste identifica o formato do zero. Se o formato bater e passar nas regras de segurança e de estrutura, ela fica "VALIDADA — ATIVE" e você decide quando ativar.


## Torznab e Newznab (9.2)

Cadastre o endpoint do seu indexador, por exemplo `https://indexador.exemplo/api` ou `…/torznab/`; o `/api` é descoberto sozinho.
- A chave pode ir no campo "Credencial" ou colada no endereço (`?apikey=…`). Nos dois casos ela fica cifrada no aparelho e nunca aparece na exportação.
- O teste de cadastro lê `t=caps` e faz uma pesquisa real.

| Recurso | Suporte |
|---|---|
| `t=caps` (funções, parâmetros, categorias, limites) | sim |
| `t=search` (texto) | sim |
| `t=movie` com `tmdbid`/`imdbid` | sim, quando o indexador declara |
| `t=tvsearch` com `season` (+ `tmdbid`) | sim, quando o indexador declara |
| `cat` | filmes 2000, TV 5000, documentários 5080; adulto 6000 sempre bloqueado |
| `offset`/`limit` | sim; limite = menor entre 50 e o máximo do caps |
| Atributos lidos | `magneturl`, `infohash`, `seeders`, `peers`, `size`, `season`, `episode`, `tmdbid`, `category`, `year` |
| Erros `<error code>` | 100–199 credencial, 200–299 parâmetro, 500–599 limite de uso |
| NZB (Newznab/Usenet) | descartado: o app não tem cliente NNTP |

**Restrições do protocolo × restrições de provedor.** O adaptador é neutro: funciona com qualquer endpoint compatível, desde que HTTPS. Para a rede local, o certificado precisa estar confirmado. O que pode ou não ser usado depende do provedor e do acervo. Isso está documentado na Biblioteca, não no protocolo.

## Servidores locais (9.2)

Para usar um servidor da sua rede (Jackett, Prowlarr, Jellyfin, catálogo próprio):
1. Ligue HTTPS no servidor. O Prowlarr tem opção nativa; o Jackett funciona atrás de um proxy reverso, como o Caddy. Certificado autoassinado serve.
2. Em Fontes → Adicionar, digite `https://192.168.x.y:porta/…`. Aparece o quadro **Servidor local**.
3. **Ler certificado**: confira a impressão digital SHA-256 com a do servidor e escolha **Confirmo: é o meu servidor**.
4. Salve. A partir daí, só esse certificado é aceito naquele host:porta. Se o certificado mudar, a conexão é recusada até você confirmar de novo.

Continua bloqueado: HTTP sem criptografia, o próprio aparelho (127.0.0.1) e endereços link-local.

## Biblioteca e matriz de avaliação (9.2)

`app/src/main/assets/source_library.json` (formato `brudre-biblioteca`) lista serviços e protocolos e diz o estado de cada um.
- Estados possíveis: integrado, só teste do motor, não aplicável, avaliado sem API, encerrado, não integrado.
- Cada provedor avaliado traz disponibilidade, API/feed, adaptador compatível, condição para integração autorizada e o teste realizado.
- "Adicionar" só existe para fontes com acervo autorizado. O cadastro passa pelas mesmas validações de qualquer fonte.

### Correspondência com a especificação (interfaces)
| Especificação | Implementação |
|---|---|
| IndexerProvider / IndexerAdapterFactory | `SearchAdapter` + `Adapters.forSource` |
| IndexerDescriptor | `UserSource` (+ `RestDescriptor`, `OpenSearch`, `TorznabCaps` em `config`) |
| IndexerCapabilities | `SourceCapabilities` |
| IndexerSearchRequest / Result | `SearchTemplate.Query` / `SearchHit` + `SearchPage` |
| IndexerHealth | `SourceTest` + `SourceStats` (falhas, tempo de resposta) |
| IndexerRegistry | `IndexerRegistry` (biblioteca) + `SourceStore` (fontes do usuário) |

## Homologação do aparelho (9.2)

Em Fontes → Diagnóstico → **Homologação do aparelho**:
- Use o app normalmente: abrir, assistir filmes e episódios por HTTP e BitTorrent, e fazer uma sessão longa.
- Marque o D-pad.
- Toque em **Gerar relatório**. O arquivo `brudre-homologacao.txt` fica na pasta do app (Android/data/com.brudre.streaming/files) e pode ser compartilhado.


## Credenciais nos endereços (9.2.43)

Chaves, tokens e senhas nunca ficam gravados no endereço:
- Ao salvar uma fonte cujo endereço tem `token=`, `password=`, `apikey=`, `passkey=` etc., o valor vai cifrado para o aparelho (Android Keystore). No endereço gravado e exportado fica `{cred}`.
- Links de download devolvidos pelo indexador (`/dl/…?jackett_apikey=…`) recebem o mesmo marcador.
- Na hora da requisição, o app troca `{cred}` pelo valor real, só em HTTPS e só para o mesmo host:porta da fonte. Um redirecionamento para outro servidor não recebe o segredo.
- Limite conhecido: segredo no **caminho** do endereço (ex.: `/live/usuario/senha/1.m3u8`) não tem como ser referenciado com segurança. Nas listas em cache, ele é mantido no armazenamento privado do app; em links de indexador, é mascarado.

## Diagnóstico por etapas (9.2.43)

| Etapa | O que comprova |
|---|---|
| Cadastrada | Configuração salva, sem teste |
| Acessível | O servidor respondeu (mesmo que a pesquisa venha vazia ou com erro de autenticação) |
| Pesquisa funcional | O protocolo devolveu resultados válidos (com aviso "conteúdo indisponível" se nenhum tem mídia utilizável) |
| Reprodução validada | Um vídeo da fonte começou a tocar no player |
