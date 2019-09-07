package riot.riotctl.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

import org.connectbot.simplesocks.Socks5Server;
import org.connectbot.simplesocks.Socks5Server.ResponseCode;

import riot.riotctl.Logger;

public class SocksProxy implements Runnable {
	private static SocksProxy instance;
	private final ServerSocket serverSocket;
	private final Set<SSHClient> clients = new HashSet<SSHClient>();
	private final Logger log;
	private int port;
	private boolean running;

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
		this.serverSocket = getSocket(minPort, maxPort);
		this.log = log;
	}

	private ServerSocket getSocket(int minPort, int maxPort) throws IOException {
		try {
			port = minPort;
			return new ServerSocket(port);
		} catch (IOException e) {
			if (minPort == maxPort) {
				throw e;
			}
			return getSocket(minPort + 1, maxPort);
		}
	}

	public int getPort() {
		return port;
	}

	@Override
	public void run() {
		try {
			while (running) {
				log.debug("Socks5 proxy running on port " + port);
				respondAsync(serverSocket.accept().getInputStream());
			}
		} catch (IOException e) {
			if (running == true) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	private void respondAsync(InputStream socket) {
		new Thread(new Runnable() {

			@Override
			public void run() {
				try {
					Socket outboundSocket = new Socket();
					Socks5Server proxy = new Socks5Server(socket, outboundSocket.getOutputStream());

					if (proxy.acceptAuthentication() && proxy.readRequest()) {
						proxy.sendReply(ResponseCode.SUCCESS);
						outboundSocket.close();
						socket.close();
					} else {
						log.error("SOCKS5 authentication failed");
					}
				} catch (IOException e) {
					e.printStackTrace();
					log.error("SOCKS5: " + e.getMessage());
				}
			}

		}).start();
	}

	public synchronized void registerClient(SSHClient sshClient) {
		clients.add(sshClient);
		if (running = false) {
			running = true;
			new Thread(instance, "Socks5 Proxy").start();
		}
	}

	public synchronized void unregisterClient(SSHClient sshClient) {
		clients.remove(sshClient);
		if (clients.isEmpty()) {
			running = false;
			try {
				serverSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
				log.warn("Unable to close proxy port: " + e.getMessage());
			}
		}
	}
}
