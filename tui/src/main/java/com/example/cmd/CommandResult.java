package com.example.cmd;

public record CommandResult(boolean success, String output, Exception error) {
    public static CommandResult success(String output) {
        return new CommandResult(true, output, null);
    }
    public static CommandResult failure(Exception e) {
        return new CommandResult(false, null, e);
    }
}
