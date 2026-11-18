package com.suksesjt12.pcgamelauncher.x11.transport;

import java.io.IOException;

public interface XStreamLock extends AutoCloseable {
    void close() throws IOException;
}
