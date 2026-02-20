package com.example.cmd.custom;

import com.example.err.TuiException;
import com.example.cmd.CommandResult;
import com.example.util.lang.Messages;
import com.example.cmd.ShellRunner;

public class LargeFilesCommand implements CustomCommand {

    private final ShellRunner shellRunner;

    public LargeFilesCommand(ShellRunner shellRunner) {
        this.shellRunner = shellRunner;
    }

    @Override
    public String getName() {
        return Messages.get("cmd.largefiles");
    }

    @Override
    public String getDescription() {
        return Messages.get("desc.largefiles");
    }

    @Override
    public CommandResult execute() {
        try {
            String result = shellRunner.runQuiet("find / -type f -size +100M -exec ls -lh {} + 2>/dev/null | head -20");
            if (result.isEmpty()) {
                return CommandResult.success(Messages.get("out.large_none"));
            }
            return CommandResult.success(Messages.get("out.large_title") + "\n\n" + result);
        } catch (TuiException e) {
            return CommandResult.failure(e);
        }
    }
}
