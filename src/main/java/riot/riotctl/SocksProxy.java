package riot.riotctl;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import org.connectbot.simplesocks.Socks5Server;
import org.connectbot.simplesocks.Socks5Server.ResponseCode;

public class SocksProxy implements Runnable, Closeable {
	private final ServerSocket serverSocket;
	private final Logger log;
	private int port;
	private boolean running;

	public SocksProxy(int port, Logger log) throws IOException {
		this(port, port + 64, log);
	}

	public SocksProxy(int minPort, int maxPort, Logger log) throws IOException {
		this.running = true;
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
				Socks5Server proxy = new Socks5Server(serverSocket.accept().getInputStream(),
						new Socket().getOutputStream());

				if (proxy.acceptAuthentication() && proxy.readRequest()) {
					proxy.sendReply(ResponseCode.SUCCESS);
				} else {

				}
			}
		} catch (IOException e) {

		}
	}

	public void close() throws IOException {
		running = false;
		serverSocket.close();
	}
}
