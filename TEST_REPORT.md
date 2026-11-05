# BruDre Streaming 8.0 — Relatório de testes

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
