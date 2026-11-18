package com.suksesjt12.pcgamelauncher.x11.transport;

import java.io.IOException;

public interface RequestHandler {
    boolean handleRequest(Client client) throws IOException;
}
