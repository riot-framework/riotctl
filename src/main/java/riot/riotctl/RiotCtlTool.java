package riot.riotctl;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

public class RiotCtlTool implements Closeable {

	private JSch jsch = new JSch();

	private final String packageName;
	private final List<Session> sessions = new ArrayList<Session>();
	private final Logger log;

	public RiotCtlTool(String packageName, List<Target> targets, Logger log) {
		super();
		this.packageName = packageName;
		this.log = log;

		for (Target target : targets) {
			try {
				log.info("Opening session to " + target.hostname);
				Session session = jsch.getSession(target.username, target.hostname);
				session.setConfig("StrictHostKeyChecking", "no");
				session.setPassword(target.password);
				session.setUserInfo(new UserInfo(log));
				session.connect(5000);
				sessions.add(session);
			} catch (JSchException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void ensurePackages(String packages) {
		log.info("Checking dependencies " + packages);
		for (Session session : sessions) {
			try {
				log.info("Exit Code: " + exec(session, "pwd"));
			} catch (Exception e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void run(File source) {

	}

	public void debug(File source) {

	}

	public void install(File source) {

	}

	public void uninstall() {

	}

	@Override
	public void close() throws IOException {
		for (Session session : sessions) {
			log.info("Closing session to " + session.getHost());
			session.disconnect();
		}
	}

	private int exec(Session session, String command) throws JSchException, IOException {
		ChannelExec channel = (ChannelExec) session.openChannel("exec");
		channel.setPtyType("vt102");
		channel.setEnv("LC_ALL", "en_US.UTF-8");
		channel.setEnv("LANG", "en_US.UTF-8");
		channel.setEnv("LANGUAGE", "en_US.UTF-8");
		channel.setInputStream(null);
		channel.setCommand(command);
		InputStream in = channel.getInputStream();

		channel.connect(3000);

		byte[] tmp = new byte[1024];
		while (true) {
			while (in.available() > 0) {
				int i = in.read(tmp, 0, 1024);
				if (i < 0)
					break;
				log.info(new String(tmp, 0, i));
			}
			if (channel.isClosed()) {
				if (in.available() > 0)
					continue;
				break;
			}
			try {
				Thread.sleep(150);
			} catch (InterruptedException e) {
				log.error(e.getMessage());
			}
		}
		channel.disconnect();
		session.disconnect();

		return channel.getExitStatus();
	}

	private PrintWriter getShell(Session session, String command) throws JSchException, IOException {
		ChannelShell channel = (ChannelShell) session.openChannel("shell");
		channel.setPtyType("vt102");
		channel.setEnv("LC_ALL", "en_US.UTF-8");
		channel.setEnv("LANG", "en_US.UTF-8");
		channel.setEnv("LANGUAGE", "en_US.UTF-8");

		channel.connect(3000);
		return new PrintWriter(channel.getOutputStream());
	}
}
