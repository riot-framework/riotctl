package riot.riotctl;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
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

			// new BonjourProbe(log, target).discover(5000);
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
		for (Session session : sessions) {
			try {
				// ProxyServer proxy = new ProxyServer(new ServerAuthenticatorNone());
				// proxy.start(8080, 5, InetAddress.getLoopbackAddress());

				SocksProxy proxy = new SocksProxy(8080, log);
				new Thread(proxy).start();
				session.setPortForwardingR(8080, "localhost", proxy.getPort());

				log.info("Checking dependencies " + packages + " on " + session.getHost());

				final String aptOptions = "-o Acquire::http::proxy=\"http://localhost:8080\"";
				final String aptUpdateCmd = "sudo apt-get " + aptOptions + " update";
				final String aptInstallCmd = "sudo apt-get " + aptOptions + " install " + packages;

				// Update package list, but not more often than once a day:
				exec(session, "find /var/cache/apt/pkgcache.bin -mmin -1440 -exec " + aptUpdateCmd + " \\;");

				// Update the packages
				exec(session, aptInstallCmd);

				session.delPortForwardingR(8080);
				proxy.close();

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
		channel.setPtyType("vanilla");
		channel.setEnv("LC_ALL", "en_US.UTF-8");
		channel.setInputStream(null);
		channel.setCommand(command);

		channel.connect(3000);
		logOutput(channel);

		channel.disconnect();
		return channel.getExitStatus();
	}

	private void logOutput(ChannelExec channel) throws IOException {
		InputStream in = channel.getInputStream();
		byte[] tmp = new byte[1024];
		while (true) {
			while (in.available() > 0) {
				int i = in.read(tmp, 0, 1024);
				if (i < 0)
					break;
				log.debug(new String(tmp, 0, i));
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
	}

	private PrintWriter getShell(Session session) throws JSchException, IOException {
		ChannelShell channel = (ChannelShell) session.openChannel("shell");
		channel.setPtyType("vanilla");
		channel.setEnv("LC_ALL", "en_US.UTF-8");

		PipedOutputStream out = new PipedOutputStream();
		channel.setInputStream(new PipedInputStream(out));
		channel.setOutputStream(log);
		channel.connect(3000);

		return new PrintWriter(out);
	}

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		List<Target> targets = new ArrayList<>();
		Target target = new Target(null, "raspberrypi.local", "pi", "raspberry");
		targets.add(target);
		RiotCtlTool tool = new RiotCtlTool("test", targets, log);
		tool.ensurePackages("oracle-java8-jdk wiringpi");
		tool.close();
		log.info("done");
	}
}
