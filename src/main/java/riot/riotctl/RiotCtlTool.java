package riot.riotctl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import riot.riotctl.discovery.DiscoveryUtil;
import riot.riotctl.discovery.HostInfo;
import riot.riotctl.internal.PackageConfig;
import riot.riotctl.internal.SSHClient;
import riot.riotctl.internal.SocksProxy;
import riot.riotctl.logger.StdOutLogger;

public class RiotCtlTool {
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

	public RiotCtlTool run() {
		for (SSHClient client : clients) {
			try {
				PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername());
				client.exec("sudo systemctl restart " + packageName, true);
				client.exec("sudo journalctl -f -u " + packageName, true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool debug() {
		// TODO "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$1"

		return this;
	}

	public RiotCtlTool stop() {
		// TODO
		return this;
	}

	public RiotCtlTool install() {
		for (SSHClient client : clients) {
			try {
				client.exec("sudo systemctl enable " + packageName, true);
				client.exec("sudo systemctl start " + packageName, true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool uninstall() {
		for (SSHClient client : clients) {
			try {
				log.info("Removing service " + packageName);
				client.exec("sudo systemctl disable " + packageName, true);
				client.exec("sudo systemctl stop " + packageName, true);
				// TODO: Delete everything
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool close() throws IOException {
		for (SSHClient client : clients) {
			log.info("Closing session to " + client.getHost());
			client.close();
		}
		return this;
	}

	public RiotCtlTool ensurePackages() {
		for (SSHClient client : clients) {
			try {
				client.setProxy(SocksProxy.ensureProxy(8080, log));

				// TODO: Skip this if it has been checked before (use a file in etc):
				// String pkgChecked = client.read(pkgConf.prereqFile);
				log.info("Checking dependencies " + dependencies + " on " + client.getHost());

				final String aptOptions = "-o Acquire::http::proxy=\"http://localhost:8080\"";
				final String aptUpdateCmd = "sudo apt-get " + aptOptions + " update";
				final String aptInstallCmd = "sudo apt-get " + aptOptions + " install " + dependencies;

				// Update package list if it's over a month old:
				client.exec("find /var/cache/apt/pkgcache.bin -mtime +30 -exec " + aptUpdateCmd + " \\;", false);

				// Update the packages:
				client.exec(aptInstallCmd, true);
				client.resetProxy();
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool deploy() {
		for (Iterator<SSHClient> iterator = clients.iterator(); iterator.hasNext();) {
			SSHClient client = iterator.next();
			try {
				PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername());
				log.info("Copying " + pkgConf.packageName + " to " + client.getHost());
				client.copyDir(stageDir, pkgConf.binDir);

				log.info("Setting up service " + pkgConf.packageName);
				client.write(pkgConf.toSystemdFile(), "/etc/systemd/system/", pkgConf.packageName + ".service");
				client.exec("sudo systemctl daemon-reload", true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
				// No point in attempting to use this client, since copying failed: Close and
				// remove connection.
				try {
					client.close();
				} catch (IOException e1) {
					log.warn(e1.getMessage());
				}
				iterator.remove();
			}
		}
		return this;
	}

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		List<Target> targets = new ArrayList<>();
		Target target = new Target(null, "raspberrypi.local", "pi", "raspberry");
		targets.add(target);

		File stageDir = new File(args[0]);

		RiotCtlTool tool = new RiotCtlTool(args[1], "oracle-java8-jdk wiringpi", stageDir, targets, log);
		tool.ensurePackages().deploy().run().close();
		log.info("done");
	}
}
