package riot.riotctl.discovery;

import java.net.InetAddress;

public class HostInfo {
	private final InetAddress host;
	private final String username;
	private final String password;

	public HostInfo(final InetAddress host, final String username, final String password) {
		this.host = host;
		this.username = username;
		this.password = password;
	}

	public InetAddress getHost() {
		return host;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

}
