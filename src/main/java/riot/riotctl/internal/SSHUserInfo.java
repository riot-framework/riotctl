package riot.riotctl.internal;

import org.apache.commons.lang3.NotImplementedException;

import riot.riotctl.Logger;

class SSHUserInfo implements com.jcraft.jsch.UserInfo {
    private final Logger log;
    private final String secret;

    public SSHUserInfo(Logger log, String secret) {
        super();
        this.log = log;
        this.secret = secret;
    }

    @Override
    public String getPassphrase() {
        return secret;
    }

    @Override
    public String getPassword() {
        return secret;
    }

    @Override
    public boolean promptPassword(String message) {
        return true;
    }

    @Override
    public boolean promptPassphrase(String message) {
        return true;
    }

    @Override
    public boolean promptYesNo(String message) {
        log.error(message);
        throw new NotImplementedException("Not Implemented: Unable to interactively prompt.");
    }

    @Override
    public void showMessage(String message) {
        log.info(message);
    }

}
