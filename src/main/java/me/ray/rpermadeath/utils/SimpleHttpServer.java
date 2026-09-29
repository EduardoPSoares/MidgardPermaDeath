package me.ray.rpermadeath.utils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import me.ray.rpermadeath.RPermadeath;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class SimpleHttpServer {

    private HttpServer server;
    private final int port;
    private final File rootDir;

    public SimpleHttpServer(int port, File rootDir) {
        this.port = port;
        this.rootDir = rootDir;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", new FileHandler());
        server.setExecutor(null);
        server.start();
        RPermadeath.getInstance().getLogger().info("HTTP Server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                String path = t.getRequestURI().getPath();
                File file = new File(rootDir, path.substring(1)).getCanonicalFile();

                // Prevent path traversal
                if (!file.toPath().startsWith(rootDir.getCanonicalFile().toPath())) {
                    String response = "403 (Forbidden)\n";
                    t.sendResponseHeaders(403, response.length());
                    try (OutputStream os = t.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    return;
                }

                if (file.exists() && !file.isDirectory()) {
                    t.sendResponseHeaders(200, file.length());
                    try (OutputStream os = t.getResponseBody();
                         FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, count);
                        }
                    }
                } else {
                    String response = "404 (Not Found)\n";
                    t.sendResponseHeaders(404, response.length());
                    try (OutputStream os = t.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                }
            } catch (Exception e) {
                RPermadeath.getInstance().getLogger().log(java.util.logging.Level.SEVERE, "Erro no servidor HTTP", e);
                // Tenta enviar erro 500 se possível
                try {
                    String response = "500 (Internal Server Error)\n";
                    t.sendResponseHeaders(500, response.length());
                    try (OutputStream os = t.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                } catch (Exception ignored) {}
            }
        }
    }
}
