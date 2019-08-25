package riot.riotctl;

import java.io.File;
import java.util.List;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class Util {

	public Util(File source, List<Target> targets) {
		System.err.println(ToStringBuilder.reflectionToString(source, ToStringStyle.MULTI_LINE_STYLE));
		System.err.println(ToStringBuilder.reflectionToString(targets, ToStringStyle.MULTI_LINE_STYLE));
	}

}
