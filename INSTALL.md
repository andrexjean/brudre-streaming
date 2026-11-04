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

3. Pressione **Go**. O download tem cerca de 30 MB.
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

Repita o passo 3 com o mesmo endereço. A nova versão instala por cima e **mantém** Minha Lista e o progresso, porque todas as versões usam a mesma assinatura.

## 6. Ajustes recomendados para o Mi Stick (1 GB de RAM)

Em **Configurações** do BruDre:

- **Qualidade → Leve** se o vídeo travar ou o Wi-Fi for fraco.
- **Fonte de reprodução → Automático** (padrão). Usa o servidor oficial e troca sozinho para torrent se ele falhar.
- **Limpar dados temporários** se o Stick avisar falta de espaço.

## Problemas comuns

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| "Instalação bloqueada" | Fontes desconhecidas desativada | Refaça o passo 2 |
| "App não instalado" ao atualizar | Versão antiga com outra assinatura | Desinstale a antiga e instale de novo |
| Título sem botão Assistir | Não existe fonte autorizada gratuita | Normal: o catálogo mostra o que existe, mas só toca o que é livre |
| Vídeo demora para começar via torrent | Poucas conexões naquele momento | Aguarde ou mude a fonte para Automático |
| "Armazenamento insuficiente" | Pouco espaço no Stick | Configurações → Limpar dados temporários |

## Sobre o conteúdo

O BruDre reproduz apenas obras de distribuição livre: acervo público do Internet Archive (domínio público e licenças abertas) e filmes abertos publicados pelos próprios titulares, como os da Blender Foundation. Filmes e séries comerciais não estão disponíveis, porque não existe fonte gratuita autorizada para eles.
