package riot.riotctl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import riot.riotctl.Target.DiscoveryMethod;
import riot.riotctl.discovery.DiscoveryUtil;
import riot.riotctl.discovery.HostInfo;
import riot.riotctl.internal.PackageConfig;
import riot.riotctl.internal.SSHClient;
import riot.riotctl.internal.SocksProxy;
import riot.riotctl.logger.StdOutLogger;

public class RiotCtlTool {
	private final List<SSHClient> clients = new ArrayList<SSHClient>();
	private final String packageName;
	private final File stageDir;
	private final Logger log;

	public RiotCtlTool(String packageName, File stageDir, List<Target> targets, Logger log) {
		super();
		this.packageName = packageName;
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

	public RiotCtlTool ensurePackages(String dependencies) {
		for (SSHClient client : clients) {
			try {
				PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername());
				String[] lastCheckedPkg = client.read(pkgConf.runDir + "/dependencies.lst", true).split("\\s+");
				if (hasSamePackages(lastCheckedPkg, dependencies.split("\\s+"))) {
					log.info("Dependencies unchanged since last install, skipping check on " + client.getHost());
					continue;
				}

				log.info("Checking dependencies " + dependencies + " on " + client.getHost());
				final SocksProxy proxy = SocksProxy.ensureProxy(8080, log);
				client.setProxy(proxy);

				final String aptOptions = "-y -o Acquire::http::proxy=\"socks5h://localhost:" + proxy.getPort() + "\"";
				final String aptUpdateCmd = "sudo apt-get " + aptOptions + " update";
				final String aptInstallCmd = "sudo apt-get " + aptOptions + " install " + dependencies;

				// Update package list if it's over a month old:
				client.exec("find /var/cache/apt/pkgcache.bin -mtime +30 -exec " + aptUpdateCmd + " \\;", false);

				// Update the packages:
				client.exec(aptInstallCmd, true);

				client.mkDir(pkgConf.runDir);
				client.write(dependencies, pkgConf.runDir + "/dependencies.lst");

				client.resetProxy();
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool ensureEnabled(boolean i2c, boolean spi, boolean serial, boolean onewire) {
		List<String> features = new ArrayList<String>(8);
		List<String> getCmds = new ArrayList<String>(8);
		List<String> setCmds = new ArrayList<String>(8);

		if (i2c) {
			features.add("I2C");
			getCmds.add("sudo raspi-config nonint get_i2c | grep -q 0");
			setCmds.add("sudo raspi-config nonint do_i2c 0");
		}
		if (spi) {
			features.add("SPI");
			getCmds.add("sudo raspi-config nonint get_spi | grep -q 0");
			setCmds.add("sudo raspi-config nonint do_spi 0");
		}
		if (serial) {
			features.add("Serial");
			getCmds.add("sudo raspi-config nonint get_serial_hw | grep -q 0");
			setCmds.add("sudo raspi-config nonint do_serial 0");
		}
		if (onewire) {
			features.add("1Wire");
			getCmds.add("sudo raspi-config nonint get_onewire | grep -q 0");
			setCmds.add("sudo raspi-config nonint do_onewire 0");
		}

		for (SSHClient client : clients) {
			if (features.size() > 0) {
				log.info("Ensuring features are enabled on " + client.getHost() + ": " + String.join(", ", features));
				for (int i = 0; i < features.size(); i++) {
					try {
						int rc = client.exec(getCmds.get(i), false);
						if (rc != 0) {
							rc = client.exec(setCmds.get(i), false);
							if (rc != 0) {
								log.error("Unable to enable " + features.get(i) + " on " + client.getHost());
							} else {
								log.warn("Enabled " + features.get(i) + ", " + client.getHost()
										+ " may need to be rebooted!");
							}
						}
					} catch (IOException e) {
						e.printStackTrace();
						log.error(e.getMessage());
					}
				}

			}

		}
		return this;
	}

	private boolean hasSamePackages(String[] pkg1, String[] pkg2) {
		Set<String> set1 = new HashSet<>(Arrays.asList(pkg1));
		Set<String> set2 = new HashSet<>(Arrays.asList(pkg2));
		return set1.size() == set2.size() && set1.containsAll(set2);
	}

	public RiotCtlTool deployDbg(int debugPort) {
		return deploy("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + debugPort);
	}

	public RiotCtlTool deploy(String... vmparams) {
		for (Iterator<SSHClient> iterator = clients.iterator(); iterator.hasNext();) {
			SSHClient client = iterator.next();
			try {
				PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername(), vmparams);
				log.info("Deploying " + pkgConf.packageName + " to " + client.getHost());
				client.copyDir(stageDir, pkgConf.binDir);
				client.write(pkgConf.toSystemdFile(), "/etc/systemd/system/" + pkgConf.packageName + ".service");
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

	public RiotCtlTool run() {
		for (SSHClient client : clients) {
			try {
				client.exec("sudo systemctl restart " + packageName, true);
				client.run("sudo journalctl -f -u " + packageName, System.in);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool start() {
		for (SSHClient client : clients) {
			try {
				client.exec("sudo systemctl restart " + packageName, true);
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool stop() {
		for (SSHClient client : clients) {
			try {
				client.exec("sudo systemctl stop " + packageName, true);
				log.info("Stopping service " + packageName + ".");
			} catch (IOException e) {
				e.printStackTrace();
				log.error(e.getMessage());
			}
		}
		return this;
	}

	public RiotCtlTool install() {
		for (SSHClient client : clients) {
			try {
				client.exec("sudo systemctl enable " + packageName, true);
				client.exec("sudo systemctl restart " + packageName, true);
				log.info("Enabled service " + packageName + ", service will now start automatically.");
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
				client.exec("sudo systemctl disable " + packageName, true);
				client.exec("sudo systemctl stop " + packageName, true);
				log.info("Disabled service " + packageName);
				// TODO: Delete everything?
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

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		List<Target> targets = new ArrayList<>();
		Target target = new Target(DiscoveryMethod.HOST_THEN_MDNS, "raspberrypi", "pi", "raspberry");
		targets.add(target);

		File stageDir = new File(args[0]);

		RiotCtlTool tool = new RiotCtlTool(args[1], stageDir, targets, log);
		tool.ensurePackages("oracle-java8-jdk wiringpi i2c-tools").ensureEnabled(true, true, false, false)
				.deployDbg(7896).run().close();
		log.info("done");
	}
}
