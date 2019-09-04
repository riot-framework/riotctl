package riot.riotctl.internal;

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

	private static final int TIMEOUT = 8000;

	private static JSch jsch = new JSch();

	private final Session session;
	private final Logger log;
	private SocksProxy proxy;

	public SSHClient(HostInfo target, Logger log) throws IOException {
		this(target.getHost().getHostName(), target.getUsername(), target.getPassword(), log);
	}

	public SSHClient(String hostname, String username, String password, Logger log) throws IOException {
		this.log = log;

		log.info("Opening session to " + hostname);
		try {
			session = jsch.getSession(username, hostname);
			session.setConfig("StrictHostKeyChecking", "no");
			session.setUserInfo(new UserInfo(log, password));
			session.connect(TIMEOUT);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public void setProxy(SocksProxy proxy) throws IOException {
		try {
			if (this.proxy != null) {
				resetProxy();
			}
			this.proxy = proxy;
			proxy.registerClient(this);
			session.setPortForwardingR(8080, "localhost", proxy.getPort());
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	public void resetProxy() throws IOException {
		try {
			session.delPortForwardingR(8080);
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

		byte[] tmp = new byte[1024];
		while (true) {
			while (in.available() > 0) {
				int i = in.read(tmp, 0, 1024);
				if (i < 0)
					break;
				log.debug(new String(tmp, 0, i).trim());
			}
			while (err.available() > 0) {
				int i = err.read(tmp, 0, 1024);
				if (i < 0)
					break;
				log.error(new String(tmp, 0, i));
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

		if (checkRc && rc != 0) {
			throw new IOException("Operation returned exit status " + rc);
		}

		channel.disconnect();
		return rc;
	}

	public void copyDir(File lDir, String rDir) throws IOException {
		exec("sudo mkdir -p " + rDir, true);
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
	 */
	public void copy(File lFile, String rFileName) throws IOException {
		final ChannelExec channel = openExecChannel();
		rFileName = rFileName.replace("'", "'\"'\"'");
		channel.setCommand("sudo scp -p -t '" + rFileName + "'");

		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();

		try {
			channel.connect(3000);
			if (receiveAck(in) != 0) {
				return;
			}
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		sendTMessage(out, lFile.lastModified(), System.currentTimeMillis());
		if (receiveAck(in) != 0) {
			return;
		}

		sendCMessage(out, "0644", rFileName, lFile.length());
		if (receiveAck(in) != 0) {
			return;
		}

		sendFileContents(out, lFile);
		if (receiveAck(in) != 0) {
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
	 * @param rDirName
	 *            the directory in which to store the file
	 * @param rFileName
	 *            the file name
	 * @throws IOException
	 */
	public void write(String payload, String rDirName, String rFileName) throws IOException {
		final ChannelExec channel = openExecChannel();
		rDirName = rDirName.replace("'", "'\"'\"'");
		channel.setCommand("sudo scp -p -t '" + rDirName + "'");

		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();

		try {
			channel.connect(3000);
			if (receiveAck(in) != 0) {
				return;
			}
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		sendTMessage(out, System.currentTimeMillis(), System.currentTimeMillis());
		if (receiveAck(in) != 0) {
			return;
		}

		sendCMessage(out, "0644", rFileName, payload.length());
		if (receiveAck(in) != 0) {
			return;
		}

		// send the content of payload as bytes
		out.write(payload.getBytes("UTF-8"));

		// send '\0'
		out.write(0);
		out.flush();
		if (receiveAck(in) != 0) {
			return;
		}

		out.close();
		channel.disconnect();
	}

	/**
	 * Open and configures an ExecChannel (PTY type, Locale...)
	 * 
	 * @throws IOException
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
	private int receiveAck(InputStream in) throws IOException {
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
			if (b == 1) { // error
				log.error(sb.toString());
			}
			if (b == 2) { // fatal error
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
