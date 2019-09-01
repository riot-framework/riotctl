package riot.riotctl;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import riot.riotctl.steps.SocksProxy;

public class RiotCtlTool implements Closeable {

	private static final String PTY_TYPE = "vanilla";
	private static final String LOCALE = "en_GB.UTF-8";

	private JSch jsch = new JSch();

	private final String packageName;
	private final List<Session> sessions = new ArrayList<Session>();
	private final Logger log;

	public RiotCtlTool(String packageName, List<Target> targets, Logger log) {
		super();
		this.packageName = packageName;
		this.log = log;

		for (Target target : targets) {

			// new BonjourProbe(log, target).discover(5000);
			try {
				log.info("Opening session to " + target.hostname);
				Session session = jsch.getSession(target.username, target.hostname);
				session.setConfig("StrictHostKeyChecking", "no");
				session.setPassword(target.password);
				session.setUserInfo(new UserInfo(log));
				session.connect(8000);
				sessions.add(session);
			} catch (JSchException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void ensurePackages(String packages) {
		for (Session session : sessions) {
			try {
				SocksProxy proxy = new SocksProxy(8080, log);
				new Thread(proxy).start();
				session.setPortForwardingR(8080, "localhost", proxy.getPort());

				// TODO: Skip this if it has been checked before (use a file in etc)
				log.info("Checking dependencies " + packages + " on " + session.getHost());

				final String aptOptions = "-o Acquire::http::proxy=\"http://localhost:8080\"";
				final String aptUpdateCmd = "sudo apt-get " + aptOptions + " update";
				final String aptInstallCmd = "sudo apt-get " + aptOptions + " install " + packages;

				// Update package list if it's over a month old:
				exec(session, "find /var/cache/apt/pkgcache.bin -mtime +30 -exec " + aptUpdateCmd + " \\;", false);

				// Update the packages:
				exec(session, aptInstallCmd, true);

				session.delPortForwardingR(8080);
				proxy.close();
			} catch (Exception e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void run(File source) {
		for (Session session : sessions) {
			try {
				log.info("Copying " + packageName + " to " + session.getHost());
				scpDir(session, source, "/usr/local/" + packageName);
				// TODO: Parametrise this!
				log.info("Executing " + packageName);
				exec(session, "/usr/local/" + packageName + '/' + packageName, true);
			} catch (JSchException | IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void debug(File source) {

	}

	public void install(File source, File systemdConf) {
		for (Session session : sessions) {
			try {
				log.info("Copying " + packageName + " to " + session.getHost());
				scpDir(session, source, "/usr/local/" + packageName);
				log.info("Setting up service " + packageName);
				scpFile(session, systemdConf, "/etc/systemd/system/");
				exec(session, "sudo systemctl enable " + packageName, true);
			} catch (JSchException | IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void uninstall() {
		for (Session session : sessions) {
			try {
				log.info("Removing service " + packageName);
				exec(session, "sudo systemctl disable " + packageName, true);
			} catch (JSchException | IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	@Override
	public void close() throws IOException {
		for (Session session : sessions) {
			log.info("Closing session to " + session.getHost());
			session.disconnect();
		}
	}

	private int exec(Session session, String command, boolean checkRc) throws JSchException, IOException {
		ChannelExec channel = (ChannelExec) session.openChannel("exec");
		channel.setPtyType(PTY_TYPE);
		channel.setEnv("LC_ALL", LOCALE);
		channel.setInputStream(null);
		channel.setCommand(command);

		InputStream in = channel.getInputStream();
		InputStream err = channel.getExtInputStream();
		StringBuilder result = new StringBuilder();
		int rc = Integer.MIN_VALUE;

		channel.connect(3000);

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

	private void scpDir(Session session, File lDir, String rDir) throws JSchException, IOException {
		exec(session, "sudo mkdir -p " + rDir, true);
		for (File lFile : lDir.listFiles()) {
			final String rFile = rDir + '/' + lFile.getName();
			if (lFile.isDirectory()) {
				scpDir(session, lFile, rFile);
			} else {
				scpFile(session, lFile, rFile);
			}
		}
	}

	private void scpFile(Session session, File lFile, String rFileName) throws JSchException, IOException {
		ChannelExec channel = (ChannelExec) session.openChannel("exec");
		channel.setPtyType(PTY_TYPE);
		channel.setEnv("LC_ALL", LOCALE);
		channel.setInputStream(null);

		// exec 'scp -t rfile' remotely
		rFileName = rFileName.replace("'", "'\"'\"'");
		channel.setCommand("sudo scp -p -t '" + rFileName + "'");

		OutputStream out = channel.getOutputStream();
		InputStream in = channel.getInputStream();

		channel.connect(3000);
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
		if (lFileName.lastIndexOf('/') > 0) {
			command += lFileName.substring(lFileName.lastIndexOf('/') + 1);
		} else {
			command += lFileName;
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

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		List<Target> targets = new ArrayList<>();
		Target target = new Target(null, "raspberrypi.local", "pi", "raspberry");
		targets.add(target);

		File stageDir = new File(args[0]);
		File svcFile = new File(args[1]);

		RiotCtlTool tool = new RiotCtlTool(svcFile.getName().replace(".service", ""), targets, log);
		tool.ensurePackages("oracle-java8-jdk wiringpi");
		tool.install(stageDir, svcFile);
		tool.close();
		log.info("done");
	}
}
