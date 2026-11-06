# Changelog — BruDre Streaming

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
