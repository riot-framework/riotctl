package riot.riotctl.internal;

public class SystemdConfig {
	private final String packageName, user;
	private final String workDir, envDir, runDir;
	private final String startScript;

	private static final char LF = '\n';

	public SystemdConfig(String packageName, String user) {
		super();
		this.packageName = packageName;
		this.user = user;
		this.workDir = "/usr/local/" + packageName;
		this.envDir = "/etc/default/" + packageName;
		this.runDir = "/run/" + packageName;
		this.startScript = workDir + "/bin/" + packageName;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("[Unit]").append(LF);
		sb.append("Description=" + packageName).append(LF);
		sb.append("Requires=network.target").append(LF);
		sb.append(LF);
		sb.append("[Service]").append(LF);
		sb.append("Type=simple").append(LF);
		sb.append("WorkingDirectory=" + workDir).append(LF);
//		sb.append("EnvironmentFile=" + envDir).append(LF);
		sb.append("ExecStart=/bin/bash " + startScript).append(LF);
		sb.append("ExecReload=/bin/kill -HUP $MAINPID").append(LF);
		sb.append("Restart=always").append(LF);
		sb.append("RestartSec=60").append(LF);
		sb.append("SuccessExitStatus=").append(LF);
		sb.append("TimeoutStopSec=5").append(LF);
		sb.append("User=" + user).append(LF);
//		sb.append("ExecStartPre=/bin/mkdir -p " + runDir).append(LF);
//		sb.append("ExecStartPre=/bin/chown " + user + ":" + user + " " + runDir).append(LF);
//		sb.append("ExecStartPre=/bin/chmod 755 " + runDir).append(LF);
		sb.append("PermissionsStartOnly=true").append(LF);
		sb.append("LimitNOFILE=1024").append(LF);
		sb.append(LF);
		sb.append("[Install]").append(LF);
		sb.append("WantedBy=multi-user.target").append(LF);
		return sb.toString();
	}

}
