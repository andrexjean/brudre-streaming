# BruDre Streaming 6.0 — Relatório de testes

Data: 04/11/2026 · Commit testado: `fa4f7ea` · Pipeline: GitHub Actions (`.github/workflows/android.yml`)

## Resumo

| Etapa | Resultado |
|---|---|
| Compilação (Gradle, AGP 8.7.3, Kotlin 2.0.21) | Aprovada |
| APK release assinado (`BruDre-Streaming.apk`, 22 MB) | Gerado |
| Testes unitários (JVM) | **20/20 aprovados** |
| Testes instrumentados (emulador Android 11, rede real) | **19/19 aprovados** |
| Validação no Xiaomi Mi TV Stick físico | **Pendente**: depende do aparelho |

## Verificação do APK

Feita com `aapt dump badging` e `apksigner verify`:

- Pacote `com.brudre.streaming`, versão 6.0 (código 6).
- `minSdk 24`, `targetSdk 35`. O Mi Stick roda Android 9 (API 28).
- `leanback-launchable-activity` presente, com banner próprio. O app aparece na tela inicial do Android TV.
- `android.hardware.touchscreen` e `android.software.leanback` declarados como não obrigatórios.
- Código nativo: `armeabi-v7a` (usado pelo Mi Stick, Amlogic S805Y com userland 32 bits) e `arm64-v8a`.
- Assinatura v2: `CN=BruDre Streaming, O=Familia Oliveira, C=BR`. A chave é fixa, então as atualizações instalam por cima.

## Os 14 testes exigidos

| # | Teste | Como foi executado | Resultado |
|---|---|---|---|
| 1 | Inicialização | `UiFlowTest.t01`: abre o app, confere logotipo, barra superior, herói e foco inicial em "Assistir Agora" | Aprovado |
| 2 | Navegação pelo controle remoto | `UiFlowTest.t02`: teclas reais injetadas na janela (D-pad direita/cima, OK, Voltar), sem toque. Herói → Detalhes → barra superior → Filmes → card → abre detalhes → Voltar fecha | Aprovado |
| 3 | Carregamento do catálogo | `UiFlowTest.t03`: linhas de Filmes, Séries e Infantil carregadas do Internet Archive real, mais o índice curado | Aprovado |
| 4 | Pesquisa | `UiFlowTest.t04`: busca digitada ("popeye") e sugestão por gênero ("Terror") retornam resultados | Aprovado |
| 5 | Minha Lista | `UiFlowTest.t05` (pela interface: adicionar → aparece em Minha Lista → remover → lista vazia) e `LibraryDbTest.myListAddRemovePersists` (persistência Room) | Aprovado |
| 6 | Histórico | `LibraryDbTest.historyAndContinueWatching`: ordem, último episódio por título, filtro de concluídos, limpeza | Aprovado |
| 7 | Reprodução direta | `PlaybackTest.t07` (Sintel via HTTP; progresso salvo ao pausar) e `t07b` (filme do Internet Archive resolvido automaticamente e reproduzido) | Aprovado |
| 8 | Torrent autorizado | `PlaybackTest.t08` (`.torrent` oficial, motor libtorrent real, reprodução iniciada) e `t08b` (somente magnet, metadados via DHT) | Aprovado |
| 9 | Buffering e seeks | `PlaybackTest.t08`: salto para o meio do filme durante o torrent; peças priorizadas e reprodução retomada | Aprovado |
| 10 | Interrupção de internet | `PlaybackTest.t10`: Wi-Fi e dados desligados no emulador. A busca mostra "Sem conexão com a internet", o player mostra erro sem fechar e tudo volta a funcionar ao religar | Aprovado |
| 11 | Retomada de reprodução | `PlaybackTest.t11` (inicia em 5:00 a partir da posição salva) e `LibraryDbTest.resumePositionRules` | Aprovado |
| 12 | Limpeza de cache | `PlaybackTest.t12`: dados de torrent, imagens e catálogo apagados e espaço liberado conferido | Aprovado |
| 13 | Erros de codec | `PlaybackTest.t13`: arquivo não-vídeo exibe "Formato de vídeo incompatível com este aparelho." sem travar. `t13b`: título sem fonte avisa e não reproduz | Aprovado |
| 14 | Baixa memória | `PlaybackTest.t14`: `onTrimMemory(RUNNING_CRITICAL)` durante a reprodução esvazia o cache de imagens e o vídeo continua tocando. Heap Java medido: 23 MB | Aprovado |

Extra: `UiFlowTest.t06` confirma que um título sem fonte autorizada **não** exibe o botão Assistir.

## Testes unitários (JVM)

- `ArchiveParserTest` (10): leitura das respostas do Internet Archive, escolha de qualidade (H.264 / 512 kb), episódios e temporadas, itens restritos, formatos rejeitados.
- `ArchiveRealDataTest` (4): metadados reais copiados do acervo (Elephants Dream, Night of the Living Dead em DVD, Bonanza). Versões duplicadas viram um item, o filme inteiro prevalece sobre as partes do DVD, há legendas automáticas e o filtro familiar funciona.
- `CoreLogicTest` (6): cálculo de peças do torrent, ordem de fontes, JSON dos modelos, índice curado (só HTTPS/magnet válidos e licença declarada), limpeza de cache, leitura de magnet.

## Problemas encontrados e corrigidos durante os testes

1. **O torrent via `.torrent` não recebia dados.** O arquivo publicado só trazia trackers WebRTC. Agora o app adiciona os trackers e o web seed do magnet oficial, trackers públicos de apoio e nós DHT. O mesmo cenário passou a tocar em cerca de 30 s.
2. **Identificador errado no Internet Archive** ("A Noite dos Mortos-Vivos"). Foi corrigido para o item restaurado em domínio público.
3. **Conteúdo impróprio e de licença duvidosa no topo do acervo.** Agora todas as consultas exigem licença declarada (domínio público ou Creative Commons) e excluem termos adultos. Há ainda um filtro local de segurança.
4. **Nomes de coleção incorretos** (`Comedy_Films`, `Film_Noir`). As linhas apareciam vazias e foram corrigidas.
5. **Barra superior cortada em telas menores.** Agora existe modo compacto abaixo de 900 dp.
6. **Foco inicial puxava o usuário de volta ao herói.** Agora as tentativas param na primeira tecla do controle.
7. **Vídeo travado no meio.** Agora a fonte é trocada automaticamente: torrent após 45 s sem dados, direto após 60 s.

## Limitações conhecidas

- **Não testado no Mi Stick físico.** O emulador era um Android 11 x86_64 com 2 GB, não um Android TV ARM com 1 GB. Fluidez da interface, decodificação por hardware (Mali-450) e consumo real de memória precisam ser confirmados no aparelho.
- **Catálogo limitado ao acervo legal.** Filmes e séries comerciais não aparecem. Itens do Internet Archive dependem da licença declarada por quem os enviou.
- **Classificação indicativa.** O acervo não informa, então os títulos aparecem como "NC" (não classificado).
- **Desempenho do torrent.** Depende de peers e web seeds no momento. O modo Automático usa o servidor oficial primeiro nos itens do Internet Archive.

## Checklist de validação no aparelho (fazer uma vez)

1. O app aparece na tela inicial com o banner BruDre.
2. A navegação funciona só com o controle: setas, OK e Voltar.
3. "Sintel" toca pelo herói; trocar a fonte para "Preferir torrent" e tocar de novo.
4. Um filme longo (ex.: "His Girl Friday") toca 30 min sem travar ou reiniciar o Stick.
5. Sair no meio e reabrir: aparece em "Continuar Assistindo" e retoma do ponto certo.
6. Configurações → Limpar dados temporários mostra o espaço liberado.
