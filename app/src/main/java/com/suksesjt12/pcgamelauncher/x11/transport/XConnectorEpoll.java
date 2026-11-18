package com.suksesjt12.pcgamelauncher.x11.transport;

import android.util.SparseArray;

import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
    private final ConnectionHandler connectionHandler;
    private final RequestHandler requestHandler;
    private final int epollFd;
    private final int serverFd;
    private final int shutdownFd;
    private Thread epollThread;
    private volatile boolean running = false;
    private boolean multithreadedClients = false;
    private boolean canReceiveAncillaryMessages = false;
    private int initialInputBufferCapacity = 4096;
    private int initialOutputBufferCapacity = 4096;
    private final SparseArray<Client> connectedClients = new SparseArray<>();

    static {
        System.loadLibrary("launcher_native");
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;
        serverFd = createAFUnixSocket(socketConfig.path);
        if (serverFd < 0) throw new RuntimeException("Failed to create an AF_UNIX socket.");

        epollFd = createEpollFd();
        if (epollFd < 0) {
            closeFd(serverFd);
            throw new RuntimeException("Failed to create epoll fd.");
        }
        if (!addFdToEpoll(epollFd, serverFd)) {
            closeFd(serverFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add server fd to epoll.");
        }

        shutdownFd = createEventFd();
        if (!addFdToEpoll(epollFd, shutdownFd)) {
            closeFd(serverFd);
            closeFd(shutdownFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }
        epollThread = new Thread(this, "PCGL-X11-epoll");
    }
 
    /** Creates an X11 TCP listener bound only to 127.0.0.1. */
    public XConnectorEpoll(TcpSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;
        serverFd = createLoopbackTcpSocket(socketConfig.port);
        if (serverFd < 0) throw new RuntimeException("Failed to create a loopback X11 TCP socket.");

        epollFd = createEpollFd();
        if (epollFd < 0) {
            closeFd(serverFd);
            throw new RuntimeException("Failed to create epoll fd.");
        }
        if (!addFdToEpoll(epollFd, serverFd)) {
            closeFd(serverFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add TCP server fd to epoll.");
        }

        shutdownFd = createEventFd();
        if (!addFdToEpoll(epollFd, shutdownFd)) {
            closeFd(serverFd);
            closeFd(shutdownFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }
        epollThread = new Thread(this, "PCGL-X11-TCP-epoll");
    }

    public synchronized void start() {
        if (running || epollThread == null) return;
        running = true;
        epollThread.start();
    }

    public synchronized void stop() {
        if (!running || epollThread == null) return;
        running = false;
        requestShutdown();
        if (Thread.currentThread() != epollThread) {
            while (epollThread.isAlive()) {
                try {
                    epollThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        epollThread = null;
    }

    @Override
    public void run() {
        while (running && doEpollIndefinitely(epollFd, serverFd, !multithreadedClients)) {
            // Native epoll wait dispatches accepted client descriptors.
        }
        shutdown();
    }

    private void handleNewConnection(int fd) {
        final Client client = new Client(this, new ClientSocket(fd));
        client.connected = true;
        if (multithreadedClients) {
            client.shutdownFd = createEventFd();
            client.pollThread = new Thread(() -> {
                connectionHandler.handleNewConnection(client);
                while (client.connected && waitForSocketRead(client.clientSocket.fd, client.shutdownFd)) {
                    // Per-client polling loop.
                }
            }, "PCGL-X11-client-" + fd);
            client.pollThread.start();
        } else {
            connectionHandler.handleNewConnection(client);
        }
        connectedClients.put(fd, client);
    }

    private void handleExistingConnection(int fd) {
        Client client = connectedClients.get(fd);
        if (client == null) return;
        XInputStream inputStream = client.getInputStream();
        try {
            if (inputStream != null) {
                if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;
                    while (running && requestHandler.handleRequest(client)) {
                        activePosition = inputStream.getActivePosition();
                    }
                    if (connectedClients.get(fd) == client) {
                        inputStream.setActivePosition(activePosition);
                    }
                } else {
                    killConnection(client);
                }
            } else {
                requestHandler.handleRequest(client);
            }
        } catch (IOException | RuntimeException e) {
            killConnection(client);
        }
    }

    public Client getClient(int fd) {
        return connectedClients.get(fd);
    }

    /** Returns a stable snapshot of live clients for server-originated events. */
    public Client[] snapshotConnectedClients() {
        Client[] snapshot = new Client[connectedClients.size()];
        for (int i = 0; i < connectedClients.size(); i++) {
            snapshot[i] = connectedClients.valueAt(i);
        }
        return snapshot;
    }

    public void killConnection(Client client) {
        if (client == null || connectedClients.get(client.clientSocket.fd) != client) return;
        client.connected = false;
        connectionHandler.handleConnectionShutdown(client);
        if (multithreadedClients) {
            if (Thread.currentThread() != client.pollThread) {
                client.requestShutdown();
                while (client.pollThread.isAlive()) {
                    try {
                        client.pollThread.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                client.pollThread = null;
            }
            closeFd(client.shutdownFd);
        } else {
            removeFdFromEpoll(epollFd, client.clientSocket.fd);
        }
        closeFd(client.clientSocket.fd);
        connectedClients.remove(client.clientSocket.fd);
    }

    /** Disconnect a client after a protocol violation. */
    public void disconnectClient(Client client) {
        killConnection(client);
    }

    private void shutdown() {
        while (connectedClients.size() > 0) {
            Client client = connectedClients.valueAt(connectedClients.size() - 1);
            killConnection(client);
        }
        removeFdFromEpoll(epollFd, serverFd);
        removeFdFromEpoll(epollFd, shutdownFd);
        closeFd(serverFd);
        closeFd(shutdownFd);
        closeFd(epollFd);
    }

    public int getInitialInputBufferCapacity() { return initialInputBufferCapacity; }
    public void setInitialInputBufferCapacity(int value) { initialInputBufferCapacity = value; }
    public int getInitialOutputBufferCapacity() { return initialOutputBufferCapacity; }
    public void setInitialOutputBufferCapacity(int value) { initialOutputBufferCapacity = value; }
    public boolean isMultithreadedClients() { return multithreadedClients; }
    public void setMultithreadedClients(boolean value) { multithreadedClients = value; }
    public boolean isCanReceiveAncillaryMessages() { return canReceiveAncillaryMessages; }
    public void setCanReceiveAncillaryMessages(boolean value) { canReceiveAncillaryMessages = value; }

    private void requestShutdown() {
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(8);
            data.asLongBuffer().put(1);
            new ClientSocket(shutdownFd).write(data);
        } catch (IOException ignored) {
        }
    }

    public static native void closeFd(int fd);
    private native int createEpollFd();
    private native int createEventFd();
    private native boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll);
    private native boolean addFdToEpoll(int epollFd, int fd);
    private native void removeFdFromEpoll(int epollFd, int fd);
    private native boolean waitForSocketRead(int clientFd, int shutdownFd);
    private native int createAFUnixSocket(String path);
    private native int createLoopbackTcpSocket(int port);
}
