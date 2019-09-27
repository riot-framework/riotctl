package riot.riotctl.internal;

public class PackageConfig {
    public final String packageName, user;
    public final String binDir, envDir, runDir;
    public final String startScript;

    public final String[] startParams;

    private static final char LF = '\n';

    public PackageConfig(String packageName, String user) {
        this(packageName, user, "");
    }

    public PackageConfig(String packageName, String user, String... vmparams) {
        super();
        this.packageName = packageName;
        this.user = user;
        this.binDir = "/usr/local/" + packageName;
        this.envDir = "/etc/default/" + packageName;
        this.runDir = "/run/" + packageName;
        this.startScript = binDir + "/bin/" + packageName;
        if (vmparams == null) {
            this.startParams = new String[] {};
        } else {
            this.startParams = vmparams;
        }
    }

    public String toSystemdFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]").append(LF);
        sb.append("Description=" + packageName).append(LF);
        sb.append("Requires=network.target").append(LF);
        sb.append(LF);
        sb.append("[Service]").append(LF);
        sb.append("Type=simple").append(LF);
        sb.append("WorkingDirectory=" + binDir).append(LF);
        for (int i = 0; i < startParams.length; i++) {
            sb.append("Environment=START_PARAMS=" + (i > 0 ? "${START_PARAMS} " : "") + startParams[i]).append(LF);
        }
        // sb.append("EnvironmentFile=" + envDir).append(LF);
        sb.append("ExecStart=/bin/bash " + startScript + " '${START_PARAMS}'").append(LF);
        sb.append("ExecReload=/bin/kill -HUP $MAINPID").append(LF);
        sb.append("Restart=always").append(LF);
        sb.append("RestartSec=60").append(LF);
        sb.append("SuccessExitStatus=143").append(LF); // VM responds to SIGTERM with RC 143
        sb.append("TimeoutStopSec=5").append(LF);
        sb.append("User=" + user).append(LF);
        // sb.append("ExecStartPre=/bin/mkdir -p " + runDir).append(LF);
        // sb.append("ExecStartPre=/bin/chown " + user + ":" + user + " " +
        // runDir).append(LF);
        // sb.append("ExecStartPre=/bin/chmod 755 " + runDir).append(LF);
        sb.append("PermissionsStartOnly=true").append(LF);
        sb.append("LimitNOFILE=1024").append(LF);
        sb.append(LF);
        sb.append("[Install]").append(LF);
        sb.append("WantedBy=multi-user.target").append(LF);
        return sb.toString();
    }

}
