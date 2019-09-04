package riot.riotctl;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import riot.riotctl.discovery.DiscoveryUtil;
import riot.riotctl.discovery.HostInfo;
import riot.riotctl.internal.PackageConfig;
import riot.riotctl.internal.SSHClient;
import riot.riotctl.internal.SocksProxy;
import riot.riotctl.logger.StdOutLogger;

public class RiotCtlTool implements Closeable {
	private final List<SSHClient> clients = new ArrayList<SSHClient>();
	private final String packageName;
	private final String dependencies;
	private final File stageDir;
	private final Logger log;

	public RiotCtlTool(String packageName, String packageDependencies, File stageDir, List<Target> targets,
			Logger log) {
		super();
		this.packageName = packageName;
		this.dependencies = packageDependencies;
		this.stageDir = stageDir;
		this.log = log;

		for (HostInfo hostinfo : DiscoveryUtil.discoverHostnames(log, targets)) {
			try {
				clients.add(new SSHClient(hostinfo, log));
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void run() {
		for (SSHClient client : clients) {
			try {
				PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername());
				ensurePackages(client, log, pkgConf, dependencies);
				deploy(client, log, pkgConf, stageDir);
				client.exec("sudo systemctl restart " + packageName, true);
				client.exec("sudo journalctl -f -u " + packageName, true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
	}

	public void debug() {

	}

	public void install() {
		for (SSHClient client : clients) {
			try {
				PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername());
				ensurePackages(client, log, pkgConf, dependencies);
				deploy(client, log, pkgConf, stageDir);
				client.exec("sudo systemctl enable " + packageName, true);
				client.exec("sudo systemctl start " + packageName, true);
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
				client.exec("sudo systemctl stop " + packageName, true);
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

	private static void ensurePackages(SSHClient client, Logger log, PackageConfig pkgConf, String packages) throws IOException {
		client.setProxy(SocksProxy.ensureProxy(8080, log));

		// TODO: Skip this if it has been checked before (use a file in etc):
		// String pkgChecked = client.read(pkgConf.prereqFile);
		log.info("Checking dependencies " + packages + " on " + client.getHost());

		final String aptOptions = "-o Acquire::http::proxy=\"http://localhost:8080\"";
		final String aptUpdateCmd = "sudo apt-get " + aptOptions + " update";
		final String aptInstallCmd = "sudo apt-get " + aptOptions + " install " + packages;

		// Update package list if it's over a month old:
		client.exec("find /var/cache/apt/pkgcache.bin -mtime +30 -exec " + aptUpdateCmd + " \\;", false);

		// Update the packages:
		client.exec(aptInstallCmd, true);
		client.resetProxy();
	}

	private static void deploy(SSHClient client, Logger log, PackageConfig pkgConf, File source) throws IOException {
		log.info("Copying " + pkgConf.packageName + " to " + client.getHost());
		client.copyDir(source, pkgConf.binDir);
		
		log.info("Setting up service " + pkgConf.packageName);
		client.write(pkgConf.toSystemdFile(), "/etc/systemd/system/", pkgConf.packageName + ".service");
		client.exec("sudo systemctl daemon-reload", true);
	}

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		List<Target> targets = new ArrayList<>();
		Target target = new Target(null, "raspberrypi.local", "pi", "raspberry");
		targets.add(target);

		File stageDir = new File(args[0]);

		RiotCtlTool tool = new RiotCtlTool(args[1], "oracle-java8-jdk wiringpi", stageDir, targets, log);
		tool.run();
		tool.close();
		log.info("done");
	}
}
