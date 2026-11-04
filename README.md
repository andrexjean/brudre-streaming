# BruDre Streaming (Android TV)

App de streaming para Android TV, otimizado para o Xiaomi Mi TV Stick (MDZ-24-AA, 1 GB de RAM). Experiência no estilo Netflix: catálogo automático, pesquisa, Minha Lista, Continuar Assistindo e reprodução com um clique, por streaming direto ou BitTorrent.

Instalação na TV: veja [INSTALL.md](INSTALL.md).

## Fontes de conteúdo (somente autorizadas)

| Fonte | O que fornece | Transporte |
|---|---|---|
| Internet Archive (coleções `feature_films`, `classic_tv`, `classic_cartoons` e outras) | Catálogo, capas, sinopses, vídeos MP4/H.264 | HTTP direto e `.torrent` oficial do acervo (com web seed) |
| Índice curado (`app/src/main/assets/authorized_sources.json`) | Filmes abertos publicados pelos titulares (Blender Foundation) | `.torrent`/magnet oficiais + HTTP |

O app não integra indexadores de terceiros nem contorna DRM. Capa e sinopse não implicam disponibilidade: o botão **Assistir** só aparece quando existe uma fonte reproduzível.

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
├── player/
│   ├── PlayerActivity.kt     ExoPlayer, troca automática de fonte, retomada, controle remoto
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
