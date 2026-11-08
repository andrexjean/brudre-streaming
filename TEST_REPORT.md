# BruDre Streaming — Relatório de testes

## Versão 9.2.46 (pedida como 9.2.43) — 08/11/2026 — correções de segurança, diagnóstico e homologação

Release **v9.2.46** (versionCode 92046), commit `f29406b`. O número segue o build do CI: os builds 43 a 45 não passaram e não foram publicados.

| Tipo de teste | Resultado |
|---|---|
| **Simulados** (JVM, sem rede) | **117/117** (11 novos em `V943Test`) |
| **Rede real** (emulador Android 11 x86_64, internet) | **62/62** (5 novos em `V943DeviceTest` + `UiFlowTest.t12`). Na 1ª execução do build 46, `UiFlowTest.t05` falhou por tempo de interface (clique na aba antes de o painel fechar); passou na nova execução sem mudança de código |
| **Release instalado no emulador** | Abertura 0,49 s; 0 crashes; 127 MB de PSS no Início |
| **Físico** (Mi Stick) | **Não executado** |

| Teste de rede real | Resultado | Evidência |
|---|---|---|
| Credenciais fora do armazenamento | PASS | Links Torznab com `{cred}`; o link `/dl/` exige a chave (401 sem ela) e tocou S01E02; 664 arquivos do app e o logcat do processo varridos sem a chave; relatório e exportação limpos |
| Migração de registros antigos | PASS | Segredo do endereço movido para o CredentialStore; endereço com `{cred}` testado; histórico de torrent limpo |
| Diagnóstico por etapas | PASS | vazio → Acessível/sem resultados; só NZB → Pesquisa funcional/conteúdo indisponível; normal → Pesquisa funcional; chave errada → Acessível/autenticação; DNS e TLS → Cadastrada; rede privada → bloqueada |
| Reprodução validada | PASS | Só depois de um vídeo tocar pela fonte |
| Tracker HTTPS local (certificado fixado) | PASS | Anúncio pelo OkHttp: 2 participantes recebidos e entregues ao motor |
| Tracker com certificado vencido / host errado | PASS | Recusados com "Certificado de segurança inválido" |
| Tracker com falha de DNS | PASS | "Endereço não encontrado (DNS)" |
| Tracker UDP | PASS | opentrackr: 5 participantes |
| DHT | PASS | Ubuntu: 1006; sintético: 20 |
| Tracker HTTPS público (Ubuntu) | PARTIAL | Conexão TLS válida e resposta do tracker, mas recusa: "Requested download is not authorized for use with this tracker". Causa não determinada (provável regra de lista do próprio tracker) |
| HTTPS interno do libtorrent | Falha comprovada | "init fail (BIO routines)" em todo tracker HTTPS, inclusive com certificado válido → por isso o anúncio passou para o OkHttp |
| Memória (3 rodadas de teste e pesquisa) | PASS | PSS 216 → 220 MB |
| Painel pelo D-pad | PASS | Foco chega em "Testar fontes" e no teste individual; etapa "Pesquisa funcional" registrada |
| Internet Archive (descritor REST) | PASS | 49 resultados; tocou por BitTorrent em 9,4 s |
| Internet Archive via Torznab (NOTLD) | FAIL | Não passou de 3 s em 180 s (repetiu da 9.2.42) |
| Regressão 9.2.42 (Torznab local e público, fallback por duração, homologação, Wikimedia, Blender, distribuições) | PASS | Mesmas evidências da 9.2.42 |

## Versão 9.2 (v9.2.42) — 08/11/2026 — Source Engine 4.0

GitHub Actions, emulador Android 11 x86_64 (TV 1080p) com internet real. Commit `320713c`, release **v9.2.42**.
**Nada aqui foi executado no Mi Stick físico.**

| Ambiente | Resultado |
|---|---|
| Testes unitários (JVM) | **106/106** (13 novos em `V92Test`) |
| Emulador, internet real | **56/56** (6 novos em `V92DeviceTest`; `SourceEngineTest.t30` e `UiFlowTest.t10` ajustados à 9.2) |
| Release instalado no emulador | Abertura a frio 0,42 s, 0 crashes, 113 MB de PSS no Início |
| Mi Stick físico | **Não executado** |

| Teste real | Resultado | Evidência |
|---|---|---|
| Torznab, servidor local (TLS autoassinado, certificado fixado) | PASS | rede privada bloqueada sem confirmação; certificado errado recusado; apikey errada → erro 100; apikey colada no endereço vai cifrada; tvsearch + season; 2 páginas; NZB e sem semeadores descartados; S01E02 tocou o arquivo certo do pacote; fixação relida após reinício |
| Torznab, endpoint público (HTTPS válido, túnel) | PASS | `…/torznab/` → `/api` descoberto; filme por tmdbid; tvsearch season 2; reprodução em 4,5 s |
| Internet Archive (descritor REST + .torrent) | PARTIAL | execução 42485720804: 47 resultados, tocou por BitTorrent em 10 s; execução 42487960745: tempo esgotado no cadastro |
| Internet Archive via Torznab (NOTLD 1968) | FAIL | não passou de 3 s em 180 s (Archive lento nesta execução) |
| Wikimedia Commons (descritor REST) | PASS | 48 resultados; vídeo WebM tocou em 6,7 s |
| Blender Open Movies (catálogo) | PASS | 5 títulos |
| Debian, Ubuntu, Arch, NetBSD, Kali, AlmaLinux, Rocky | PASS | .torrent oficial HTTPS lido; participantes reais vistos (DHT/tracker), sem baixar arquivos |
| Fedora, openSUSE, FreeBSD, Manjaro, EndeavourOS, FOSS Torrents, Academic Torrents, Public Domain Torrents | PARTIAL | páginas responderam, mas sem link .torrent direto (o link fica em páginas internas ou só em HTTP) |
| Slackware, eTree | BLOCKED | HTTP 403 para cliente automatizado |
| Linux Mint | FAIL | página de torrents respondeu 404 |
| Fallback por duração | PASS | 30 s → 120 s: pausou e perguntou |
| Rotina de homologação | PASS | relatório gerado; no emulador ele se declara inválido como homologação |

## Versão 9.1 (v9.1.39) — 07/11/2026

Resultados separados por ambiente. **Nada aqui foi executado no Mi Stick físico.**

| Ambiente | Resultado |
|---|---|
| Testes unitários (JVM) | **93/93** (13 novos em `V91Test`) |
| Emulador Android 11 x86_64, TV 1080p, internet real (GitHub Actions) | **50/50** (9 novos em `V91DeviceTest` + `UiFlowTest.t10` ampliado) |
| Release instalado no emulador | Abre a frio em 0,64 s, 0 crashes, 110 MB PSS no Início |
| Mi Stick físico | **Não executado** (sem acesso ao aparelho) |

| # | Teste | Evidência (emulador) | Resultado |
|---|---|---|---|
| 1 | Certificado TLS válido | web seed do GitHub pelo OkHttp: episódio tocou | Aprovado |
| 2 | Certificado inválido/expirado | expired, wrong.host, self-signed, untrusted-root (badssl.com): recusados no OkHttp; como web seed, nenhuma peça aceita | Aprovado |
| 3 | Redirecionamento HTTPS | github.com → raw seguiu; → http:// e → .apk bloqueados no salto | Aprovado |
| 4 | Fonte indisponível | salva sem validar fica desativada; validada não ativa sozinha; pendente não pode ser ativada | Aprovado |
| 5 | Redetecção de formato | salva como JSON → "Atualizar" → RSS, mantendo nome e histórico | Aprovado |
| 6 | Pesquisa em várias fontes | OpenSearch (Atom) + REST (descritor) + API BruDre na mesma página | Aprovado |
| 7 | Paginação | página 2 só da fonte com mais; catálogo paginado 2 → 3 títulos sem repetir | Aprovado |
| 8 | Cancelamento de pesquisa | 2 ms | Aprovado |
| 9 | Associação de filmes | Alien × Aliens, Duna 1984 × 2021, TMDB diferente, filme × série (unit) | Aprovado |
| 10 | Temporadas e episódios | junção por temporada/episódio, série × filme (unit); episódios do torrent (t25) | Aprovado |
| 11 | Áudio e legenda | MKV com áudio pt+en e legenda pt embutida: lidos; troca para inglês salva como preferência | Aprovado |
| 12 | Retomada | parou em 15,6 s → retomou em 15,6 s | Aprovado |
| 13 | Falha durante a reprodução | vídeo truncado: tentou a mesma fonte, trocou para a completa em 17 s; todas falhando → para, sem ciclo | Aprovado |
| 14 | Sem peers | "Sem participantes" após 30 s | Aprovado |
| 15 | Cache | apagar ao fechar: 0 B; manter até o limite: 517 KB | Aprovado |
| 16 | Duplicadas | variação de maiúsculas/barra recusada; importação ignora repetida | Aprovado |
| 17 | Persistência | 7 preferências relidas por nova instância | Aprovado |
| 18 | D-pad | abas, BitTorrent, colar magnet, preferências e diagnóstico só pelas setas/OK | Aprovado |
| 19 | Memória e liberação | PSS (processo de teste) 224 → 213 → 210 MB; sessão BitTorrent encerrada; 0 streams | Aprovado (emulador) |
| 20 | Regressão v9.0.37 | todos os 41 testes anteriores | Aprovado |

## Versão 9.0 (v9.0.37) — 06/11/2026 — Source Engine 2.0

GitHub Actions, emulador Android 11 x86_64 (TV 1080p) **com internet real** (EUA). Commit `37737ac`, release **v9.0.37**.
Conteúdo de teste próprio: série sintética `samples/test-torrent` (3 episódios + legenda .srt, web seed HTTPS no GitHub),
API de pesquisa estática `samples/search` e catálogos de troca de fonte `samples/feeds/fallback-*.json`.

| Etapa | Resultado |
|---|---|
| Compilação debug + release (R8) | Aprovada — APK 15,9 MB, versionCode 90037 |
| Testes unitários | **80/80** (19 novos em `V9Test`) |
| Testes instrumentados | **41/41** (9 novos: `SourceEngineTest` t25–t31, `UiFlowTest` t10–t11) |
| Release instalado no emulador | Abre a frio em 0,54 s, 0 crashes, 131 MB PSS |
| Mi Stick físico | **Pendente** (fluidez no emulador sem GPU não é representativa) |

| # | Teste obrigatório | Evidência | Resultado |
|---|---|---|---|
| 1 | Magnet válido/inválido | magnet compartilhado → 3 vídeos listados (t27); `btih:123` → "Magnet inválido" | Aprovado |
| 2 | Importar .torrent | content:// ("Abrir com") e bytes → histórico, hash `bf16e86e…` | Aprovado |
| 3 | Reprodução progressiva | episódio de 445 KB tocou em ~5 s pelo web seed | Aprovado |
| 4 | Seleção de episódio | S01E02 escolhido: arquivo certo no motor; outros vídeos **não** gravados (0 bytes) | Aprovado |
| 5 | Legendas do torrent | `.pt-BR.srt` virou faixa de texto no player | Aprovado |
| 6 | Catálogo JSON | t24 (regressão) + campos tmdb/idioma (unit) | Aprovado |
| 7 | Pesquisa dinâmica | API `{slug}` → 1 resultado; aparece na Pesquisa e nos detalhes | Aprovado |
| 8 | Associação título ↔ resultado | por TMDB `movie:10378` e nome+ano | Aprovado |
| 9 | Várias fontes e prioridade | Big Buck Bunny: 4 fontes; nativas primeiro × minhas fontes primeiro | Aprovado |
| 10 | Falha de DNS | "Endereço não encontrado (DNS)…" + UnknownHostException nos detalhes | Aprovado |
| 11 | Timeout | 10.255.255.1 → "Tempo esgotado" em 10 s | Aprovado |
| 12 | Servidor indisponível / HTTP | 404 → "Arquivo não encontrado no servidor (HTTP 404)"; ConnectException → "Servidor indisponível" (unit) | Aprovado |
| 13 | Fonte offline | salva sem validar (desativada) → teste OK → ativada sozinha; fonte quebrada → player trocou para a próxima e gravou ✗1 / ✓1 | Aprovado |
| 14 | Retomada | parou em 15,7 s → retomou em 15,9 s | Aprovado |
| 15 | Cancelamento de pesquisa | cancelado em 1 ms; fonte lenta não segura as outras | Aprovado |
| 16 | Limpeza de cache | apagar ao fechar: 0 bytes; manter até o limite: 517 KB mantidos | Aprovado |
| 17 | D-pad | abas, BitTorrent, colar magnet, nativas e diagnóstico só com setas/OK | Aprovado |
| 18 | Regressão | todos os 32 testes da 8.1 aprovados | Aprovado |

Conteúdo do mundo: "Cinema Brasileiro" devolveu 20 filmes, todos com selo PT-BR; TV ao vivo do mundo separada em
"em português" (26 canais de filmes) e "outros idiomas" (80).

## Atualização 8.1 (v8.0.30) — 05/11/2026

Executado no GitHub Actions: testes unitários (JVM) e testes instrumentados em emulador Android 11 x86_64 (TV 1080p, 2 GB) **com internet real**, a partir dos EUA (alguns canais brasileiros bloqueiam acesso de fora do Brasil). Commits testados `5199d83` (v8.0.29) e `0f992bc` (**v8.0.30**, release atual: as duas rodadas passaram inteiras). Logs, capturas e relatórios nos branches `ci-build` e `ci-tests`.

| Etapa | Resultado |
|---|---|
| Compilação debug + release com R8 | Aprovada (APK caiu de 24,5 MB para **15,9 MB**) |
| Testes unitários | **61/61 aprovados** |
| Testes instrumentados | **32/32 aprovados** |
| APK release instalado e navegado pelo controle no emulador | Sem travamentos nem crashes; abre em 0,8 s (`am start -W`) |
| Validação no Mi Stick físico | **Pendente** (sem acesso ao aparelho) |

### Gerenciar fontes — testes obrigatórios (`PlaybackTest.t24`, `UiFlowTest.t09`)

| Teste | Como | Resultado |
|---|---|---|
| Cadastro de fonte autorizada | 7 fontes reais cadastradas: catálogo JSON e RSS (filmes abertos da Blender), HLS de teste da Mux, magnet e .torrent do Sintel, vídeo HTTPS do Sintel, playlist IPTV do Brasil | Aprovado |
| Identificação do formato | O formato detectado bateu com o esperado nas 7; página web (github.com), script de texto, `http://` e `file://` foram recusados sem salvar | Aprovado |
| Reprodução de um vídeo | Filme do catálogo cadastrado (HTTP) e link único HLS tocaram no player | Aprovado |
| Ativação e desativação | Catálogo desativado some das linhas e da pesquisa; reativado volta | Aprovado |
| Prioridade | "Subir" duas vezes leva a fonte ao topo | Aprovado |
| Persistência após reinicialização | Configuração gravada em disco (`shared_prefs/brudre_sources.xml`) e relida por uma nova instância com a mesma ordem e estado | Aprovado |
| Exportar / importar | Exportado para `Android/data/com.brudre.streaming/files/brudre-fontes.json`, tudo apagado e reimportado com as mesmas 7 fontes | Aprovado |
| Exclusão | Fonte excluída some do armazenamento e do catálogo | Aprovado |
| Tela pelo controle remoto | `UiFlowTest.t09` abre Configurações → Gerenciar fontes e o diálogo de adicionar (capturas `gerenciar_fontes*.png`) | Aprovado |

Também: `t22` (catálogo externo: Sintel por HTTP e HLS, Sintel só magnet, Big Buck Bunny só .torrent, Tears of Steel — todos tocaram e o tipo de fonte escolhido foi o esperado) e `t23` (magnet colado à mão toca por BitTorrent progressivo). Unitários: `FeedTest` (6), `SourceManagerTest` (3).

### TV ao Vivo — verificação de todos os canais (`PlaybackTest.t21`)
- **457 canais testados em cerca de 80 s** (Brasil + português): **367 no ar**, 90 fora do ar. Brasil: **215 de 294 no ar** (rodada da v8.0.30; a anterior deu 360 e 213). Principais motivos de falha: servidor responde erro HTTP (50), servidor inexistente/sem conexão.
- Precisão: dos canais aprovados na verificação, **10 de 10 sorteados tocaram de verdade no player**.
- **Defeito encontrado e corrigido:** os canais Pluto TV (≈200) redirecionam para endereços assinados com mais de 2 KB, que a proteção de URL recusava. Na 8.0.24 eles falhavam no player — era parte dos "vários canais não funcionam". O limite passou para 16 KB.

### Filmes e séries atuais (`PlaybackTest.t15`, `UiFlowTest.t03/t09`)
- Linhas grátis, Lançamentos e Em Alta carregadas (todas não vazias). Ex.: "Homem-Aranha: Sem Volta Para Casa", "Jogos Vorazes", "John Wick 4" grátis no **Mercado Play**; séries "Sherlock", "Luther", "One Piece".
- Nenhum serviço pago na lista de apps gratuitos; abrir o app não instalado leva à Play Store sem travar.

### Outros defeitos corrigidos nesta rodada
1. **Motor BitTorrent encerrava a sessão durante a abertura de outro magnet** (o desligamento por ociosidade não via a busca de metadados em andamento). Corrigido; o teste `t23` passou.
2. Tela Infantil: animes 16+ (ex.: JoJo) apareciam em "Desenhos Grátis" — a linha passou a usar só o gênero infantil do TMDB.

### Desempenho
- Medido com o APK **release** no emulador (`ci/perf-release.sh`): abre em 0,8 s, sem crash, memória total (PSS) ≈ 120 MB durante a navegação.
- Os números de quadros do emulador **não representam o Mi Stick**: o emulador do CI desenha por software (sem GPU), então todos os quadros passam de 16 ms. A fluidez real precisa ser conferida no aparelho.

---

## Versão 8.0 (v8.0.24)

Data: 05/11/2026 · Commit testado: `70ec607` · Release: **v8.0.24** (versionCode 80024) · Pipeline: GitHub Actions (`.github/workflows/android.yml`)

Todos os resultados abaixo vêm de execuções reais:

- testes unitários na JVM;
- testes instrumentados num emulador Android 11 x86_64 (perfil TV 1080p, 2 GB), **com internet real**.

Os logs, capturas de tela e relatórios ficam nos branches `ci-build` e `ci-tests` do repositório.

## Resumo

| Etapa | Resultado |
|---|---|
| Compilação (Gradle, AGP 8.7.3, Kotlin 2.0.21), APK debug e release assinado | Aprovada |
| Testes unitários (JVM) | **49/49 aprovados** |
| Testes instrumentados (emulador, rede real) | **28/28 aprovados** |
| Validação visual (17 capturas do emulador revisadas) | Aprovada |
| Validação no Xiaomi Mi TV Stick físico | **Pendente**: não houve acesso ao aparelho |

## Verificação do APK (`aapt` + `apksigner`)

- Pacote `com.brudre.streaming`, versão `8.0.N`, `versionCode` 80000 + N.
- `minSdk 24`, `targetSdk 35`. O Mi Stick roda Android 9 (API 28).
- `leanback-launchable-activity` e banner presentes. Touchscreen e leanback são declarados como não obrigatórios.
- Código nativo `armeabi-v7a` (o do Mi Stick) e `arm64-v8a`.
- Assinatura v2 com a mesma chave das versões 6 e 7, então a atualização instala por cima. O APK tem cerca de 24,5 MB.
- O manifesto não tem mais `<queries>` de apps de streaming. A única permissão sensível é `REQUEST_INSTALL_PACKAGES`, usada pelo atualizador do próprio app.

## Os 24 testes exigidos

| # | Teste | Como foi executado | Resultado |
|---|---|---|---|
| 1 | Inicialização | `UiFlowTest.t01`: logotipo, barra superior, destaque e foco inicial em "Assistir Agora" | Aprovado |
| 2 | Navegação pelo controle remoto | `UiFlowTest.t02`: teclas reais (D-pad, OK, Voltar), sem toque: destaque → barra → Filmes → card → detalhes → Voltar | Aprovado |
| 3 | Carregamento do catálogo | `UiFlowTest.t03`: Início, Séries, Infantil, **Clássicos**, **Documentários** e **TV ao Vivo** carregados de fontes reais | Aprovado |
| 4 | Pesquisa | `UiFlowTest.t04` (texto e sugestão) e `t09` (pesquisa unificada com canais) | Aprovado |
| 5 | Internet Archive | `PlaybackTest.t07b` (item resolvido, **verificado** e reproduzido), `t15` (obra do TMDB achada no acervo) e `ArchiveParserTest`/`ArchiveRealDataTest` (14 testes) | Aprovado |
| 6 | IPTV-org | `PlaybackTest.t17`: lista do Brasil com 294 canais, todos HTTPS/HLS, nenhum pago ou adulto, 8 categorias; lista em português com 348 canais de 13 países; busca de canais. `LiveTest` (10 testes, incluindo uma amostra real da lista) | Aprovado |
| 7 | Reprodução de canais ao vivo | `PlaybackTest.t18`: canal da lista tocando ao vivo no player (captura `ao_vivo`) | Aprovado |
| 8 | Motor BitTorrent | `PlaybackTest.t08` (`.torrent` oficial, libtorrent real; o motor encerra ao sair) | Aprovado |
| 9 | Torrent autorizado | `PlaybackTest.t08` (Sintel, Blender) e `t08b` (somente magnet, metadados via DHT) | Aprovado |
| 10 | Reprodução HTTP | `PlaybackTest.t07` (progresso salvo ao pausar) | Aprovado |
| 11 | Reprodução HLS | `PlaybackTest.t19`: HLS 1080p H.264 (stream público de teste da Mux), tentativa rotulada "streaming HLS" | Aprovado |
| 12 | Favoritos | `UiFlowTest.t05` (adicionar → Minha Lista → remover) e `LibraryDbTest.myListAddRemovePersists` | Aprovado |
| 13 | Histórico | `LibraryDbTest.historyAndContinueWatching` | Aprovado |
| 14 | Retomada | `PlaybackTest.t11` (inicia em 5:00) e `LibraryDbTest.resumePositionRules` | Aprovado |
| 15 | Cache | `PlaybackTest.t12`: torrent, imagens, catálogo e listas de canais apagados; espaço liberado conferido | Aprovado |
| 16 | Falhas de conexão | `PlaybackTest.t10`: Wi-Fi e dados desligados. A busca mostra "Sem conexão com a internet", o player mostra erro sem fechar, e tudo volta a funcionar ao religar | Aprovado |
| 17 | Baixa memória | `PlaybackTest.t14`: `onTrimMemory(RUNNING_CRITICAL)` durante a reprodução libera os caches e o vídeo continua. Heap Java de 27 MB | Aprovado |
| 18 | Remoção de redirecionamentos pagos | `SourcesTest.noPaidServiceRedirectsInSource` varre o código-fonte e não encontra referências a Netflix, Prime, Disney+, HBO, Globoplay, `watch/providers`, aluguel/compra ou JustWatch. `PlaybackTest.t15`: lançamento recente fica só como catálogo, sem fonte e sem redirecionamento. `UiFlowTest.t06`: sem fonte, não há botão Assistir | Aprovado |
| 19 | Abertura cinematográfica | `UiFlowTest.t08`: o vídeo iniciou e terminou sozinho, sem precisar da animação reserva. Capturas em 1,0 / 2,3 / 3,7 / 5,4 / 6,3 s. No emulador (decodificação de vídeo por software), as execuções mediram de 7,6 a 10,1 s contando a preparação do player; o vídeo em si tem 7,0 s | Aprovado |
| 20 | Pular a abertura | `UiFlowTest.t08`: OK e Voltar pulam em menos de 2 s, e Voltar não fecha o app | Aprovado |
| 21 | Desativar a abertura | `UiFlowTest.t08`: com a opção desligada, o app abre direto no Início | Aprovado |
| 22 | Transição para a tela inicial | `UiFlowTest.t08`: ao fim do vídeo, o Início com foco em "Assistir Agora" aparece sem espera extra (captura `intro_transicao_inicio`) | Aprovado |
| 23 | Compatibilidade de codecs | `PlaybackTest.t20` (`DeviceInfo` no emulador: "H.264 HW até 2160p, H.265 software, VP9 HW, AV1 não, AAC software"), `t13` (arquivo não-vídeo exibe "Formato de vídeo incompatível com este aparelho." sem travar) e `t19` (H.264 1080p via HLS) | Aprovado |
| 24 | Integridade das funcionalidades existentes | Todos os testes da 7.0 continuam aprovados: controle dos pais com PIN (`t07`), atualização dentro do app (`t16`), título sem fonte (`t06`, `t13b`), Minha Lista e retomada | Aprovado |

## Fontes e segurança

- `PlaybackTest.t15` buscou os 10 primeiros clássicos do TMDB no Internet Archive; **9 de 10** encontraram fonte confirmada e um deles foi reproduzido. Entre os encontrados estão Nosferatu, Metrópolis, Sem Novidade no Front, Viagem à Lua, A General, Asas, Aurora, O Nascimento de uma Nação e O Fantasma da Ópera. *O Garoto* (1921) não tem cópia licenciada no acervo, por isso ficou como catálogo.
- `PlaybackTest.t20`, pelo cliente HTTP protegido:
  - recusa loopback, `localhost`, `[::1]`, `169.254.169.254` e arquivo `.apk`;
  - recusa tráfego sem TLS;
  - `StreamValidator` confirma uma fonte válida e rejeita 404, página HTML no lugar do vídeo e `file://`.
  - Item inexistente no Internet Archive: o acervo não devolve 404 imediato, então o resultado é "não foi possível confirmar". Nesse caso o app também não mostra Assistir; oferece **Verificar de novo**.
- `SourcesTest` (10 testes):
  - casamento de títulos: o mesmo nome com outro ano é recusado;
  - prioridade HTTP → alternativas → BitTorrent;
  - "Somente direto" e BitTorrent desligado nunca iniciam o motor;
  - URLs inseguras não chegam ao player;
  - TMDB só com linhas de domínio público.
- `LiveTest`:
  - parser M3U com vírgulas dentro de aspas, `EXTVLCOPT`, endereços alternativos e IDs estáveis;
  - bloqueio de canais adultos, pagos (com exceção de FAST como Pluto TV), `http`, não-HLS, loopback e `.apk`;
  - animação adulta fora do Infantil.

## Problemas encontrados e corrigidos nesta versão

1. **O IP literal escapava da guarda de DNS.** O OkHttp não consulta DNS para endereços literais, e o interceptor de rede só roda depois de conectar. Agora a URL é validada também antes de conectar (interceptor de aplicação), e cada redirecionamento continua validado.
2. **Anos errados nos clássicos** (ex.: "O Circo 1959", "Aurora 2026"). O parâmetro `region` fazia o TMDB devolver a data de relançamento no Brasil. Ele foi removido. A busca no acervo já usava o ano original e não foi afetada.
3. **South Park em Infantil.** O grupo "Animation" da lista colocava animação adulta em Infantil. Agora Infantil exige o grupo Kids (ou animação que não seja comédia) e há uma lista de exclusão.
4. **Um quarto dos canais usava `http` sem criptografia.** Eles foram excluídos para manter o tráfego sem TLS bloqueado. Sobraram 294 canais no Brasil.
5. **Partículas da vinheta.** Elas formavam a TV na posição final enquanto o ícone ainda se movia. Agora seguem o ícone.

## Limitações conhecidas

- **Não testado no Mi Stick físico.** O emulador é Android 11 x86_64 com 2 GB, não um Android TV ARM com 1 GB. Fluidez, decodificação por hardware da vinheta 1080p e consumo real de memória precisam ser confirmados no aparelho. O bloco Configurações → Este aparelho mostra os decodificadores reais.
- **Lançamentos comerciais não tocam.** Eles aparecem só como catálogo. A disponibilidade depende de existir cópia gratuita e autorizada.
- **TV ao Vivo depende de terceiros.** Os sinais do iptv-org são coletados pela comunidade. Alguns caem, mudam de endereço ou são retransmissões não oficiais de canais abertos. O BruDre remove automaticamente os canais pagos e adultos, mas não consegue comprovar a autorização de cada sinal. O teste 7 precisou tentar canais até achar um no ar, e o primeiro tocou.
- **Licenças do Internet Archive.** Elas são declaradas por quem enviou o item. O app aceita só domínio público ou Creative Commons e exclui a coleção de envios abertos (`opensource_movies`).
- **Chave do TMDB.** Ela fica embutida no APK, como em qualquer app cliente. Se for revogada, as linhas do catálogo mostram erro, mas o resto do app continua funcionando.
- **Faixa inferior nos detalhes.** No emulador ainda aparece uma faixa fina sob a tela de detalhes, na área da barra de navegação do sistema. É preciso conferir no Stick, que não tem essa barra.

## Checklist de validação no aparelho (fazer uma vez)

1. A vinheta toca com som ao abrir. OK pula. Em Configurações → Abertura cinematográfica dá para desligar.
2. Em Configurações → Este aparelho, conferir se aparece "H.264 HW" e se a RAM é de cerca de 1 GB.
3. "Nosferatu", em Clássicos, mostra Assistir e toca.
4. Em TV ao Vivo, abrir 3 canais de Notícias. Segurar OK num deles e conferir se ele aparece em Canais favoritos.
5. Tocar um filme do Internet Archive por 30 min sem travar ou reiniciar o Stick.
6. Sair no meio e reabrir: o título aparece em Continuar Assistindo e retoma do ponto certo.
7. Configurações → Limpar dados temporários mostra o espaço liberado.
