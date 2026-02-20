package com.example.cmd;

import com.example.cmd.custom.CustomCommand;
import com.example.cmd.shell.ShellCommand;

import java.util.function.Supplier;

public class CommandItem {

    public enum Type {SHELL, CUSTOM, LANG, GROUP, PLUGIN}

    private final String name;
    private final String description;
    private final Type type;
    private final String shellCommand;
    private final Supplier<CommandResult> executor;

    private CommandItem(String name, String description, Type type,
                        String shellCommand, Supplier<CommandResult> executor) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.shellCommand = shellCommand;
        this.executor = executor;
    }

    public static CommandItem shell(ShellCommand command) {
        return new CommandItem(command.getName(), command.getDescription(),
                Type.SHELL, command.getShellCommand(), null);
    }

    public static CommandItem custom(CustomCommand command) {
        return new CommandItem(command.getName(), command.getDescription(),
                Type.CUSTOM, null, command::execute);
    }

    public static CommandItem lang(String name, String description) {
        return new CommandItem(name, description, Type.LANG, null, null);
    }

    public static CommandItem group(String name, String description, String groupKey) {
        return new CommandItem(name, description, Type.GROUP, groupKey, null);
    }

    public String getGroupKey() { return shellCommand; }

    public static CommandItem plugin(CustomCommand command) {
        return new CommandItem(command.getName(), command.getDescription(),
                Type.PLUGIN, null, command::execute);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Type getType() { return type; }
    public String getShellCommand() { return shellCommand; }
    public Supplier<CommandResult> getExecutor() { return executor; }
}
