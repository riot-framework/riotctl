package riot.riotctl.internal;

import java.io.IOException;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import riot.riotctl.Logger;

public class HttpProxy {
    private static HttpProxy instance;
    private HttpProxyServer server = null;
    private final Set<SSHClient> clients = new HashSet<SSHClient>();
    private final Logger log;
    private int minPort, maxPort, port;

    /**
     * Ensure that at least one instance of the Socks proxy exists, returns its
     * port.
     * 
     * @param port
     *            the lowest port number to use for the proxy, e.g. 8080
     * @param log
     *            the logger class
     * @return a port number on which a Socks5 server is listening
     * @throws IOException
     *             if the proxy couldn't be started
     */
    public static synchronized HttpProxy ensureProxy(int port, Logger log) throws IOException {
        if (instance != null) {
            return instance;
        }
        instance = new HttpProxy(port, port + 128, log);
        return instance;
    }

    public HttpProxy(int minPort, int maxPort, Logger log) throws IOException {
        this.minPort = minPort;
        this.maxPort = maxPort;
        this.server = getSocket(minPort, maxPort);
        this.log = log;
    }

    private HttpProxyServer getSocket(int minPort, int maxPort) throws IOException {
        try {
            // Will throw exception if port is in use
            HttpProxyServer server = DefaultHttpProxyServer.bootstrap().withPort(minPort).start();
            port = minPort;
            return server;
        } catch (Exception e) {
            if (minPort == maxPort) {
                throw e;
            }
            return getSocket(minPort + 1, maxPort);
        }
    }

    private static boolean available(int port) {
        try (Socket ignored = new Socket("localhost", port)) {
            return false;
        } catch (IOException ignored) {
            return true;
        }
    }

    public int getPort() {
        return port;
    }

    public synchronized void registerClient(SSHClient sshClient) throws IOException {
        clients.add(sshClient);
        if (server == null) {
            this.server = getSocket(minPort, maxPort);
        }
    }

    public synchronized void unregisterClient(SSHClient sshClient) throws IOException {
        clients.remove(sshClient);
        if (clients.isEmpty()) {
            server.stop();
            server = null;
        }
    }
}
