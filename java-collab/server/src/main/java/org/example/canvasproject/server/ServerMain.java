package org.example.canvasproject.server;

import java.util.logging.Logger;

public class ServerMain {

    private static final Logger log = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) throws InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("WS_PORT", "8765"));

        CollaborationServer server = new CollaborationServer(port);
        server.start();
        log.info("Canvas Collaboration Server listening on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (Exception e) {
                log.warning("Error stopping server: " + e.getMessage());
            }
        }));

        Thread.currentThread().join();
    }
}
