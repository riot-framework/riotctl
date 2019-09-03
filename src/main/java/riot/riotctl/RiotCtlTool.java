package riot.riotctl;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import riot.riotctl.internal.SSHClient;
import riot.riotctl.internal.SocksProxy;
import riot.riotctl.internal.SystemdConfig;

public class RiotCtlTool implements Closeable {
	private final List<SSHClient> clients = new ArrayList<SSHClient>();
	private final String packageName;
	private final Logger log;

	public RiotCtlTool(String packageName, List<Target> targets, Logger log) {
		super();
		this.packageName = packageName;
		this.log = log;

		for (Target target : targets) {
			try {
				// new BonjourProbe(log, target).discover(5000);
				clients.add(new SSHClient(target, log));
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void ensurePackages(String packages) {
		for (SSHClient client : clients) {
			try {
				client.setProxy(SocksProxy.ensureProxy(8080, log));

				// TODO: Skip this if it has been checked before (use a file in etc)
				log.info("Checking dependencies " + packages + " on " + client.getHost());

				final String aptOptions = "-o Acquire::http::proxy=\"http://localhost:8080\"";
				final String aptUpdateCmd = "sudo apt-get " + aptOptions + " update";
				final String aptInstallCmd = "sudo apt-get " + aptOptions + " install " + packages;

				// Update package list if it's over a month old:
				client.exec("find /var/cache/apt/pkgcache.bin -mtime +30 -exec " + aptUpdateCmd + " \\;", false);

				// Update the packages:
				client.exec(aptInstallCmd, true);

				client.resetProxy();

			} catch (Exception e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void run(File source) {
		for (SSHClient client : clients) {
			try {
				log.info("Copying " + packageName + " to " + client.getHost());
				client.copyDir(source, "/usr/local/" + packageName);
				log.info("Running service " + packageName);
				String payload = new SystemdConfig(packageName, client.getUsername()).toString();
				client.write(payload, "/etc/systemd/system/", packageName + ".service");
				client.exec("sudo systemctl daemon-reload", true);
				client.exec("sudo systemctl start " + packageName, true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void debug(File source) {

	}

	public void install(File source) {
		for (SSHClient client : clients) {
			try {
				log.info("Copying " + packageName + " to " + client.getHost());
				client.copyDir(source, "/usr/local/" + packageName);
				log.info("Setting up service " + packageName);
				String payload = new SystemdConfig(packageName, client.getUsername()).toString();
				client.write(payload, "/etc/systemd/system/", packageName + ".service");
				client.exec("sudo systemctl daemon-reload", true);
				client.exec("sudo systemctl enable " + packageName, true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void uninstall() {
		for (SSHClient client : clients) {
			try {
				log.info("Removing service " + packageName);
				client.exec("sudo systemctl disable " + packageName, true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	@Override
	public void close() throws IOException {
		for (SSHClient client : clients) {
			log.info("Closing session to " + client.getHost());
			client.close();
		}
	}

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		List<Target> targets = new ArrayList<>();
		Target target = new Target(null, "raspberrypi.local", "pi", "raspberry");
		targets.add(target);

		File stageDir = new File(args[0]);

		RiotCtlTool tool = new RiotCtlTool(args[1], targets, log);
		//tool.ensurePackages("oracle-java8-jdk wiringpi");
		tool.run(stageDir);
		tool.close();
		log.info("done");
	}
}
