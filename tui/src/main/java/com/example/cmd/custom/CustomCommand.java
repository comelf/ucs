package com.example.cmd.custom;

import com.example.cmd.CommandResult;

public interface CustomCommand {
    String getName();
    String getDescription();
    CommandResult execute();
}
