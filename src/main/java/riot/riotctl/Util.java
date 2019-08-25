package riot.riotctl;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

public class Util {

	public Util(Object... conf) {
		for (Object o : conf) {
			System.err.println(ToStringBuilder.reflectionToString(o, ToStringStyle.MULTI_LINE_STYLE));
		}
	}

}
