package com.example.cmd.custom;

import com.example.err.TuiException;
import com.example.cmd.CommandResult;
import com.example.cmd.ShellRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ScriptCommand implements CustomCommand {

    private final String name;
    private final String description;
    private final Path scriptPath;
    private final ShellRunner shellRunner;

    private ScriptCommand(String name, String description, Path scriptPath, ShellRunner shellRunner) {
        this.name = name;
        this.description = description;
        this.scriptPath = scriptPath;
        this.shellRunner = shellRunner;
    }

    /**
     * 스크립트 파일에서 메타데이터를 파싱하여 ScriptCommand를 생성.
     * 헤더 포맷:
     * <pre>
     * # name=My Plugin
     * # description=Does something useful
     * </pre>
     */
    public static ScriptCommand load(Path path, ShellRunner shellRunner) throws IOException {
        String fileName = path.getFileName().toString();
        String defaultName = fileName.replaceFirst("\\.sh$", "");
        String name = defaultName;
        String description = "";

        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("#")) break;
            String comment = trimmed.substring(1).trim();
            if (comment.startsWith("name=")) {
                name = comment.substring("name=".length()).trim();
            } else if (comment.startsWith("description=")) {
                description = comment.substring("description=".length()).trim();
            }
        }

        return new ScriptCommand(name, description, path, shellRunner);
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() { return description; }

    @Override
    public CommandResult execute() {
        try {
            return CommandResult.success(shellRunner.runQuiet("sh " + scriptPath.toAbsolutePath()));
        } catch (TuiException e) {
            return CommandResult.failure(e);
        }
    }
}
