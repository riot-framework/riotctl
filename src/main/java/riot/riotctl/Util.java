package riot.riotctl;

import java.util.List;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class Util {

	public Util(List<Target> targets) {
		System.err.println(ToStringBuilder.reflectionToString(targets, ToStringStyle.MULTI_LINE_STYLE));
	}

}
