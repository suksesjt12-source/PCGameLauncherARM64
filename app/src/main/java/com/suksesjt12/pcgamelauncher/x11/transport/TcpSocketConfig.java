package com.suksesjt12.pcgamelauncher.x11.transport;

/** Loopback-only TCP endpoint; X11 display number N maps to TCP port 6000 + N. */
public final class TcpSocketConfig {
    public final int port;

    public TcpSocketConfig(int port) {
        if (port < 6000 || port > 65535) {
            throw new IllegalArgumentException("X11 TCP port must be in the range 6000..65535.");
        }
        this.port = port;
    }

    public int getDisplayNumber() {
        return port - 6000;
    }

    public String getDisplayEndpoint() {
        return "127.0.0.1:" + getDisplayNumber();
    }
}
