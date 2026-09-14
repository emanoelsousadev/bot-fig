# Bot de Figurinhas para WhatsApp

Bot que recebe imagens e vídeos/GIFs pelo WhatsApp e responde com a figurinha (sticker) correspondente, já convertida para o formato WebP exigido pelo WhatsApp.

O projeto é dividido em duas partes que conversam entre si por HTTP:

- **`bot-figurinhas-bridge/`** (Node.js) — conecta ao WhatsApp usando [Baileys](https://github.com/WhiskeySockets/Baileys), escuta mensagens recebidas, baixa a mídia e envia a figurinha de volta.
- **`java-fig/`** (Java) — servidor HTTP simples que recebe a mídia em base64, usa o `ffmpeg` para converter em WebP (estático para imagem, animado para vídeo/GIF) e devolve o resultado.

## Como funciona

1. O usuário envia uma foto ou vídeo/GIF pelo WhatsApp.
2. O bridge Node.js baixa a mídia e envia para `POST http://localhost:8080/converter` (endpoint do servidor Java).
3. O servidor Java converte a mídia para `.webp` usando `ffmpeg` e retorna o resultado em base64.
4. O bridge Node.js envia a figurinha de volta para o remetente.

## Pré-requisitos

- [Node.js](https://nodejs.org/) 18+
- [JDK](https://adoptium.net/) 17+
- [ffmpeg](https://ffmpeg.org/) instalado e disponível no `PATH`

## Como rodar

### 1. Servidor Java (conversor)

```bash
cd java-fig/src
javac ConversorFigurinhaServer.java
java ConversorFigurinhaServer
```

O servidor sobe em `http://localhost:8080`.

### 2. Bridge do WhatsApp (Node.js)

```bash
cd bot-figurinhas-bridge
npm install
node index.js
```

Na primeira execução, escaneie o QR code exibido no terminal com o WhatsApp (Configurações > Aparelhos conectados > Conectar um aparelho). A sessão fica salva localmente em `sessao_auth/` para não precisar escanear novamente.

## Aviso de segurança

A pasta `bot-figurinhas-bridge/sessao_auth/` guarda as credenciais da sessão autenticada do WhatsApp (chaves de criptografia da conta). Ela é ignorada pelo Git (`.gitignore`) e **nunca deve ser commitada ou compartilhada** — quem tiver acesso a esses arquivos consegue assumir a sessão do WhatsApp conectado.

As imagens recebidas (`imagens_recebidas/`) e os arquivos temporários de conversão (`temp_conversao/`) também não são versionados.
