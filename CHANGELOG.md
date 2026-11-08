# Changelog — BruDre Streaming

## 9.2.46 (novembro de 2026) — Correções de segurança, diagnóstico e homologação

Versão corretiva pedida como "9.2.43". O número segue o build do CI; os builds 43, 44 e 45 não passaram nos testes e não foram publicados.

### Credenciais (correção crítica)
- Links de download de indexadores Torznab (`/dl/…?jackett_apikey=…`, `…/download?apikey=…`) não carregam mais a chave. Ela vira o marcador `{cred}`.
- O valor real só entra na requisição, no instante dela, por um interceptor do cliente protegido:
  - só em HTTPS;
  - só para o host:porta da fonte dona da credencial;
  - o destino de um redirecionamento nunca recebe a chave.
- Segredos colados em qualquer endereço de fonte (`token`, `password`, `apikey`, `passkey`…) saem para o armazenamento cifrado (Android Keystore). No endereço gravado e exportado fica `{cred}`.
- Mensagens de erro, detalhes técnicos, histórico de testes, logs, relatório de homologação e histórico de torrents passam por uma limpeza de segredos.
- Respostas de endereços com chave (ex.: `api_key` do TMDB) não vão mais para o cache HTTP em disco.
- Migração automática de registros antigos:
  - fontes, mensagens e histórico de torrents são regravados limpos;
  - o cache HTTP antigo é descartado;
  - caches de catálogo que continham segredos são apagados.

### Diagnóstico de fontes
- Etapas comprovadas: **Cadastrada → Acessível → Pesquisa funcional → Reprodução validada**. A última só vem de um vídeo que realmente tocou pela fonte.
- Erros separados: DNS, tempo esgotado, HTTP, TLS, **autenticação**, formato, **sem resultados**, sem participantes, **conteúdo indisponível**, bloqueada.
- Cada teste registra data, tempo de resposta, etapa alcançada e motivo.
- **Painel Source Engine** (Fontes → Diagnóstico):
  - totais: cadastradas, ativas, acessíveis, com pesquisa validada, com reprodução comprovada e com erro;
  - última verificação, tempo médio de resposta e histórico dos últimos 40 testes;
  - **Testar fontes** (uma por vez) e teste individual por fonte.

### Confiabilidade
- Novas tentativas para falhas transitórias: tempo esgotado, conexão recusada, HTTP 408/429/502/503/504.
  - Espera de 1 s e depois 2 s, no máximo 3 tentativas.
  - Respeita `Retry-After` (até 8 s).
  - Prazo total = dobro do tempo-limite da fonte, até 60 s.
  - Cancelável: sair da tela interrompe a espera.
- Uma fonte lenta ou fora do ar não segura as outras: cada uma tem seu prazo, e a página informa quais falharam e quais vieram vazias.
- **Causa da falha do Internet Archive no CI:** o teste de cadastro usava 12 s por consulta. Agora usa 20 s por tentativa, com novas tentativas.
- `.torrent` remoto: novas tentativas e cache de metadados por endereço.

### BitTorrent
- **Trackers HTTPS agora funcionam.** O teste do CI mostrou que o HTTPS interno do libtorrent falha em todo tracker HTTPS com "init fail (BIO routines)", até com certificado válido. É o mesmo problema que já afetava os web seeds.
- O anúncio HTTPS passou a ser feito pelo cliente protegido, com a validação de certificados do Android, e os participantes recebidos são entregues ao motor. Funciona como os web seeds desde a 9.1.
- O diagnóstico mostra **qual mecanismo achou participantes**: tracker UDP, tracker HTTPS, tracker HTTP ou DHT.
- Também lista as falhas de tracker (certificado, DNS). A validação de certificado continua ligada.

### Interface e homologação
- O texto de Gerenciar fontes lista todos os protocolos.
- O relatório de homologação inclui:
  - versão do BruDre e motivos de erro de reprodução;
  - saídas do app registradas pelo sistema (crash, ANR, falta de memória; Android 11 ou superior);
  - tempo total assistido;
  - situação "NÃO HOMOLOGADO" até o usuário marcar o resultado.

## 9.2 (novembro de 2026) — Source Engine 4.0

### Protocolos de indexação (adaptadores genéricos, nenhum indexador pré-cadastrado)
- **Torznab**: capacidades (`t=caps`), pesquisa por texto, filme por TMDB/IMDb (`t=movie`), série por temporada/episódio (`t=tvsearch`), categorias, paginação `offset/limit`, erros do protocolo traduzidos (ex.: código 100 = credencial recusada). A `apikey` colada no endereço sai do endereço e vai cifrada.
- **Newznab**: mesmo adaptador. Itens NZB (Usenet) são descartados. Tocá-los exigiria um cliente NNTP com yEnc, que não existe no app, e uma conta paga em provedor Usenet. Só entram itens com magnet ou .torrent.
- Resultados vão para o fluxo atual: conferência de título e ano, classificação, motor BitTorrent. O hash (`infohash`) vira magnet. Categoria adulta (6000) é sempre bloqueada. Software, áudio e itens sem semeadores são descartados. O episódio anunciado pelo indexador escolhe o arquivo certo dentro do pacote da temporada.
- Também existentes e mantidos: OpenSearch, RSS/Atom, REST por descritor, **GraphQL (POST)**, catálogo paginado.

### Servidores locais autorizados (Jackett/Prowlarr/Jellyfin na sua rede)
- Endereços da rede privada (10/8, 172.16/12, 192.168/16, fc00::/7) só funcionam como **servidor local confirmado**. A pasta Fontes mostra a impressão digital SHA-256 do certificado; você confere e confirma. Depois disso só aquele certificado é aceito, e só naquele host:porta.
- Sempre HTTPS: o tráfego sem criptografia continua desligado no app inteiro. Loopback e link-local continuam bloqueados.
- Antes, só a validação de certificado barrava a rede privada. Agora ela é **bloqueada explicitamente** (anti-SSRF, inclusive nomes que resolvem para IP privado).
- Importar fontes nunca traz certificado fixado: o servidor local entra desativado até ser confirmado no aparelho.

### Cadastro avançado
- Protocolo (automático ou manual), autenticação (chave no endereço, X-Api-Key, Bearer), credencial cifrada pelo Android Keystore, categorias, tempo-limite, resultados por página, ativa/inativa.

### Biblioteca de fontes (Fontes → Biblioteca)
- 41 entradas: 31 serviços e 10 protocolos.
- 10 provedores avaliados tecnicamente, cada um com disponibilidade, API/feed, adaptador correspondente, condição para integração autorizada e teste: 1337x, YTS, EZTV, The Pirate Bay, TorrentGalaxy, Nyaa, LimeTorrents, RuTracker, Torlock e EXT.to. Nenhum é integrado.

### Classificação explicável e fallback
- Cada fonte em "Escolher fonte" mostra **por que está naquela posição**: título conferido, fonte preferida, áudio e legenda, resolução, formato, histórico, tempo de resposta e prioridade. O tempo de resposta médio desempata.
- Ao trocar de fonte, o player **compara a duração**. Se a diferença passar de 60 s ou 5% (o que for maior), ele pausa e pergunta: Continuar, Escolher outra fonte ou Voltar.

### Homologação do aparelho (Fontes → Diagnóstico)
- Mede no uso real: abertura a frio, tempo até o vídeo, travamentos (rebuffering), falhas, encerramentos inesperados, memória (PSS e avisos do sistema), armazenamento e a sessão mais longa.
- Lista de conferência do D-pad e "Testar rede BitTorrent", que conta participantes sem baixar arquivos.
- O relatório é salvo na pasta do app e pode ser compartilhado. No emulador, ele se declara inválido como homologação.

## 9.1 (novembro de 2026) — Segurança, descoberta de fontes e reprodução

### Segurança TLS (crítico)
- **Removido** o desligamento da validação de certificados da 9.0.37.
- Causa encontrada: o OpenSSL 3.5 do libtorrent4j foi compilado apontando para certificados da máquina de build, inexistentes no Android.
- Servidores web dos torrents (web seeds) agora são baixados pelo **OkHttp protegido do app**: certificado e hostname validados pelo Android, cada redirecionamento conferido (sem http://, destino local ou executável). Cada peça entra no motor por `add_piece`, que confere o hash SHA-1 antes de aceitar.
- O libtorrent mantém a validação ligada, com as CAs do sistema Android entregues ao OpenSSL (para trackers HTTPS).

### Fontes (Source Engine 3.0)
- Adaptadores com **capacidades declaradas** (pesquisa por texto, por identificador, episódios, paginação, formatos, autenticação, limite por minuto): API BruDre, **OpenSearch**, **RSS/Atom** e **API REST por descritor** (`brudre-adapter`), cadastráveis sem recompilar.
- **Paginação sob demanda**: "Carregar mais resultados" na Pesquisa e "Carregar mais" nos catálogos paginados (`next` no JSON, `rel="next"` no RSS/Atom). Sem consultas repetidas, com teto de memória.
- Associação mais rigorosa: **filme × série** nunca se misturam; TMDB diferente rejeita. Fonte encontrada só pelo nome (sem ano) **não entra na troca automática**: aparece em "Escolher fonte" como "(sem ano: confirme)".
- **Preferências**: idioma de áudio e de legenda, resolução, fonte preferida e histórico. O que a fonte não informa aparece como "desconhecido" e nunca é presumido.

### Gerenciar fontes
- Capacidades, falhas de uso, **duplicadas** (recusadas ao cadastrar e marcadas), **Atualizar redetecta o formato**, **revalidação automática** configurável (só com o app aberto), status do TLS no Diagnóstico.
- Fonte salva sem validação **não é ativada sozinha**: passa pela identificação completa do formato, fica "VALIDADA — ATIVE" e só liga quando você ativa.

### Reprodução
- Falha **no meio do vídeo**: tenta de novo a mesma fonte, depois a próxima, **na mesma posição** e com o mesmo idioma de áudio e legenda. Há limite de trocas, sem ciclos. Quando nada funciona: botão "Escolher outra fonte".
- BitTorrent: participantes conectados e conhecidos, servidores web, velocidade real, buffer e "início em ~X s" (só com taxa estável). "Sem participantes" identificado.
- **Faixas reais**: o player lê os idiomas de áudio e legenda do arquivo e mostra ao abrir. Esses idiomas confirmam os selos PT-BR e LEG PT. A troca de faixa no menu do player vira preferência salva.

## 9.0 (novembro de 2026) — Source Engine 2.0

### Fontes
- **Todas as fontes com a mesma obra** são usadas (antes só a primeira): catálogos, APIs de pesquisa e torrents associados. A ordem segue a prioridade da lista; fontes com 3 falhas seguidas vão para o fim. Em "Fontes nativas" escolha se as suas fontes vêm antes ou depois das nativas.
- **Troca automática de fonte**: se uma fonte falha antes de tocar, o player tenta a próxima (endereços e torrents equivalentes). Sem repetição em loop quando todas falham.
- **Escolher fonte (N)** na tela do título: lista das fontes encontradas com formato, qualidade (1080p/720p…) e histórico de uso.
- **API de pesquisa do BruDre**: cadastre um endereço com `{query}`/`{slug}` (e opcionais `{tmdb}`, `{year}`, `{type}`, `{season}`, `{page}`) que responda JSON no formato de catálogo. Consulta dinâmica, até 3 fontes em paralelo, 12 s por fonte, cancelada ao sair da tela.
- Associação por **identificador TMDB** (`"tmdb": "movie:603"`) antes de nome + ano.
- Catálogos não ficam mais todos na memória: índice leve por catálogo e no máximo 4 catálogos carregados ao mesmo tempo.
- **Erros em português, por tipo**: DNS, servidor indisponível, tempo esgotado, HTTP, certificado TLS, formato incompatível, catálogo inválido, magnet inválido, sem peers, sem vídeo, bloqueio de segurança e armazenamento — com **Detalhes técnicos** (erro original).
- **Salvar sem validar**: a fonte fica desativada até um teste dar certo; aí é ativada sozinha. **Testar novamente** no próprio diálogo.
- Gerenciar fontes em seções: **Minhas fontes, Catálogos, BitTorrent, Fontes nativas e Diagnóstico** (prioridade, situação, última verificação, resultados e histórico de cada fonte).

### BitTorrent
- **Torrents do usuário sem catálogo**: colar magnet, importar `.torrent` (seletor de arquivos, quando o aparelho tiver), **Abrir com BruDre** em gerenciadores de arquivos e **Compartilhar** (texto com magnet ou arquivo).
- **Histórico de torrents** com associação opcional a filme/série (passa a aparecer como fonte do título).
- **Lista de arquivos e episódios**: S01E01, 1x01, "Temporada 1 Episódio 1", pastas "Season 1". Agrupado por temporada, progresso individual por episódio; só o vídeo escolhido é baixado. O app não presume mais que o maior arquivo é o desejado.
- **Legendas que vêm dentro do torrent** (.srt, .vtt, .ass) entram no player, com o idioma reconhecido pelo nome.
- Magnet com `xs=` (fonte exata): baixa o `.torrent` por HTTPS quando não há peers (hash conferido).
- **Depois de assistir**: apagar ao fechar (padrão) ou manter até o limite do cache para rever mais rápido.

### Conteúdo do Brasil e do mundo
- Selos de idioma em todos os cards: **PT-BR** (áudio em português), **LEG PT** (legenda em português) e **ORIGINAL XX** (só no idioma original). Na tela do título, a linha de idioma explica o que existe.
- TV ao vivo do mundo: Notícias, Filmes, Séries, Infantil, Documentários, Entretenimento, Música, Esportes e países (EUA, Espanha, México, Argentina, França, Itália), cada um separado em **"em português"** e **"outros idiomas"**.
- Linhas **Cinema Brasileiro**, **Filmes Brasileiros Grátis** e **Séries Brasileiras**.

## 8.1 (novembro de 2026)

### Gerenciar fontes (Configurações → Gerenciar fontes)
- Adicionar fonte pelo controle remoto: o formato é **identificado sozinho** pelos primeiros bytes e a fonte só é salva depois de validada (estrutura + conexão). Formatos: magnet, .torrent, vídeo HTTP/HTTPS, HLS/M3U8, playlist IPTV M3U, catálogo JSON do BruDre e RSS. Documentação em [`docs/FONTES.md`](docs/FONTES.md).
- Cada fonte: **ativar/desativar**, **testar** (resultado com data e hora), **atualizar** (baixa de novo), **subir/descer** (prioridade de reprodução), **editar** e **excluir** (com confirmação).
- **Exportar/importar** a configuração (`brudre-fontes.json` na pasta do app, ou importar de um endereço https).
- Fontes salvas no aparelho: continuam após reiniciar. As listas M3U e catálogos da 8.0 migram sozinhos.
- Integração com o catálogo: catálogos viram linhas no Início; links únicos aparecem em "Minhas fontes (links)"; títulos do app com mesmo nome e ano ganham o botão Assistir com a fonte cadastrada; títulos sem capa recebem capa e sinopse do TMDB.
- **Abrir link de vídeo**: magnet, .torrent, HLS ou vídeo https colado à mão toca direto no player.
- Recusa páginas web, executáveis (APK/EXE/ELF/DEX/ZIP), endereços locais, `http://` sem criptografia e formatos desconhecidos.

### Filmes e séries atuais (gratuitos e legais)
- Abas novas **Lançamentos** e **Em Alta** (filmes e séries), e linhas "Filmes Recentes Grátis", "Séries Grátis", "Desenhos Grátis" etc.
- Títulos grátis em apps oficiais (Pluto TV, YouTube, Plex, Mercado Play, ViX, Tubi) mostram **"Assistir grátis no …"**: abre o app oficial (ou a Play Store para instalar sem custo). Serviços pagos, mesmo os planos "com anúncios", nunca aparecem.
- Canais de filmes e de séries 24 h grátis dentro das seções Filmes e Séries.
- Lançamentos sem versão gratuita continuam no catálogo como "sem fonte gratuita". O BruDre não integra fontes de pirataria.

### TV ao Vivo
- **Teste de sinal de todos os canais**, feito no próprio aparelho (manifesto → variante → segmento mais recente). Canais fora do ar ficam ocultos; o endereço que respondeu passa a ser o principal. Resultado vale 6 h (2 h para falhas) e pode ser refeito em "Testar de novo".

### Desempenho (Mi Stick, 1 GB)
- APK release otimizado com R8 (código e recursos não usados removidos) e perfis de compilação do Compose instalados mesmo fora da Play Store.
- Sombras só no card focado, capas sem animação de troca, capas do TMDB em tamanho de card, cálculos das linhas de canais memorizados, decodificadores lidos fora da thread da interface.
- Verificação de fonte e busca no acervo em cache (20 min e 7 dias): abrir um título pela segunda vez é imediato.
- Vinheta em 720p (1,3 MB, mais leve para decodificar).

### Abertura
- Som cerca de 15 dB mais baixo (−32 LUFS), audível sem assustar.

## 8.0 (build de novembro de 2026)

Atualização sobre a 7.0.20. A interface, a navegação pelo controle remoto, Minha Lista, Histórico, Continuar Assistindo e o controle dos pais foram preservados. Antes das mudanças estruturais foi criado o backup `backup-v7.0.20`, um branch no repositório.

### Serviços pagos removidos

- Saiu a seção "Onde assistir", com os cards de Netflix, Prime Video, Disney+, Max, Paramount+ e Globoplay e as ofertas de assinatura, aluguel e compra.
- Saíram o "Meus streamings", o filtro "Lançamentos: só o que eu assino" e as linhas "Nos Meus Streamings".
- Saíram o `StreamingLauncher`, que abria o app oficial ou a Play Store, e o mapa de pacotes `StreamingApps`. Também foi tirada do manifesto a lista `<queries>` desses apps.
- O TMDB passou a fornecer só metadados (capas, sinopses em português e classificação indicativa). O app não consulta mais `watch/providers`, os dados da JustWatch.
- Um teste automático (`SourcesTest.noPaidServiceRedirectsInSource`) falha se alguma referência a serviço pago voltar ao código.

### BruDre Source Manager (`sources/`)

- Todas as fontes seguem uma interface comum `ContentProvider`, com `search`, `getDetails` e `findStreamsFor`. A validação do stream fica no `StreamValidator`, a resolução da reprodução no `PlaybackPlan` e o estado de cada fonte no `StatusBoard`.
- Os provedores são:
  - Internet Archive;
  - Filmes abertos dos titulares (curadoria com torrent oficial);
  - Catálogo TMDB (só metadados);
  - TV ao Vivo (iptv-org);
  - BitTorrent;
  - listas M3U do usuário.
- Cada fonte tem tempo-limite próprio e uma falha fica registrada só nela; as outras continuam funcionando. Em Configurações → Fontes de conteúdo aparecem o estado de cada uma (funcionando, lenta ou com falha), a latência e o último erro, e é possível ativar ou desativar qualquer fonte.
- O fluxo de reprodução é: título → identificação da fonte → verificação → reprodução.
  - Antes de mostrar o **Assistir**, o primeiro vídeo é verificado: HEAD ou GET dos primeiros bytes, tipo de conteúdo e, para HLS, o manifesto `#EXTM3U`.
  - Um link morto é descartado.
  - Se a fonte não responder, o app mostra "Fonte encontrada, mas não respondeu agora" com o botão **Verificar de novo**, sem Assistir.
- Os títulos aparecem em três estados: **só catálogo** (selo CATÁLOGO), **fonte identificada** (selo GRÁTIS) e **reprodução confirmada** (botão Assistir).
- Títulos do TMDB são procurados no Internet Archive pelos títulos em inglês e original e pelo ano (±1). O app não aceita uma obra com o mesmo nome e outro ano, como o *Metropolis* de 2001 no lugar do de 1927.
- As linhas do TMDB agora mostram só obras publicadas até 1930, que já estão em domínio público nos EUA e por isso costumam ter cópia autorizada no acervo. Lançamentos comerciais continuam na Pesquisa como catálogo, com a mensagem "sem fonte gratuita".

### Internet Archive

- Novas linhas: Faroeste, Aventura e Ação, Documentários Grátis, Arquivo Prelinger, Ciência e Natureza e Documentários Adicionados Recentemente.
- Itens com acesso restrito são recusados também ao abrir os detalhes.
- A pesquisa aceita paginação (`page`).
- O filtro de licença foi mantido: só domínio público ou Creative Commons, sem acesso restrito e sem conteúdo adulto.
- A coleção `opensource_movies`, de envios de usuários, ficou de fora para reduzir o risco de licença declarada incorretamente.

### TV ao Vivo (iptv-org + M3U)

- Nova seção **TV ao Vivo**:
  - Brasil por categoria: Notícias, Infantil, Documentários e Educação, Filmes/Séries/Entretenimento, Esportes, Música, Religiosos e Gerais;
  - Em português, organizado por país;
  - Mundo: Notícias, Infantil e Documentários;
  - listas do usuário;
  - canais favoritos (segure OK no canal).
- As listas usadas são `countries/br.m3u`, `languages/por.m3u` e `categories/{news,kids,documentary}.m3u`. Se `iptv-org.github.io` falhar, o app usa o espelho `raw.githubusercontent.com`.
- O parser M3U lê a lista linha a linha, sem carregá-la inteira na memória. Ele entende `tvg-id`, `tvg-logo`, `group-title`, `http-user-agent` e `http-referrer`, além de `#EXTVLCOPT`.
- Endereços duplicados do mesmo canal viram **sinais alternativos**, usados na reconexão.
- Filtros aplicados sempre:
  - conteúdo adulto;
  - marcas de TV por assinatura (Premiere, SporTV, Telecine, HBO, ESPN, GloboNews, Discovery etc.), exceto canais FAST oficiais como Pluto TV;
  - endereços inseguros;
  - formatos que não são HLS;
  - **http sem TLS**.
- Animação adulta (South Park, Adult Swim…) nunca entra em Infantil.
- As listas ficam em cache por 12 h. Sem rede, o app usa a última versão salva.
- Canais que falham aparecem como **FORA DO AR** na sessão.
- Os canais também aparecem na Pesquisa unificada, no Início (Notícias), em Infantil e em Documentários.
- Em Configurações dá para adicionar até 10 listas M3U gratuitas. Antes de salvar, o app confere a lista e exige ao menos um canal HLS válido; também é possível remover uma lista.

### Player

- HLS via `media3-exoplayer-hls`.
- Todo o tráfego de mídia passa por um cliente OkHttp protegido (`media3-datasource-okhttp`).
- Ordem das fontes: HTTP/HLS → endereços alternativos → BitTorrent oficial. O modo Automático deixou de começar pelo torrent.
- O modo "Somente direto" nunca inicia o motor BitTorrent. Desativar a fonte BitTorrent também impede o uso do motor.
- Ao vivo:
  - sem retomada nem gravação de progresso;
  - reconexão automática em 3 s e 6 s, depois troca para o sinal alternativo;
  - ao ficar para trás da janela ao vivo, volta ao ponto atual;
  - as setas não fazem busca quando a transmissão não tem DVR;
  - indicador "● AO VIVO".
- Aviso ao iniciar o BitTorrent: "seu IP fica visível aos outros participantes". O mesmo aviso aparece em Configurações.

### Segurança e privacidade

- `UrlGuard` aceita só http e https e bloqueia:
  - credenciais embutidas na URL;
  - localhost, loopback, link-local e multicast;
  - URLs com mais de 2048 caracteres;
  - caracteres de controle;
  - extensões executáveis (apk, exe, dex, jar, sh, msi…).
- O `GuardInterceptor` valida **cada salto de redirecionamento** e recusa respostas executáveis. O `GuardDns` não resolve nomes para endereços locais do aparelho.
- O tráfego sem criptografia continua bloqueado no app (`usesCleartextTraffic=false`).
- Nenhuma credencial é pedida. O PIN dos pais continua guardado só como hash SHA-256.
- Limpar dados temporários agora também apaga as listas de canais em cache.

### Seções e interface

- Novas seções no topo: **Clássicos**, **Documentários** e **TV ao Vivo**. Em telas de TV, o menu fica compacto, com ícones; o item focado ou ativo mostra o nome.
- O destaque do Início traz só títulos que tocam no BruDre.
- O histórico em Minha Lista passou a se chamar "Histórico — Continuar Assistindo".
- Configurações ganhou o bloco **Este aparelho**: modelo, Android/API, ABI, RAM, memória livre, limite do app, armazenamento e decodificadores H.264, H.265, VP9, AV1 e AAC, indicando hardware e altura máxima.

### Nova abertura cinematográfica

- Vídeo local de 7,0 s em 1920×1080 a 30 fps, H.264 Main 4.0 com AAC 48 kHz e 2,2 MB, em `res/raw/brudre_intro.mp4`. Não usa internet nem WebView.
- Roteiro:
  - 0–1,5 s: feixes de luz azul, ciano e lilás;
  - 1,5–3 s: o contorno da TV é desenhado por uma linha luminosa e partículas formam o nome;
  - 3–5 s: "BruDre" surge com profundidade (escala e desfoque), a TV assume sua posição e STREAMING aparece abaixo;
  - 5–6,5 s: brilho especular, reflexo e pulsação;
  - 6,5–7 s: transição para a cor de fundo do app.
- Trilha sonora **original**, sintetizada por código: drone, varredura, faíscas, impacto suave, pad em Ré maior e sinos FM. Toca a −17 LUFS, no fluxo de mídia, e respeita o volume do sistema.
- OK, Voltar ou qualquer tecla pula a vinheta. Ela não se repete ao voltar do segundo plano e pode ser desligada em Configurações. O catálogo carrega em paralelo.
- Se o aparelho não decodificar o vídeo, o app usa a animação desenhada da versão anterior.
- Os scripts que geram o vídeo e a trilha estão em `tools/intro/`.

### Versão

- `versionName` 8.0.N e `versionCode` 80000 + N. A atualização dentro do app funciona a partir da 7.0.x, porque o versionCode e a build são maiores e a assinatura é a mesma.
