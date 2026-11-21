package org.example.canvasproject.server;

import org.java_websocket.server.DefaultSSLWebSocketServerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.logging.Logger;

public class ServerMain {

    private static final Logger log = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) throws InterruptedException {
        String host = ServerConfig.host();
        int port = ServerConfig.port();

        CollaborationServer server = new CollaborationServer(new InetSocketAddress(host, port));

        String keystore = ServerConfig.tlsKeystore();
        boolean tls = keystore != null && !keystore.isBlank();
        if (tls) {
            try {
                server.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(loadSslContext(
                        keystore, ServerConfig.tlsPassword())));
            } catch (Exception e) {
                log.severe("Не удалось загрузить TLS-хранилище " + keystore + ": " + e.getMessage());
                return;
            }
        }

        server.start();
        String scheme = tls ? "wss" : "ws";
        log.info("Canvas Collaboration Server: " + scheme + "://" + host + ":" + port);
        log.info("MongoDB: " + System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017"));
        log.info("Новые устройства: " + (ServerConfig.allowNewDevices() ? "разрешены" : "запрещены"));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (Exception e) {
                log.warning("Error stopping server: " + e.getMessage());
            }
        }));

        Thread.currentThread().join();
    }

    private static SSLContext loadSslContext(String path, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path)) {
            keyStore.load(in, password.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password.toCharArray());

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }
}
