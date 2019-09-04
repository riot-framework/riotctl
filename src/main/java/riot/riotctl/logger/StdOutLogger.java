package riot.riotctl.logger;

import riot.riotctl.Logger;

public final class StdOutLogger extends Logger {

	@Override
	public void error(String s) {
		System.err.println(s);
	}

	@Override
	public void info(String s) {
		System.out.println(s);
	}

	@Override
	public void debug(String s) {
		System.out.println(s);
	}

}
