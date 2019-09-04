package riot.riotctl.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import riot.riotctl.Logger;
import riot.riotctl.Target;
import riot.riotctl.Target.DiscoveryMethod;
import riot.riotctl.internal.StdOutLogger;

public class DiscoveryUtil {

	private DiscoveryUtil() {
		// Not instantiable
	}

	public static List<HostInfo> discoverHostnames(Logger log, List<Target> targets) {
		LinkedList<HostInfo> results = new LinkedList<HostInfo>();
		HostInfo hostinfo;
		for (Target target : targets) {
			switch (target.getDiscoveryMethod()) {
			case HOST:
				hostinfo = lookup(log, target);
				if (hostinfo != null) {
					results.add(hostinfo);
				} else {
					log.error("Host not found: " + target.getDevicename());
				}
				break;
			case MDNS:
				results.addAll(new BonjourProbe(log, target, true).getResults());
				break;
			case HOST_THEN_MDNS:
				hostinfo = lookup(log, target);
				if (hostinfo != null) {
					results.add(hostinfo);
				} else {
					results.addAll(new BonjourProbe(log, target, true).getResults());
				}
			default:
				break;
			}
		}
		return results;
	}

	private static final HostInfo lookup(Logger log, Target target) {
		return lookup(log, target.getDevicename(), target.getUsername(), target.getPassword());
	}

	private static final HostInfo lookup(Logger log, String devicename, String username, String password) {
		InetAddress addr;
		try {
			addr = InetAddress.getByName(devicename);
			return new HostInfo(addr, username, password);
		} catch (UnknownHostException e) {
			if (!devicename.endsWith(".local")) {
				return lookup(log, devicename + ".local", username, password);
			}
			return null;
		}
	}

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		List<Target> targets = new ArrayList<>();

		for (String name : args) {
			targets.add(new Target(DiscoveryMethod.HOST_THEN_MDNS, name, "", ""));
		}

		log.info("Searching for hosts...");
		List<HostInfo> results = discoverHostnames(log, targets);
		for (HostInfo hostInfo : results) {
			log.info("Found host " + hostInfo.getHost());
		}

		log.info("Done");
	}
}
