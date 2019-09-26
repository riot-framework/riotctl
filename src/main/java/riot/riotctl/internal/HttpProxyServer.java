package riot.riotctl.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import riot.riotctl.Logger;

// based on https://stackoverflow.com/a/27179532/2092587
public class HttpProxyServer extends Thread {
	private static final Pattern CONNECT = Pattern.compile("CONNECT (.+):(.+) HTTP/(1\\.[01])",
	        Pattern.CASE_INSENSITIVE);
	private static final Pattern GET = Pattern.compile("GET (.+) HTTP/(1\\.[01])", Pattern.CASE_INSENSITIVE);

	private static HttpProxyServer instance;
	private final ServerSocket serverSocket;
	private final Logger log;

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
	public static synchronized HttpProxyServer ensureProxy(int port, Logger log) throws IOException {
		if (instance != null) {
			return instance;
		}
		instance = new HttpProxyServer(port, port + 128, log);
		instance.start();
		return instance;
	}

	private HttpProxyServer(int minPort, int maxPort, Logger log) throws IOException {
		this.serverSocket = getSocket(minPort, maxPort, log);
		this.log = log;
	}

	@Override
	public void run() {
		Socket socket;
		try {
			while ((socket = serverSocket.accept()) != null) {
				(new Handler(socket, log)).start();
			}
		} catch (IOException e) {
			log.error(e.getMessage());
			e.printStackTrace();
		}
	}

	private ServerSocket getSocket(int minPort, int maxPort, Logger log) throws IOException {
		try {
			ServerSocket server = new ServerSocket(minPort); // Will throw exception if port is in use
			log.debug("Starting HTTP proxy on port " + minPort);
			return server;
		} catch (IOException e) {
			if (minPort == maxPort) {
				throw e;
			}
			return getSocket(minPort + 1, maxPort, log);
		}
	}

	public static class Handler extends Thread {
		private final Socket clientSocket;
		private boolean previousWasR = false;
		private final Logger log;

		public Handler(Socket clientSocket, Logger log) {
			this.clientSocket = clientSocket;
			this.log = log;
		}

		@Override
		public void run() {
			try {
				String request = readLine(clientSocket);
				log.debug("Request: " + request);
				Matcher connect = CONNECT.matcher(request);
				Matcher get = GET.matcher(request);
				if (connect.matches()) {
					handleRequest(connect.group(1), Integer.parseInt(connect.group(2)), connect.group(3));
				} else if (get.matches()) {
					handleRequest(get.group(1), getPort(get.group(1)), get.group(2));
				} else {
					log.warn("Proxy unable to serve request " + request);
				}
			} catch (IOException e) {
				e.printStackTrace(); // TODO: implement catch
			} finally {
				try {
					clientSocket.close();
				} catch (IOException e) {
					e.printStackTrace(); // TODO: implement catch
				}
			}
		}

		private int getPort(String group) throws IOException {
			final String url = group.toLowerCase();
			if (url.startsWith("https"))
				return 443;
			if (url.startsWith("http"))
				return 80;
			throw new IOException("Unknown protocol!");
		}

		private void handleRequest(String host, int port, String httpVersion)
		        throws IOException, UnsupportedEncodingException {
			String header;
			do {
				header = readLine(clientSocket);
			} while (!"".equals(header));
			OutputStreamWriter outputStreamWriter = new OutputStreamWriter(clientSocket.getOutputStream(),
			        "ISO-8859-1");

			final Socket forwardSocket;
			try {
				forwardSocket = new Socket(host, port);
				System.out.println(forwardSocket);
			} catch (IOException | NumberFormatException e) {
				e.printStackTrace();
				outputStreamWriter.write("HTTP/" + httpVersion + " 502 Bad Gateway\r\n");
				outputStreamWriter.write("Proxy-agent: Simple/0.1\r\n");
				outputStreamWriter.write("\r\n");
				outputStreamWriter.flush();
				return;
			}
			try {
				outputStreamWriter.write("HTTP/" + httpVersion + " 200 Connection established\r\n");
				outputStreamWriter.write("Proxy-agent: Simple/0.1\r\n");
				outputStreamWriter.write("\r\n");
				outputStreamWriter.flush();

				Thread remoteToClient = new Thread() {
					@Override
					public void run() {
						forwardData(forwardSocket, clientSocket);
					}
				};
				remoteToClient.start();
				try {
					if (previousWasR) {
						int read = clientSocket.getInputStream().read();
						if (read != -1) {
							if (read != '\n') {
								forwardSocket.getOutputStream().write(read);
							}
							forwardData(clientSocket, forwardSocket);
						} else {
							if (!forwardSocket.isOutputShutdown()) {
								forwardSocket.shutdownOutput();
							}
							if (!clientSocket.isInputShutdown()) {
								clientSocket.shutdownInput();
							}
						}
					} else {
						forwardData(clientSocket, forwardSocket);
					}
				} finally {
					try {
						remoteToClient.join();
					} catch (InterruptedException e) {
						e.printStackTrace(); // TODO: implement catch
					}
				}
			} finally {
				forwardSocket.close();
			}
		}

		private static void forwardData(Socket inputSocket, Socket outputSocket) {
			try {
				InputStream inputStream = inputSocket.getInputStream();
				try {
					OutputStream outputStream = outputSocket.getOutputStream();
					try {
						byte[] buffer = new byte[4096];
						int read;
						do {
							read = inputStream.read(buffer);
							if (read > 0) {
								outputStream.write(buffer, 0, read);
								if (inputStream.available() < 1) {
									outputStream.flush();
								}
							}
						} while (read >= 0);
					} finally {
						if (!outputSocket.isOutputShutdown()) {
							outputSocket.shutdownOutput();
						}
					}
				} finally {
					if (!inputSocket.isInputShutdown()) {
						inputSocket.shutdownInput();
					}
				}
			} catch (IOException e) {
				e.printStackTrace(); // TODO: implement catch
			}
		}

		private String readLine(Socket socket) throws IOException {
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			int next;
			readerLoop: while ((next = socket.getInputStream().read()) != -1) {
				if (previousWasR && next == '\n') {
					previousWasR = false;
					continue;
				}
				previousWasR = false;
				switch (next) {
				case '\r':
					previousWasR = true;
					break readerLoop;
				case '\n':
					break readerLoop;
				default:
					byteArrayOutputStream.write(next);
					break;
				}
			}
			return byteArrayOutputStream.toString("ISO-8859-1");
		}
	}

	public int getPort() {
		return serverSocket.getLocalPort();
	}

	public void registerClient(SSHClient sshClient) {
		// TODO Auto-generated method stub

	}

	public void unregisterClient(SSHClient sshClient) {
		// TODO Auto-generated method stub

	}
}