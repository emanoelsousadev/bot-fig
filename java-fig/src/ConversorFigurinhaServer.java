import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.Base64;
import java.util.UUID;

public class ConversorFigurinhaServer {

    static final String PASTA_TEMP = "temp_conversao";

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get(PASTA_TEMP));

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/converter", new ConversorHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("✅ Servidor Java rodando em http://localhost:8080");
        System.out.println("Endpoint disponível: POST /converter");
    }

    static class ConversorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String tipo = "imagem";
            if (query != null && query.contains("tipo=video")) {
                tipo = "video";
            }

            String extensaoEntrada = tipo.equals("video") ? "mp4" : "jpg";

            String idUnico = UUID.randomUUID().toString();
            Path caminhoEntrada = Paths.get(PASTA_TEMP, idUnico + "_entrada." + extensaoEntrada);
            Path caminhoSaida = Paths.get(PASTA_TEMP, idUnico + "_saida.webp");

            try {
                String base64Recebido = new String(exchange.getRequestBody().readAllBytes());
                byte[] bytesMidia = Base64.getDecoder().decode(base64Recebido);

                Files.write(caminhoEntrada, bytesMidia);

                boolean sucesso = tipo.equals("video")
                        ? converterVideoParaWebpAnimado(caminhoEntrada, caminhoSaida)
                        : converterImagemParaWebp(caminhoEntrada, caminhoSaida);

                if (!sucesso || !Files.exists(caminhoSaida)) {
                    enviarResposta(exchange, 500, "Erro na conversão da mídia");
                    return;
                }

                byte[] bytesWebp = Files.readAllBytes(caminhoSaida);
                String base64Resultado = Base64.getEncoder().encodeToString(bytesWebp);

                enviarResposta(exchange, 200, base64Resultado);

            } catch (Exception e) {
                e.printStackTrace();
                enviarResposta(exchange, 500, "Erro interno: " + e.getMessage());
            } finally {
                try { Files.deleteIfExists(caminhoEntrada); } catch (IOException ignored) {}
                try { Files.deleteIfExists(caminhoSaida); } catch (IOException ignored) {}
            }
        }

        private boolean converterImagemParaWebp(Path entrada, Path saida) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-i", entrada.toAbsolutePath().toString(),
                        "-vf", "scale=512:512:force_original_aspect_ratio=decrease,format=rgba,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000",
                        "-pix_fmt", "yuva420p",
                        "-vcodec", "libwebp",
                        saida.toAbsolutePath().toString()
                );
                return executarFfmpeg(pb);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        static final int DURACAO_MAXIMA_SEGUNDOS = 6;
        static final long TAMANHO_MAXIMO_BYTES = 500 * 1024;

        private boolean converterVideoParaWebpAnimado(Path entrada, Path saida) {
            int[] tentativasFps = { 12, 10, 8 };
            int[] tentativasQualidade = { 60, 45, 30 };

            for (int i = 0; i < tentativasFps.length; i++) {
                boolean ok = executarConversaoAnimada(entrada, saida, tentativasFps[i], tentativasQualidade[i]);

                if (!ok) continue;

                try {
                    long tamanho = Files.size(saida);
                    System.out.printf("Tentativa %d: fps=%d qualidade=%d tamanho=%.1fKB%n",
                            i + 1, tentativasFps[i], tentativasQualidade[i], tamanho / 1024.0);

                    if (tamanho <= TAMANHO_MAXIMO_BYTES) {
                        return true;
                    }
                    if (i == tentativasFps.length - 1) {
                        return true;
                    }
                } catch (IOException e) {
                    return false;
                }
            }
            return false;
        }

        private boolean executarConversaoAnimada(Path entrada, Path saida, int fps, int qualidade) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-i", entrada.toAbsolutePath().toString(),
                        "-t", String.valueOf(DURACAO_MAXIMA_SEGUNDOS),
                        "-an",
                        "-vf", "fps=" + fps + ",scale=512:512:force_original_aspect_ratio=decrease,format=rgba,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000",
                        "-pix_fmt", "yuva420p",
                        "-vcodec", "libwebp",
                        "-loop", "0",
                        "-q:v", String.valueOf(qualidade),
                        "-vsync", "0",
                        saida.toAbsolutePath().toString()
                );
                return executarFfmpeg(pb);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        private boolean executarFfmpeg(ProcessBuilder pb) throws Exception {
            pb.redirectErrorStream(true);
            Process processo = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(processo.getInputStream()))) {
                while (reader.readLine() != null) {
                }
            }

            int codigoSaida = processo.waitFor();
            return codigoSaida == 0;
        }

        private void enviarResposta(HttpExchange exchange, int statusCode, String corpo) throws IOException {
            byte[] bytesResposta = corpo.getBytes();
            exchange.sendResponseHeaders(statusCode, bytesResposta.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytesResposta);
            }
        }
    }
}
