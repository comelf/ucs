package com.example.cmd.custom;

import com.example.err.TuiException;
import com.example.cmd.CommandResult;
import com.example.util.lang.Messages;
import com.example.cmd.ShellRunner;

public class SystemSummaryCommand implements CustomCommand {

    private final ShellRunner shellRunner;

    public SystemSummaryCommand(ShellRunner shellRunner) {
        this.shellRunner = shellRunner;
    }

    @Override
    public String getName() {
        return Messages.get("cmd.summary");
    }

    @Override
    public String getDescription() {
        return Messages.get("desc.summary");
    }

    @Override
    public CommandResult execute() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(Messages.get("out.summary_title")).append("\n\n");
            sb.append("[OS] ").append(shellRunner.runQuiet("uname -a")).append("\n");
            sb.append("[User] ").append(shellRunner.runQuiet("whoami")).append("\n");
            sb.append("[Date] ").append(shellRunner.runQuiet("date")).append("\n");
            sb.append("[Uptime] ").append(shellRunner.runQuiet("uptime")).append("\n");
            sb.append("[Disk]\n").append(shellRunner.runQuiet("df -h")).append("\n");
            return CommandResult.success(sb.toString());
        } catch (TuiException e) {
            return CommandResult.failure(e);
        }
    }
}
