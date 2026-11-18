package com.suksesjt12.pcgamelauncher.x11.transport;

public interface ConnectionHandler {
    void handleConnectionShutdown(Client client);

    void handleNewConnection(Client client);
}