package riot.riotctl.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

import riot.riotctl.Logger;
import sockslib.common.methods.NoAuthenticationRequiredMethod;
import sockslib.server.SocksProxyServer;
import sockslib.server.SocksServerBuilder;

public class SocksProxy {
	private static SocksProxy instance;
	private SocksProxyServer server = null;
	private final Set<SSHClient> clients = new HashSet<SSHClient>();
	private final Logger log;
	private int minPort, maxPort;

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
	public static synchronized SocksProxy ensureProxy(int port, Logger log) throws IOException {
		if (instance != null) {
			return instance;
		}
		instance = new SocksProxy(port, port + 128, log);
		return instance;
	}

	public SocksProxy(int minPort, int maxPort, Logger log) throws IOException {
		this.minPort = minPort;
		this.maxPort = maxPort;
		this.server = getSocket(minPort, maxPort);
		this.log = log;
	}

	private SocksProxyServer getSocket(int minPort, int maxPort) throws IOException {
		try {
			SocksProxyServer server = SocksServerBuilder.newSocks5ServerBuilder()
					.setSocksMethods(new NoAuthenticationRequiredMethod()).setBindAddr(InetAddress.getLocalHost()).setBindPort(minPort).build();
			server.start(); // Will throw exception if port is in use
			return server;
		} catch (IOException e) {
			if (minPort == maxPort) {
				throw e;
			}
			return getSocket(minPort + 1, maxPort);
		}
	}

	public int getPort() {
		return server.getBindPort();
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
			server.shutdown();
			server = null;
		}
	}
}
