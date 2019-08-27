package riot.riotctl;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.lang3.NotImplementedException;

public abstract class Logger extends OutputStream {

	public abstract void error(String s);

	public abstract void info(String s);

	public abstract void debug(String s);

	@Override
	public void write(int b) throws IOException {
		throw new NotImplementedException("Unable to output individual chars");
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		info(new String(b, off, len));
	}

}
