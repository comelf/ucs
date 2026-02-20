package com.example.core;

import com.example.cmd.ShellRunner;
import com.example.cmd.custom.CustomCommand;
import com.example.cmd.custom.ScriptCommand;
import com.example.util.PathUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PluginLoader {

    private static final String PROPERTY_KEY = "tui.plugins.dir";
    private static final String DEFAULT_PATH = "plugins";

    private final Path pluginDir;
    private final ShellRunner shellRunner;

    public PluginLoader(ShellRunner shellRunner) {
        this.shellRunner = shellRunner;
        this.pluginDir = resolvePluginDir();
    }

    private static Path resolvePluginDir() {
        return PathUtil.resolve(PROPERTY_KEY, DEFAULT_PATH);
    }

    public Path getPluginDir() {
        return pluginDir;
    }

    public List<CustomCommand> loadAll() {
        if (!Files.isDirectory(pluginDir)) {
            return List.of();
        }
        List<CustomCommand> commands = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir, "*.sh")) {
            for (Path path : stream) {
                try {
                    commands.add(ScriptCommand.load(path, shellRunner));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return commands;
    }
}
