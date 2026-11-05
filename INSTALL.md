# BruDre Streaming — Instalação no Xiaomi Mi TV Stick

Guia para quem não programa. Leva cerca de 10 minutos na primeira vez.

## O que você precisa

- Mi TV Stick ligado na TV e conectado ao Wi-Fi.
- O controle remoto do Stick.
- Nenhum computador.

## 1. Instalar o app "Downloader" na TV

1. Na tela inicial do Stick, abra a **Google Play Store**.
2. Pesquise por **Downloader** (ícone laranja, desenvolvedor *AFTVnews*).
3. Instale e abra uma vez. Se pedir acesso a arquivos, toque em **Permitir**.

## 2. Permitir a instalação do BruDre

1. Abra **Configurações** (engrenagem no canto superior direito).
2. Vá em **Preferências do dispositivo → Segurança e restrições → Fontes desconhecidas**.
3. Ative a chave ao lado de **Downloader**.

> Os nomes podem variar levemente conforme a versão do sistema do Stick.

## 3. Baixar e instalar o BruDre

1. Abra o **Downloader**.
2. No campo de endereço, digite exatamente:

   ```
   https://github.com/andrexjean/brudre-streaming/releases/latest/download/BruDre-Streaming.apk
   ```

3. Pressione **Go**. O download tem cerca de 25 MB.
4. Quando aparecer a tela de instalação, escolha **Instalar**.
5. Ao terminar, escolha **Concluído**. O Downloader pergunta se quer apagar o arquivo baixado: escolha **Excluir** para liberar espaço.

### Alternativa pelo celular (sem digitar o endereço)

1. Instale o app **Send Files to TV** no celular (Play Store) e também na TV.
2. No celular, baixe o `BruDre-Streaming.apk` pelo endereço acima.
3. Envie o arquivo para a TV pelo Send Files to TV e abra-o na TV com um gerenciador de arquivos.
4. Na primeira vez, o Android pede para permitir instalação a partir desse app: permita.

## 4. Abrir

- O BruDre aparece na lista de **Apps** do Stick (role até o fim e use "Personalizar" para fixar na tela inicial).
- Navegue com as **setas**, confirme com **OK**, volte com **Voltar**.
- No player: **OK** pausa/continua, **← →** voltam/avançam 10 s, **↑ ↓** mostram os controles (legendas no botão CC).
- Na tela inicial, **Voltar** duas vezes fecha o app.

## 5. Atualizar

**Quem já tem a 7.0 instalada:** abra o BruDre. Em alguns segundos aparece o botão **Atualizar** no topo (ou em Configurações → Atualização do app). Aperte OK e confirme **Atualizar** na tela do Android. Minha Lista, o histórico e o progresso são mantidos.

Também funciona repetir o passo 3 com o mesmo endereço: a nova versão instala por cima porque todas usam a mesma assinatura.

## 6. Ajustes recomendados para o Mi Stick (1 GB de RAM)

Em **Configurações** do BruDre:

- **Qualidade → Leve** se o vídeo travar ou o Wi-Fi for fraco.
- **Fonte de reprodução → Automático** (padrão). Usa o streaming HTTP/HLS primeiro e só liga o BitTorrent se ele falhar.
- **Fontes de conteúdo**: desligue o que não usa (por exemplo, BitTorrent) para economizar memória.
- **Abertura cinematográfica**: pode ser desligada para o app abrir mais rápido.
- **Limpar dados temporários** se o Stick avisar falta de espaço.

## Problemas comuns

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| "Instalação bloqueada" | Fontes desconhecidas desativada | Refaça o passo 2 |
| "App não instalado" ao atualizar | Versão antiga com outra assinatura | Desinstale a antiga e instale de novo |
| Título sem botão Assistir (selo CATÁLOGO) | Não existe fonte gratuita e autorizada | Normal: lançamentos comerciais aparecem só como catálogo |
| "Fonte encontrada, mas não respondeu agora" | Servidor da fonte lento ou fora do ar | Aperte **Verificar de novo** depois de alguns segundos |
| Canal ao vivo "FORA DO AR" | O sinal da lista pública caiu | Tente outro canal; as listas são atualizadas a cada 12 h |
| Vídeo demora para começar via torrent | Poucas conexões naquele momento | Aguarde ou mude a fonte para Automático |
| "Armazenamento insuficiente" | Pouco espaço no Stick | Configurações → Limpar dados temporários |

## TV ao Vivo

- Entre em **TV ao Vivo** no topo. Os canais abertos do Brasil aparecem por categoria, seguidos dos canais em português de outros países e dos canais do mundo.
- **OK** abre o canal. **Segure OK** para colocar ou tirar dos favoritos.
- Se o sinal cair, o app reconecta sozinho e tenta os endereços alternativos do canal.
- Para adicionar outra lista gratuita: **Configurações → Fontes de conteúdo → Adicionar lista M3U**. Use só listas com endereço `https://` e canais autorizados.

## Ficha técnica

| Item | Valor |
|---|---|
| Versão | 8.0.N (versionCode 80000 + N; o N é o número do build no GitHub) |
| Pacote | `com.brudre.streaming` |
| ABI | `armeabi-v7a` (Mi Stick) e `arm64-v8a` |
| SDK mínimo / alvo | Android 7.0 (API 24) / API 35. O Mi Stick roda Android 9 (API 28) |
| Tamanho | cerca de 25 MB (a vinheta ocupa 2,2 MB; o motor BitTorrent nativo, a maior parte) |
| Principais dependências | Kotlin 2.0.21, Jetpack Compose (BOM 2024.12), Media3/ExoPlayer 1.5.1 (+ HLS e OkHttp), Room 2.6.1, Coil 2.7, OkHttp 4.12, libtorrent4j 2.1.0-38 |
| Fontes | Internet Archive, filmes abertos dos titulares (Blender Studio), TMDB (só metadados), iptv-org (canais abertos), listas M3U do usuário, BitTorrent oficial |
| Permissões | Internet, estado da rede, instalar atualizações do próprio app |

## Sobre o conteúdo

O BruDre não usa assinaturas nem pagamentos e não abre outros apps de streaming. Ele reproduz:

- acervo público do Internet Archive (domínio público e licenças abertas);
- filmes abertos publicados pelos próprios titulares, como os da Blender Foundation;
- canais de TV abertos das listas públicas do projeto iptv-org, filtradas: sem canais pagos, sem conteúdo adulto e só com conexão criptografada.

Lançamentos comerciais aparecem apenas como catálogo (capa e sinopse do TMDB), sem botão Assistir, porque não existe fonte gratuita autorizada para eles. Os sinais de TV ao vivo são mantidos por terceiros: alguns podem ficar fora do ar ou ter origem não oficial, e o BruDre não garante a disponibilidade deles. Conexões BitTorrent tornam o endereço IP visível aos outros participantes.
