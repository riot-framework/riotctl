package riot.riotctl.discovery;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

import riot.riotctl.Logger;
import riot.riotctl.Target;
import riot.riotctl.logger.StdOutLogger;

public class BonjourProbe implements ServiceListener, Closeable {

    private static final String SERVICE = "_sftp-ssh._tcp.local.";
    private final Logger log;
    private final Target target;
    private final List<JmDNS> mdnsInstances = Collections.synchronizedList(new ArrayList<JmDNS>());
    private final List<HostInfo> results = new ArrayList<HostInfo>();

    public BonjourProbe(Logger log, Target target) {
        this(log, target, findAdapters(log));
    }

    /**
     * Set up an mDNS probe that will only log available devices
     *
     * @param log         the RIoT logger object
     * @param allAdapters whether to query all network adapters, or only those with IP beggining in 168.254.*,
     *                    192.168.*, or 10.*
     */
    public BonjourProbe(Logger log, boolean allAdapters) {
        this(log, null, allAdapters ? findAdapters(log) : findMostLikelyAdapters(log));
    }

    public BonjourProbe(Logger log, Target target, boolean allAdapters) {
        this(log, target, allAdapters ? findAdapters(log) : findMostLikelyAdapters(log));
    }

    public BonjourProbe(Logger log, Target target, Set<InetAddress> networkAdapters) {
        super();
        this.log = log;
        this.target = target;
        log.info("Probing " + networkAdapters.size() + " interfaces for service " + SERVICE);

        Collections.unmodifiableSet(networkAdapters).stream().parallel().forEach(networkAdapter -> {
            try {
                JmDNS instance = JmDNS.create(networkAdapter);
                mdnsInstances.add(instance);
                instance.addServiceListener(SERVICE, this);
            } catch (IOException e) {
                e.printStackTrace();
                log.error(e.getMessage());
            }
        });
    }

    private static Set<InetAddress> findMostLikelyAdapters(Logger log) {
        Set<InetAddress> results = new HashSet<InetAddress>();

        // Bonjour range (IP4)
        results.addAll(findAdapters(log, (byte) 169, (byte) 254));

        // Bonjour range (IP6)
        results.addAll(findAdapters(log, (byte) 0xfe, (byte) 0x80));

        // Class C private network
        results.addAll(findAdapters(log, (byte) 192, (byte) 168));

        // Class A private network
        results.addAll(findAdapters(log, (byte) 10));

        try {
            log.info("Probing using default address.");
            results.add(InetAddress.getLocalHost());
        } catch (UnknownHostException e) {
            log.error(e.getMessage());
        }

        return results;
    }

    private static Set<InetAddress> findAdapters(Logger log, byte... ipStartsWith) {
        Set<InetAddress> results = new HashSet<InetAddress>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            nicLoop:
            for (NetworkInterface nic : Collections.list(nics)) {
                if (!nic.isUp())
                    continue;
                ipLoop:
                for (InterfaceAddress ifAddress : nic.getInterfaceAddresses()) {
                    byte[] ifBytes = ifAddress.getAddress().getAddress();
                    for (int i = 0; i < ipStartsWith.length; i++) {
                        if (ifBytes[i] != ipStartsWith[i])
                            continue ipLoop;
                    }
                    log.debug("Selecting interface " + nic.getDisplayName() + " (" + ifAddress.getAddress()
                            + ") for probing.");
                    results.add(ifAddress.getAddress());
                }
            }
            return results;
        } catch (SocketException e) {
            log.error(e.getMessage());
            return results;
        }
    }

    public List<HostInfo> getResults() {
        if (results.size() == 0) {
            log.warn("No matching hosts found via mDNS.");
        }
        return results;
    }

    @Override
    public void close() throws IOException {
        Collections.unmodifiableList(mdnsInstances).stream().parallel().forEach(jmDNS -> {
            try {
                jmDNS.close();
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        });

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
        log.debug("Service Resolved: " + evt);
        if (target == null) {
            for (InetAddress addr : evt.getInfo().getInetAddresses()) {
                StringBuilder sb = new StringBuilder("Device '").append(evt.getName());
                final String hostname = addr.getHostName();
                sb.append("' is at ").append(hostname);
                final String hostaddr = addr.getHostAddress();
                if (!hostaddr.equals(hostname)) {
                    sb.append(" (").append(hostaddr).append(")");
                }
                log.info(sb.toString());
            }
        }
        if (evt.getName().equals(target.getDevicename())) {
            for (InetAddress addr : evt.getInfo().getInetAddresses()) {
                log.info("Found device through mDNS: " + addr);
                results.add(new HostInfo(addr, target.getUsername(), target.getPassword()));
            }
            this.notifyAll();
        }
    }

    public static void main(String[] args) throws IOException {
        StdOutLogger log = new StdOutLogger();
        Target target = new Target(null, args[0], "raspberry", "pi");
        BonjourProbe probe = new BonjourProbe(log, target, true);

        log.info("Found: ");
        for (HostInfo addr : probe.getResults()) {
            log.info(" - " + addr.getHost());
        }

        probe.close();
        log.info("done");
    }

}
