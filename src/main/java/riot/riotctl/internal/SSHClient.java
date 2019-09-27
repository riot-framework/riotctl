package riot.riotctl.internal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import riot.riotctl.Logger;
import riot.riotctl.discovery.HostInfo;

/**
 * Holds an SSH connection to a host and allows files to be copied to it, and
 * commands to be executed.
 */
public class SSHClient implements Closeable {
	private static final String PTY_TYPE = "vanilla";
	private static final String LOCALE = "en_GB.UTF-8";

	private static JSch jsch = new JSch();

	private final Session session;
	private final Logger log;
	private ProxyServer proxy;

	public SSHClient(HostInfo target, Logger log) throws IOException {
		this(target.getHost().getHostName(), target.getUsername(), target.getPassword(), log);
	}

	public SSHClient(String hostname, String username, String password, Logger log) throws IOException {
		this.log = log;

		log.info("Opening session to " + hostname);
		try {
			session = jsch.getSession(username, hostname);
			session.setConfig("StrictHostKeyChecking", "no");
			session.setUserInfo(new SSHUserInfo(log, password));
			try {
				session.connect(2000);
			} catch (JSchException e) {
				// Workaround: After a long downtime, some PIs don't reply to the first SSH
				// client. This seems to fix it reliable. Ping me if you have an idea why the
				// first connection attempt sometimes fails...
				session.connect();
			}
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public void setProxy(ProxyServer proxy) throws IOException {
		try {
			if (this.proxy != null) {
				resetProxy();
			}
			this.proxy = proxy;
			proxy.registerClient(this);
			session.setPortForwardingR(proxy.getPort(), "localhost", proxy.getPort());
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public void resetProxy() throws IOException {
		try {
			session.delPortForwardingR(proxy.getPort());
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
		proxy.unregisterClient(this);
		this.proxy = null;
	}

	public int exec(String command, boolean checkRc) throws IOException {
		final ChannelExec channel = openExecChannel();
		channel.setCommand(command);

		InputStream in = channel.getInputStream();
		InputStream err = channel.getExtInputStream();
		int rc = Integer.MIN_VALUE;

		try {
			channel.connect(3000);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		StringBuilder debug = new StringBuilder();
		StringBuilder error = new StringBuilder();
		byte[] tmp = new byte[1024];
		while (true) {
			while (in.available() > 0) {
				int i = in.read(tmp, 0, 1024);
				if (i < 0)
					break;
				debug.append(new String(tmp, 0, i));
			}
			while (err.available() > 0) {
				int i = err.read(tmp, 0, 1024);
				if (i < 0)
					break;
				error.append(new String(tmp, 0, i));
			}
			if (debug.length() > 0 && debug.charAt(debug.length() - 1) == '\n') {
				log.debug(debug.toString().trim());
				debug = new StringBuilder();
			}
			if (error.length() > 0 && error.charAt(error.length() - 1) == '\n') {
				// StdErr is often used for status messages, e.g. by systemctl
				log.info(error.toString().trim());
			}

			if (channel.isClosed()) {
				if ((in.available() > 0) || (err.available() > 0))
					continue;
				rc = channel.getExitStatus();
				break;
			}
			try {
				Thread.sleep(1000);
			} catch (Exception ee) {
			}
		}

		if (debug.length() > 0) {
			log.debug(debug.toString().trim());
		}
		if (debug.length() > 0) {
			log.error(error.toString().trim());
		}

		if (checkRc && rc != 0) {
			throw new IOException("Operation returned exit status " + rc);
		}

		channel.disconnect();
		return rc;
	}

	public int run(String command, InputStream stdIn) throws IOException {
		final ChannelExec channel = openExecChannel();
		channel.setCommand(command);

		InputStream in = channel.getInputStream();
		InputStream err = channel.getExtInputStream();
		OutputStream out = channel.getOutputStream();
		int rc = Integer.MIN_VALUE;
		int enterCount = 0;

		try {
			channel.connect(3000);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		log.info("To stop, press <Enter> twice.");

		byte[] tmp = new byte[1024];
		run: while (true) {
			while (in.available() > 0) {
				int i = in.read(tmp, 0, 1024);
				if (i < 0)
					break;
				log.info(new String(tmp, 0, i).trim());
			}
			while (err.available() > 0) {
				int i = err.read(tmp, 0, 1024);
				if (i < 0)
					break;
				log.error(new String(tmp, 0, i));
			}
			if (stdIn.available() > 0) {
				int i = stdIn.read(tmp, 0, 1024);
				if (i < 0)
					break;
				for (int j = 0; j < i; j++) {
					if (tmp[j] == '\n') {
						if (++enterCount > 1) {
							break run;
						}
					} else {
						enterCount = 0;
					}
				}
				out.write(tmp, 0, i);
			}
			if (channel.isClosed()) {
				if ((in.available() > 0) || (err.available() > 0))
					continue;
				rc = channel.getExitStatus();
				break;
			}
			try {
				Thread.sleep(1000);
			} catch (Exception ee) {
			}
		}

		channel.disconnect();
		return rc;
	}

	public void mkDir(String rDir) throws IOException {
		exec("sudo mkdir -p " + rDir, true);
	}

	public void copyDir(File lDir, String rDir) throws IOException {
		mkDir(rDir);
		for (File lFile : lDir.listFiles()) {
			final String rFile = rDir + '/' + lFile.getName();
			if (lFile.isDirectory()) {
				copyDir(lFile, rFile);
			} else {
				copy(lFile, rFile);
			}
		}
	}

	/**
	 * Copies a single file via SCP
	 * 
	 * @param lFile
	 *            the file to copy
	 * @param rFileName
	 *            the file name and directory to which to copy the file
	 * @throws IOException
	 *             if copying fails
	 */
	public void copy(File lFile, String rFileName) throws IOException {
		final ChannelExec channel = openExecChannel();
		rFileName = rFileName.replace("'", "'\"'\"'");
		channel.setCommand("sudo scp -p -t '" + rFileName + "'");

		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();

		try {
			channel.connect(3000);
			if (receiveAck(in, false) != 0) {
				return;
			}
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		sendTMessage(out, lFile.lastModified(), System.currentTimeMillis());
		if (receiveAck(in, false) != 0) {
			return;
		}

		sendCMessage(out, "0644", rFileName, lFile.length());
		if (receiveAck(in, false) != 0) {
			return;
		}

		sendFileContents(out, lFile);
		if (receiveAck(in, false) != 0) {
			return;
		}

		out.close();
		channel.disconnect();
	}

	/**
	 * Writes a file via SCP, with the contents of a string
	 * 
	 * @param payload
	 *            the file's content
	 * @param rFile
	 *            the remote file as which to store the contents
	 * @throws IOException
	 */
	public void write(String payload, String rFile) throws IOException {
		final ChannelExec channel = openExecChannel();
		rFile = rFile.replace("'", "'\"'\"'");
		channel.setCommand("sudo scp -p -t '" + rFile + "'");
		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();

		try {
			channel.connect(3000);
			if (receiveAck(in, false) != 0) {
				return;
			}
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		sendTMessage(out, System.currentTimeMillis(), System.currentTimeMillis());
		if (receiveAck(in, false) != 0) {
			return;
		}

		sendCMessage(out, "0644", rFile.substring(rFile.lastIndexOf('/') + 1), payload.length());
		if (receiveAck(in, false) != 0) {
			return;
		}

		// send the content of payload as bytes
		out.write(payload.getBytes("UTF-8"));

		// send '\0'
		out.write(0);
		out.flush();
		if (receiveAck(in, false) != 0) {
			return;
		}

		out.close();
		channel.disconnect();
	}

	/**
	 * Reads a file via SCP, returning the contents in a string
	 * 
	 * @param rFile
	 *            the file name
	 * @param suppressStdErr
	 *            whether errors should be redirected to the debug log instead of
	 *            the error log
	 * @throws IOException
	 *             if connection failed or reading the file aborted. This will NOT
	 *             be thrown if the file is not found, instead an empty String will
	 *             be returned.
	 * @return a String with the contents of the file, or an empty String if no such
	 *         file exists
	 */
	public String read(String rFile, boolean suppressStdErr) throws IOException {
		final ChannelExec channel = openExecChannel();
		rFile = rFile.replace("'", "'\"'\"'");
		rFile = "'" + rFile + "'";
		channel.setCommand("scp -f " + rFile);
		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try {
			channel.connect(3000);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		byte[] buf = new byte[1024];

		out.write(0);
		out.flush();

		while (true) {
			int c = receiveAck(in, suppressStdErr);
			if (c != 'C') {
				break;
			}

			// read file mask, e.g. '0644 '
			in.read(buf, 0, 5);

			long filesize = 0L; // unused
			while (true) {
				if (in.read(buf, 0, 1) < 0) {
					// error
					break;
				}
				if (buf[0] == ' ')
					break;
				filesize = filesize * 10L + (long) (buf[0] - '0');
			}

			String filename = null; // unused
			for (int i = 0;; i++) {
				in.read(buf, i, 1);
				if (buf[i] == (byte) 0x0a) {
					filename = new String(buf, 0, i);
					break;
				}
			}

			out.write(0);
			out.flush();

			int foo;
			while (true) {
				if (buf.length < filesize)
					foo = buf.length;
				else
					foo = (int) filesize;
				foo = in.read(buf, 0, foo);
				if (foo < 0) {
					// error
					break;
				}
				baos.write(buf, 0, foo);
				filesize -= foo;
				if (filesize == 0L)
					break;
			}
			baos.close();

			if (receiveAck(in, false) != 0) {
				return "";
			}

			out.write(0);
			out.flush();
		}

		channel.disconnect();

		return new String(baos.toByteArray(), "UTF-8");
	}

	/**
	 * Open and configures an ExecChannel (PTY type, Locale...)
	 * 
	 * @throws IOException
	 *             if the Channel couldn't be opened
	 */
	private ChannelExec openExecChannel() throws IOException {
		try {
			ChannelExec channel = (ChannelExec) session.openChannel("exec");
			channel.setPtyType(PTY_TYPE);
			channel.setEnv("LC_ALL", LOCALE);
			channel.setInputStream(null);
			return (ChannelExec) channel;
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Sends an SCP 'T' message with the file modification and access time
	 */
	private void sendTMessage(OutputStream out, long mtime, long atime) throws IOException {
		String command;
		command = "T" + (mtime / 1000) + " 0";
		command += (" " + (atime / 1000) + " 0\n");
		out.write(command.getBytes());
		out.flush();
	}

	/**
	 * Sends an SCP 'C' message with the file access rights mask, file name, and
	 * size
	 */
	private void sendCMessage(OutputStream out, String mask, String rFileName, long filesize) throws IOException {
		String command;
		command = "C" + mask + " " + filesize + " ";
		if (rFileName.lastIndexOf('/') > 0) {
			command += rFileName.substring(rFileName.lastIndexOf('/') + 1);
		} else {
			command += rFileName;
		}
		command += "\n";
		out.write(command.getBytes());
		out.flush();
	}

	/**
	 * Sends the actual file contents
	 */
	private void sendFileContents(OutputStream out, File lFile) throws FileNotFoundException, IOException {
		FileInputStream fis;
		fis = new FileInputStream(lFile);
		byte[] buf = new byte[1024];
		while (true) {
			int len = fis.read(buf, 0, buf.length);
			if (len <= 0)
				break;
			out.write(buf, 0, len); // out.flush();
		}
		fis.close();
		fis = null;
		// send '\0'
		buf[0] = 0;
		out.write(buf, 0, 1);
		out.flush();
	}

	/**
	 * Check for SCP Acknowledge return code; 0 for success, 1 for error, 2 for
	 * fatal error.
	 */
	private int receiveAck(InputStream in, boolean suppressErrors) throws IOException {
		int b = in.read();
		if (b == 0)
			return b;
		if (b == -1)
			return b;

		if (b == 1 || b == 2) {
			StringBuffer sb = new StringBuffer();
			int c;
			do {
				c = in.read();
				sb.append((char) c);
			} while (c != '\n');
			if (suppressErrors) {
				log.debug(sb.toString());
			} else if (b == 1) { // error
				log.error(sb.toString());
			} else if (b == 2) { // fatal error
				log.error(sb.toString());
			}
		}
		return b;
	}

	public String getHost() {
		return session.getHost();
	}

	public String getUsername() {
		return session.getUserName();
	}

	@Override
	public void close() throws IOException {
		session.disconnect();
	}

}
