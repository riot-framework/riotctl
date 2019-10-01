package riot.riotctl;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import riot.riotctl.Target.DiscoveryMethod;
import riot.riotctl.discovery.BonjourProbe;
import riot.riotctl.discovery.DiscoveryUtil;
import riot.riotctl.discovery.HostInfo;
import riot.riotctl.internal.PackageConfig;
import riot.riotctl.internal.ProxyServer;
import riot.riotctl.internal.SSHClient;
import riot.riotctl.logger.StdOutLogger;

public class RiotCtlTool {
    private static final SimpleDateFormat TIMEDATECTL_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static final String PARAM_VERBOSE = "-v";
    public static final String PARAM_ADD_UNSUPPORTED_MODULES = "-J--add-modules=jdk.unsupported";

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
                log.error(hostinfo.getHost().getHostName() + " - " + e.getMessage());
            }
        }
    }

    public RiotCtlTool ensurePackages(String dependencies) {
        if (dependencies == null || dependencies.trim().length() < 1)
            return this;

        for (SSHClient client : clients) {
            try {
                PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername());
                String[] lastCheckedPkg = client.read(pkgConf.getDeplistFileName(), true).split("\\s+");

                if (hasSamePackages(lastCheckedPkg, dependencies.split("\\s+"))) {
                    log.info("Dependencies unchanged since last install, skipping check on " + client.getHost());
                    continue;
                }

                log.info("Checking dependencies " + dependencies + " on " + client.getHost());
                final ProxyServer proxy = ProxyServer.ensureProxy(8080, log);
                client.setProxy(proxy);

                String aptOptions = "-y";
                aptOptions += " -o Acquire::http::proxy=\"socks5h://localhost:" + proxy.getPort() + "\"";
                aptOptions += " -o Acquire::http::No-Cache=true";
                aptOptions += " -o Acquire::http::Pipeline-Depth=0";
                aptOptions += " -o Acquire::Queue-Mode=access";
                aptOptions += " -o Acquire::Retries=10";
                // aptOptions += " -o Acquire::BrokenProxy=true";

                final String aptUpdateCmd = "sudo DEBIAN_FRONTEND=noninteractive apt-get " + aptOptions + " update";
                final String aptInstallCmd = "sudo DEBIAN_FRONTEND=noninteractive apt-get " + aptOptions
                        + " install -m " + dependencies;

                // Update package list if it's over a month old:
                int updRc = client.exec("find /var/cache/apt/pkgcache.bin -mtime +30 | egrep '.*'", false);
                if (updRc != 0) {
                    // File doesn't exist, or is more than 30 days old.
                    log.info("Updating package list");
                    client.exec(aptUpdateCmd, true);
                }

                // Update the packages:
                client.exec(aptInstallCmd, true, true);
                client.mkDir(pkgConf.runDir);
                client.write(dependencies, pkgConf.getDeplistFileName());

                client.resetProxy();
            } catch (IOException e) {
                e.printStackTrace();
                log.error(e.getMessage());
            }
        }
        return this;
    }

    public RiotCtlTool ensureEnabled(boolean i2c, boolean spi, boolean serial, boolean onewire, boolean time) {
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
            setCmds.add("sudo raspi-config nonint do_serial 0"
                    + "&& sudo sed -i /boot/cmdline.txt -e \"s/console=ttyAMA0,[0-9]\\+ //\""
                    + "&& sudo sed -i /boot/cmdline.txt -e \"s/console=serial0,[0-9]\\+ //\"");
        }
        if (onewire) {
            features.add("1Wire");
            getCmds.add("sudo raspi-config nonint get_onewire | grep -q 0");
            setCmds.add("sudo raspi-config nonint do_onewire 0");
        }

        for (SSHClient client : clients) {
            // Set internal clock
            if (time) {
                ensureCurrentTime(client);
            }
            // Enable features
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

    private void ensureCurrentTime(SSHClient client) {
        try {
            int timeRc = client.exec("timedatectl | grep -q 'synchronized: no'", false);
            if (timeRc == 0) {
                int attempts = 5;
                timeRc = client.exec("sudo timedatectl set-ntp 0 ", true);
                timeRc = client.exec("sudo timedatectl set-time '" + TIMEDATECTL_FMT.format(new Date()) + "'", false);
                while (timeRc != 0 && --attempts > 0) {
                    log.info("Attempting again in 10 seconds...");
                    Thread.sleep(10000);
                    timeRc = client.exec("sudo timedatectl set-time '" + TIMEDATECTL_FMT.format(new Date()) + "'",
                            false);
                }
                log.info("Updated system clock");
            } else {
                log.debug("System clock is already synchronized");
            }

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            log.error(e.getMessage());
        }
    }

    private boolean hasSamePackages(String[] pkg1, String[] pkg2) {
        Set<String> set1 = new HashSet<>(Arrays.asList(pkg1));
        Set<String> set2 = new HashSet<>(Arrays.asList(pkg2));
        return set1.size() == set2.size() && set1.containsAll(set2);
    }

    public RiotCtlTool deployDbg(int debugPort) {
        return deployWithParameters("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + debugPort);
    }

    public RiotCtlTool deploy(String... vmparams) {
        for (Iterator<SSHClient> iterator = clients.iterator(); iterator.hasNext();) {
            SSHClient client = iterator.next();
            try {
                PackageConfig pkgConf = new PackageConfig(packageName, client.getUsername(), vmparams);
                log.info("Deploying " + pkgConf.packageName + " to " + client.getHost());
                client.copyDir(stageDir, pkgConf.binDir);
                client.write(pkgConf.toSystemdFile(), pkgConf.getSystemdFileName());
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

    public static void discover(Logger log) {
        try {
            final BonjourProbe probe = new BonjourProbe(log, true);
            Thread.sleep(3000);
            probe.close();
        } catch (InterruptedException e) {
            log.error(e.getMessage());
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    public RiotCtlTool run() {
        for (SSHClient client : clients) {
            try {
                client.exec("sudo systemctl restart " + packageName, true);
                client.run("sudo journalctl -n 1 -f -u " + packageName, System.in);
            } catch (IOException e) {
                e.printStackTrace();
                log.error(e.getMessage());
            }

            try {
                // May fail if the session times out.
                client.exec("sudo systemctl stop " + packageName, true);
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        }
        return this;
    }

    public RiotCtlTool start() {
        for (SSHClient client : clients) {
            try {
                log.info("Starting " + packageName + " on " + client.getHost());
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
                log.info("Stopped " + packageName + " on " + client.getHost());
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

        // RiotCtlTool.discover(new StdOutLogger(false));

        StdOutLogger log = new StdOutLogger();
        List<Target> targets = new ArrayList<>();
        Target target = new Target(DiscoveryMethod.HOST_THEN_MDNS, "raspberrypi", "pi", "raspberry");
        targets.add(target);

        File stageDir = new File(args[0]);

        RiotCtlTool tool = new RiotCtlTool(args[1], stageDir, targets, log);
        tool.ensureEnabled(true, true, false, false, true).ensurePackages("openjdk-8-jdk-headless wiringpi i2c-tools")
                .deploy().run().close();
        log.info("done");
    }
}
