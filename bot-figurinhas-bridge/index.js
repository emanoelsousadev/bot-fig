const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, downloadMediaMessage } = require('@whiskeysockets/baileys');
const qrcode = require('qrcode-terminal');
const pino = require('pino');
const fs = require('fs');
const path = require('path');

// Pasta onde vamos salvar as imagens recebidas, por enquanto só pra teste
const PASTA_IMAGENS = path.join(__dirname, 'imagens_recebidas');
if (!fs.existsSync(PASTA_IMAGENS)) {
    fs.mkdirSync(PASTA_IMAGENS);
}

async function iniciarBot() {
    // Salva a sessão numa pasta local, assim não precisa escanear o QR toda vez
    const { state, saveCreds } = await useMultiFileAuthState('sessao_auth');

    const sock = makeWASocket({
        auth: state,
        logger: pino({ level: 'silent' }) // muda pra 'info' se quiser ver logs detalhados
    });

    // Sempre que a sessão for atualizada, salva localmente
    sock.ev.on('creds.update', saveCreds);

    // Escuta mudanças de conexão (QR code, conectado, desconectado)
    sock.ev.on('connection.update', (update) => {
        const { connection, lastDisconnect, qr } = update;

        if (qr) {
            console.log('Escaneie o QR code abaixo com o WhatsApp Business (TIM):');
            qrcode.generate(qr, { small: true });
        }

        if (connection === 'close') {
            const deveReconectar = (lastDisconnect?.error)?.output?.statusCode !== DisconnectReason.loggedOut;
            console.log('Conexão fechada. Reconectando?', deveReconectar);
            if (deveReconectar) {
                iniciarBot();
            }
        } else if (connection === 'open') {
            console.log('✅ Conectado ao WhatsApp com sucesso!');
        }
    });

    // Escuta mensagens recebidas
    sock.ev.on('messages.upsert', async (m) => {
        const msg = m.messages[0];
        if (!msg.message) return;
        if (msg.key.fromMe) return; // ignora mensagens enviadas pelo próprio bot

        const remetente = msg.key.remoteJid;
        const tipoMensagem = Object.keys(msg.message)[0];

        console.log(`Mensagem recebida de ${remetente} - tipo: ${tipoMensagem}`);

        const ehImagem = tipoMensagem === 'imageMessage';
        const ehVideo = tipoMensagem === 'videoMessage';

        if (ehImagem || ehVideo) {
            const tipo = ehImagem ? 'imagem' : 'video';
            const extensao = ehImagem ? 'jpg' : 'mp4';
            const emoji = ehImagem ? '📷' : '🎬';

            console.log(`${emoji} ${ehImagem ? 'Imagem' : 'Vídeo/GIF'} recebido! Baixando...`);
            try {
                const buffer = await downloadMediaMessage(
                    msg,
                    'buffer',
                    {},
                    { logger: pino({ level: 'silent' }) }
                );

                const nomeArquivo = `${tipo}_${Date.now()}.${extensao}`;
                const caminhoCompleto = path.join(PASTA_IMAGENS, nomeArquivo);
                fs.writeFileSync(caminhoCompleto, buffer);

                console.log(`✅ Arquivo salvo em: ${caminhoCompleto}`);

                // Manda pro backend Java, informando o tipo via query param
                console.log('🔄 Enviando para o backend Java converter em figurinha...');
                const base64Original = buffer.toString('base64');

                const resposta = await fetch(`http://localhost:8080/converter?tipo=${tipo}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain' },
                    body: base64Original
                });

                if (!resposta.ok) {
                    const textoErro = await resposta.text();
                    throw new Error(`Backend Java retornou status ${resposta.status}: ${textoErro}`);
                }

                const base64Webp = await resposta.text();
                const bufferSticker = Buffer.from(base64Webp, 'base64');

                console.log('📤 Enviando figurinha de volta...');
                await sock.sendMessage(remetente, { sticker: bufferSticker });

                console.log('✅ Figurinha enviada com sucesso!');
            } catch (erro) {
                console.error(`❌ Erro ao processar o ${tipo}:`, erro);
            }
        }
    });
}

iniciarBot();
