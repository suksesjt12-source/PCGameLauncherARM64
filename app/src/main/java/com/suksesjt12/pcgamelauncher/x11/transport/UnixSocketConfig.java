package com.suksesjt12.pcgamelauncher.x11.transport;

import java.io.File;
import java.io.IOException;

/** Creates an app-private AF_UNIX socket path below the supplied runtime root. */
public final class UnixSocketConfig {
    public static final String XSERVER_RELATIVE_PATH = "usr/tmp/.X11-unix/X0";

    public final String path;

    private UnixSocketConfig(String path) {
        this.path = path;
    }

    public static UnixSocketConfig createSocket(String rootPath, String relativePath) {
        if (rootPath == null || rootPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Runtime root path is empty.");
        }
        if (relativePath == null || relativePath.trim().isEmpty() ||
                new File(relativePath).isAbsolute()) {
            throw new IllegalArgumentException("Socket path must be relative to the runtime root.");
        }

        try {
            File root = new File(rootPath).getCanonicalFile();
            File socketFile = new File(root, relativePath).getCanonicalFile();
            String rootPrefix = root.getPath() + File.separator;
            if (!socketFile.getPath().startsWith(rootPrefix)) {
                throw new SecurityException("X11 socket path escapes the runtime root.");
            }

            File parent = socketFile.getParentFile();
            if (parent == null || !parent.getCanonicalPath().startsWith(rootPrefix)) {
                throw new SecurityException("Invalid X11 socket parent directory.");
            }
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IllegalStateException("Cannot create X11 socket directory: " + parent);
            }
            if (socketFile.exists() && !socketFile.delete()) {
                throw new IllegalStateException("Cannot remove stale X11 socket: " + socketFile);
            }
            return new UnixSocketConfig(socketFile.getPath());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot resolve X11 socket path.", error);
        }
    }
}
