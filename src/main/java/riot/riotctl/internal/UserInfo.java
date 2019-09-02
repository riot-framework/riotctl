package riot.riotctl.internal;

import org.apache.commons.lang3.NotImplementedException;

import riot.riotctl.Logger;

class UserInfo implements com.jcraft.jsch.UserInfo {
	private final Logger log;

	public UserInfo(Logger log) {
		super();
		this.log = log;
	}

	@Override
	public String getPassphrase() {
		return null;
	}

	@Override
	public String getPassword() {
		return null;
	}

	@Override
	public boolean promptPassword(String message) {
		log.error(message);
		throw new NotImplementedException("Not Implemented: Unable to interactively prompt password.");
	}

	@Override
	public boolean promptPassphrase(String message) {
		log.error(message);
		throw new NotImplementedException("Not Implemented: Unable to interactively prompt passphrase.");
	}

	@Override
	public boolean promptYesNo(String message) {
		log.error(message);
		throw new NotImplementedException("Not Implemented: Unable to interactively prompt passphrase.");
	}

	@Override
	public void showMessage(String message) {
		log.info(message);
	}

}
