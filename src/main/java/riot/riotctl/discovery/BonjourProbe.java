package riot.riotctl.discovery;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;
import javax.jmdns.ServiceTypeListener;

import riot.riotctl.Logger;
import riot.riotctl.StdOutLogger;
import riot.riotctl.Target;

public class BonjourProbe implements ServiceListener, ServiceTypeListener, Closeable {

	private static final String SERVICE = "_sftp-ssh._tcp.local.";
	private final Logger log;
	private final Target target;
	private JmDNS jmdns;

	public BonjourProbe(Logger log, Target target) {
		this(log, target, guessNetworkAdapter(log, target));
	}

	public BonjourProbe(Logger log, Target target, InetAddress networkAdapter) {
		super();
		this.log = log;
		this.target = target;
		try {
			log.info("Probing interface " + networkAdapter + " for service " + SERVICE);
			this.jmdns = JmDNS.create(networkAdapter);
			jmdns.addServiceListener(SERVICE, this);
			jmdns.addServiceTypeListener(this);
		} catch (IOException e) {
			log.error("Unable to find adapter " + networkAdapter);
		}
	}

	private static InetAddress guessNetworkAdapter(Logger log, Target target) {
		InetAddress candidate = null;

		// Bonjour range (IP4)
		candidate = findNetworkAdapter(log, (byte) 169, (byte) 254);
		if (candidate != null)
			return candidate;

		// Bonjour range (IP6)
		candidate = findNetworkAdapter(log, (byte) 0xfe, (byte) 0x80);
		if (candidate != null)
			return candidate;

		// Class C private network
		candidate = findNetworkAdapter(log, (byte) 192, (byte) 168);
		if (candidate != null)
			return candidate;

		// Class A private network
		candidate = findNetworkAdapter(log, (byte) 10);
		if (candidate != null)
			return candidate;

		try {
			log.info("Probing using default address.");
			return InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			log.error(e.getMessage());
			return InetAddress.getLoopbackAddress();
		}
	}

	private static InetAddress findNetworkAdapter(Logger log, byte... ipStartsWith) {
		try {
			Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
			nicLoop: for (NetworkInterface nic : Collections.list(nics)) {
				if (!nic.isUp())
					continue;
				ipLoop: for (InterfaceAddress ifAddress : nic.getInterfaceAddresses()) {
					byte[] ifBytes = ifAddress.getAddress().getAddress();
					for (int i = 0; i < ipStartsWith.length; i++) {
						if (ifBytes[i] != ipStartsWith[i])
							continue ipLoop;
					}
					log.info("Choosing interface " + nic.getDisplayName() + " for probing.");
					return ifAddress.getAddress();
				}
			}
			return null;
		} catch (SocketException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public void discover(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			log.error("Interrupted while discovering devices");
		}
	}

	@Override
	public void close() throws IOException {
		jmdns.close();
	}

	@Override
	public void serviceAdded(ServiceEvent evt) {
		log.debug("Service " + evt.getType() + " on " + evt.getName() + " found.");
	}

	@Override
	public void serviceRemoved(ServiceEvent evt) {
		log.debug("Service " + evt.getType() + " on " + evt.getName() + " has stopped.");
	}

	@Override
	public void serviceResolved(ServiceEvent evt) {
		log.info("Resolved: " + evt);
	}

	@Override
	public void serviceTypeAdded(ServiceEvent evt) {
		log.debug("Discovered service type " + evt.getType());
	}

	@Override
	public void subTypeForServiceTypeAdded(ServiceEvent evt) {

	}

	public static void main(String[] args) throws IOException {
		StdOutLogger log = new StdOutLogger();
		Target target = new Target(null, "raspberrypi", "raspberry", "pi");
		BonjourProbe probe = new BonjourProbe(log, target);
		probe.discover(5000);
		probe.close();
		log.info("done");
	}

}
