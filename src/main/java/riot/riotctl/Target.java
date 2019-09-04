package riot.riotctl;

public class Target {

	public enum DiscoveryMethod {
		HOST, MDNS, HOST_THEN_MDNS
	}

	private final DiscoveryMethod method;
	private final String devicename;
	private final String username;
	private final String password;

	public Target(final DiscoveryMethod method, final String devicename, final String username, final String password) {
		this.method = method;
		this.devicename = devicename;
		this.username = username;
		this.password = password;
	}

	public String getDevicename() {
		return devicename;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public DiscoveryMethod getDiscoveryMethod() {
		return method;
	}

}
