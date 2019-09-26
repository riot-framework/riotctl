package riot.riotctl.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import riot.riotctl.Logger;

// based on https://stackoverflow.com/a/27179532/2092587
public class HttpProxyServer extends Thread {
    private static final Pattern CONNECT = Pattern.compile("CONNECT (.+):(.+) HTTP/(1\\.[01])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GETPOST = Pattern
            .compile("(GET|POST) (?:http)://([^/:]*)(?::([^/]*))?(/.*) HTTP/(1\\.[01])", Pattern.CASE_INSENSITIVE);
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

        private final Logger log;

        private final Socket clientSocket;
        private boolean previousWasR = false;

        public Handler(Socket clientSocket, Logger log) {
            this.clientSocket = clientSocket;
            this.log = log;
        }

        @Override
        public void run() {
            try {
                String request = readLine(clientSocket, Integer.MAX_VALUE);

                Matcher connectMatcher = CONNECT.matcher(request);
                Matcher getNpostMatcher = GETPOST.matcher(request);

                System.out.println("Request: " + request);
                if (connectMatcher.matches()) {
                    // ...

                } else if (getNpostMatcher.matches()) {
                    String method = getNpostMatcher.group(1);
                    String hostString = getNpostMatcher.group(2);
                    String portString = getNpostMatcher.group(3);
                    String lengthString = null;
                    String line;
                    ArrayList<String> buffer = new ArrayList<String>();
                    Integer port = portString == null || "".equals(portString) ? 80 : Integer.parseInt(portString);
                    Integer length = null;

                    buffer.add(request);
                    while ((line = readLine(clientSocket, Integer.MAX_VALUE)) != null) {
                        buffer.add(line);

                        if ("".equals(line))
                            break;

                        if (lengthString == null && line.startsWith("Content-Length: ")) {
                            lengthString = line.substring(16);
                            length = Integer.parseInt(lengthString);
                        }
                    }

                    try {
                        final Socket forwardSocket;
                        try {
                            forwardSocket = new Socket(hostString, port);
                            System.out.println("  " + forwardSocket);

                        } catch (IOException | NumberFormatException e) {
                            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(
                                    clientSocket.getOutputStream(), "ISO-8859-1");

                            e.printStackTrace();
                            outputStreamWriter.write("HTTP/" + connectMatcher.group(3) + " 502 Bad Gateway\r\n");
                            outputStreamWriter.write("Proxy-agent: Simple/0.1\r\n");
                            outputStreamWriter.write("\r\n");
                            outputStreamWriter.flush();
                            return;
                        }

                        PrintWriter printWriter = new PrintWriter(forwardSocket.getOutputStream());

                        for (String bufferedLine : buffer) {
                            printWriter.println(bufferedLine);
                        }
                        printWriter.flush();

                        if ("POST".equals(method) && length > 0) {
                            System.out.println("Posting data ...");
                            if (previousWasR) { // skip \n if existing
                                int read = clientSocket.getInputStream().read();
                                if (read != '\n') {
                                    forwardSocket.getOutputStream().write(read);
                                }
                                forwardData(clientSocket, forwardSocket, length, true);
                            } else {
                                forwardData(clientSocket, forwardSocket, length, true);
                            }
                        }

                        System.out.println("Forwarding response ...");
                        forwardData(forwardSocket, clientSocket, null, false);

                        if (forwardSocket != null) {
                            forwardSocket.close();
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private static void forwardData(Socket inputSocket, Socket outputSocket, Integer length, boolean isPost) {
            try {
                InputStream inputStream = inputSocket.getInputStream();
                try {
                    OutputStream outputStream = outputSocket.getOutputStream();
                    try {
                        byte[] buffer = new byte[4096];
                        int read;
                        if (length == null || length > 0) {
                            do {
                                if ((read = inputStream.read(buffer)) > 0) {
                                    outputStream.write(buffer, 0, read);
                                    if (inputStream.available() < 1) {
                                        outputStream.flush();
                                    }
                                    if (length != null) {
                                        length = length - read;
                                    }
                                }
                            } while (read >= 0 && (length == null || length > 0));
                        }
                    } finally {
                        if (!outputSocket.isOutputShutdown()) {
                            if (!isPost) {
                                outputSocket.shutdownOutput();
                            }
                        }
                    }
                } finally {
                    if (!inputSocket.isInputShutdown()) {
                        inputSocket.shutdownInput();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private String readLine(Socket socket, Integer noOfBytes) throws IOException {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            int next;
            readerLoop: while (noOfBytes-- > 0 && (next = socket.getInputStream().read()) != -1) {
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