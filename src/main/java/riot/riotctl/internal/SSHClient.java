package riot.riotctl.internal;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import riot.riotctl.Logger;
import riot.riotctl.Target;

public class SSHClient implements Closeable {
	private static final String PTY_TYPE = "vanilla";
	private static final String LOCALE = "en_GB.UTF-8";

	private static JSch jsch = new JSch();

	private final Session session;
	private final Logger log;
	private SocksProxy proxy;

	public SSHClient(Target target, Logger log) throws IOException {
		this(target.hostname, target.username, target.password, log);
	}

	public SSHClient(String hostname, String username, String password, Logger log) throws IOException {
		this.log = log;

		log.info("Opening session to " + hostname);
		try {
			session = jsch.getSession(username, hostname);
			session.setConfig("StrictHostKeyChecking", "no");
			session.setPassword(password);
			session.setUserInfo(new UserInfo(log));
			session.connect(8000);
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
		final ChannelExec channel;

		try {
			channel = (ChannelExec) session.openChannel("exec");
			channel.setPtyType(PTY_TYPE);
			channel.setEnv("LC_ALL", LOCALE);
			channel.setInputStream(null);
			channel.setCommand(command);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		InputStream in = channel.getInputStream();
		InputStream err = channel.getExtInputStream();
		StringBuilder result = new StringBuilder();
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
				result.append(new String(tmp, 0, i).trim());
			}
			while (err.available() > 0) {
				int i = err.read(tmp, 0, 1024);
				if (i < 0)
					break;
				result.append(new String(tmp, 0, i));
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
			if (result.length() > 0)
				log.error(result.toString());
			throw new IOException("Operation returned exit status " + rc);
		} else {
			if (result.length() > 0)
				log.debug(result.toString());
		}

		channel.disconnect();
		return rc;
	}

	public void scpDir(File lDir, String rDir) throws IOException {
		exec("sudo mkdir -p " + rDir, true);
		for (File lFile : lDir.listFiles()) {
			final String rFile = rDir + '/' + lFile.getName();
			if (lFile.isDirectory()) {
				scpDir(lFile, rFile);
			} else {
				scpFile(lFile, rFile);
			}
		}
	}

	public void scpFile(File lFile, String rFileName) throws IOException {
		final ChannelExec channel;

		try {
			channel = (ChannelExec) session.openChannel("exec");
			channel.setPtyType(PTY_TYPE);
			channel.setEnv("LC_ALL", LOCALE);
			channel.setInputStream(null);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		// exec 'scp -t rfile' remotely
		rFileName = rFileName.replace("'", "'\"'\"'");
		channel.setCommand("sudo scp -p -t '" + rFileName + "'");

		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();

		try {
			channel.connect(3000);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
		if (checkAck(in) != 0) {
			return;
		}

		String lFileName = lFile.getName();
		String command;
		FileInputStream fis;

		command = "T" + (lFile.lastModified() / 1000) + " 0";
		command += (" " + (System.currentTimeMillis() / 1000) + " 0\n");
		out.write(command.getBytes());
		out.flush();
		if (checkAck(in) != 0) {
			return;
		}

		// send "C0644 filesize filename", where filename should not include '/'
		long filesize = lFile.length();
		command = "C0644 " + filesize + " ";
		if (rFileName.lastIndexOf('/') > 0) {
			command += rFileName.substring(rFileName.lastIndexOf('/') + 1);
		} else {
			command += rFileName;
		}
		command += "\n";
		out.write(command.getBytes());
		out.flush();
		if (checkAck(in) != 0) {
			return;
		}

		// send a content of lfile
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
		if (checkAck(in) != 0) {
			return;
		}
		out.close();

		channel.disconnect();
	}

	public void scpString(String payload, String rDirName, String rFileName) throws IOException {
		final ChannelExec channel;

		try {
			channel = (ChannelExec) session.openChannel("exec");
			channel.setPtyType(PTY_TYPE);
			channel.setEnv("LC_ALL", LOCALE);
			channel.setInputStream(null);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}

		// exec 'scp -t rfile' remotely
		rDirName = rDirName.replace("'", "'\"'\"'");
		channel.setCommand("sudo scp -p -t '" + rDirName + "'");

		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();

		try {
			channel.connect(3000);
		} catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
		if (checkAck(in) != 0) {
			return;
		}

		String command;

		command = "T" + (System.currentTimeMillis() / 1000) + " 0";
		command += (" " + (System.currentTimeMillis() / 1000) + " 0\n");
		out.write(command.getBytes());
		out.flush();
		if (checkAck(in) != 0) {
			return;
		}

		command = "C0644 " + payload.length() + " " + rFileName + "\n";
		out.write(command.getBytes());
		out.flush();
		if (checkAck(in) != 0) {
			return;
		}

		// send the content of payload as bytes
		out.write(payload.getBytes("UTF-8"));

		// send '\0'
		out.write(0);
		out.flush();
		if (checkAck(in) != 0) {
			return;
		}
		out.close();

		channel.disconnect();
	}

	/**
	 * Check for SCP Acknowledge return code; 0 for success, 1 for error, 2 for
	 * fatal error.
	 */
	private int checkAck(InputStream in) throws IOException {
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
