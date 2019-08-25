package riot.riotctl;

public class Target {

	public Target(final String hostname, final String username, final String password) {
		this.hostname = hostname;
		this.username = username;
		this.password = password;
	}

	public String hostname;
	public String username;
	public String password;
}
