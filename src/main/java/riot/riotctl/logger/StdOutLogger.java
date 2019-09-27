package riot.riotctl.logger;

import riot.riotctl.Logger;

public final class StdOutLogger extends Logger {

    private final boolean debug;

    public StdOutLogger() {
        this.debug = true;
    }

    public StdOutLogger(boolean debug) {
        this.debug = debug;
    }

    @Override
    public void error(String s) {
        System.err.println(s);
    }

    @Override
    public void warn(String s) {
        System.err.println(s);
    }

    @Override
    public void info(String s) {
        System.out.println(s);
    }

    @Override
    public void debug(String s) {
        if (debug)
            System.out.println(s);
    }

}
