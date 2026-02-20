package com.example.cmd.shell;

import com.example.util.lang.Messages;

public enum ShellCommand {
    LS("ls -la", "desc.ls"),
    PS("ps aux", "desc.ps"),
    DF("df -h", "desc.df"),
    FREE("free -h", "desc.free"),
    TOP("top -bn1", "desc.top"),
    UNAME("uname -a", "desc.uname"),
    WHOAMI("whoami", "desc.whoami"),
    DATE("date", "desc.date"),
    NETSTAT("netstat -tlnp", "desc.netstat", "netstat -tlnp"),
    UPTIME("uptime", "desc.uptime"),
    ENV("env", "desc.env"),
    LSOF("lsof -i -P -n", "desc.lsof", "lsof -i -P -n"),
    DU("du -sh *", "desc.du", "du -sh *"),
    IOSTAT("iostat", "desc.iostat");

    private final String name;
    private final String descriptionKey;
    private final String shellCommand;

    ShellCommand(String shellCommand, String descriptionKey) {
        this.name = shellCommand;
        this.descriptionKey = descriptionKey;
        this.shellCommand = shellCommand;
    }

    ShellCommand(String name, String descriptionKey, String shellCommand) {
        this.name = name;
        this.descriptionKey = descriptionKey;
        this.shellCommand = shellCommand;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return Messages.get(descriptionKey);
    }

    public String getShellCommand() {
        return shellCommand;
    }
}
