# BruDre Streaming (Android TV)

App de streaming para Android TV, otimizado para o Xiaomi Mi TV Stick (MDZ-24-AA, 1 GB de RAM). Experiência no estilo Netflix: catálogo automático, pesquisa, Minha Lista, Continuar Assistindo e reprodução com um clique, por streaming direto ou BitTorrent.

Instalação na TV: veja [INSTALL.md](INSTALL.md).

## Fontes de conteúdo (gratuitas, gerenciadas pelo BruDre Source Manager)

| Fonte | O que fornece | Transporte |
|---|---|---|
| Internet Archive (`feature_films`, `classic_tv`, `classic_cartoons`, `prelinger` e outras) | Catálogo, capas, sinopses, vídeos MP4/H.264. Só domínio público ou Creative Commons | HTTP direto e `.torrent` oficial (web seed) |
| Índice curado (`app/src/main/assets/authorized_sources.json`) | Filmes abertos publicados pelos titulares (Blender Foundation) | HTTP + `.torrent`/magnet oficiais |
| TMDB | **Só metadados** (capas, sinopses em português, classificação). Clássicos até 1930 são procurados no Internet Archive | — |
| iptv-org (`countries/br`, `languages/por`, `categories/*`) | Canais de TV abertos, filtrados (sem pagos, adultos ou http) | HLS |
| Listas M3U do usuário | Canais adicionais gratuitos e autorizados | HLS (https) |

O app não tem serviços pagos, não integra indexadores de pirataria e não contorna DRM. Capa e sinopse não implicam disponibilidade: o botão **Assistir** só aparece quando a fonte foi verificada. Detalhes das mudanças em [CHANGELOG.md](CHANGELOG.md).

## Arquitetura

```
app/src/main/java/com/brudre/streaming/
├── BruDreApp.kt              Application + Services (dependências, cache de imagens, memória baixa)
├── data/
│   ├── Models.kt             Title, Playable, TorrentRef, SubtitleRef
│   ├── ArchiveParser.kt      JSON do Internet Archive → títulos/episódios (funções puras, testadas)
│   ├── CatalogRepository.kt  Linhas, pesquisa e detalhes; cache em memória + Room (6 h)
│   ├── CuratedCatalog.kt     Índice local de fontes autorizadas
│   ├── LibraryRepository.kt  Minha Lista, progresso, histórico
│   ├── AppSettings.kt        Preferências
│   └── db/Database.kt        Room
├── torrent/
│   ├── TorrentEngine.kt      Sessão libtorrent (libtorrent4j), sob demanda
│   ├── TorrentStream.kt      Prioridade de peças (deadlines), espera por peça, estatísticas
│   ├── TorrentDataSource.kt  DataSource do Media3 lendo o arquivo parcial + roteador torrent:// / https://
│   ├── PieceMath.kt          Posição no arquivo ↔ peças (testado)
│   └── CacheManager.kt       Limite e limpeza do armazenamento temporário
├── sources/
│   ├── SourceProvider.kt     Interface ContentProvider, estados, TitleMatcher (catálogo → acervo)
│   ├── Providers.kt          Internet Archive, filmes abertos, TMDB (metadados)
│   ├── SourceManager.kt      Busca unificada, detalhes, verificação, isolamento de falhas, StatusBoard
│   ├── GuardedHttp.kt        Interceptor/DNS de segurança e StreamValidator
│   └── UrlGuard.kt           Regras de URL (esquemas, destinos locais, executáveis)
├── live/
│   ├── M3uParser.kt          Parser M3U, ChannelPolicy (filtros), LiveCategories
│   └── LiveRepository.kt     Listas iptv-org e do usuário, cache, favoritos, canais fora do ar
├── player/
│   ├── PlayerActivity.kt     ExoPlayer (HTTP, HLS, torrent), troca de fonte, reconexão ao vivo, controle remoto
│   └── PlaybackPlan.kt       Ordem de fontes + mensagens de erro
└── ui/                       Jetpack Compose para TV (foco por D-pad), telas e tema
```

### Streaming BitTorrent

1. Baixa o `.torrent` oficial (ou resolve o magnet via DHT).
2. Seleciona apenas o arquivo de vídeo (`Priority.IGNORE` nos demais) e ativa download sequencial.
3. O `TorrentDataSource` traduz cada leitura do ExoPlayer em peças, define *deadlines* para a janela à frente (~12 MB) e bloqueia até a peça estar verificada no disco.
4. Em seek, os prazos antigos são descartados e a nova região é priorizada. O índice MP4 no fim do arquivo é buscado no início.
5. Ao sair do player, o torrent é removido com os dados, e a sessão é encerrada se não houver outro stream.

### Otimizações para 1 GB de RAM

- Cache de imagens limitado a 12% da memória, RGB565 e disco de 48 MB.
- Buffer do player de 15 a 40 s, com teto de 24 MB.
- Listas virtualizadas (`LazyRow`/`LazyColumn`). Sombras e animações só no item focado.
- Libtorrent: 40 conexões (ajustável), fila de disco de 4 MB, sessão encerrada fora do player.
- `onTrimMemory` libera imagens e caches de catálogo.

## Compilar

Requisitos: JDK 17 e Android SDK 35 (o Android Studio já traz os dois).

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # testes JVM
./gradlew connectedDebugAndroidTest  # testes no emulador/aparelho (usam a internet)
```

O GitHub Actions (`.github/workflows/android.yml`) compila, roda os testes unitários e instrumentados em emulador e publica o APK como release a cada push na `main`.

A assinatura fica em `keystore/`. É fixa para que as atualizações instalem por cima sem apagar os dados. Para uso doméstico isso basta; para distribuir publicamente, gere uma chave própria e guarde-a fora do repositório.

## Licenças

- libtorrent4j: MIT. libtorrent: BSD.
- Media3/ExoPlayer, AndroidX, OkHttp e Coil: Apache 2.0.
- Conteúdo: licença de cada obra, exibida na tela de detalhes.
