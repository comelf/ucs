package com.example;

import com.example.core.TuiApp;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

import static com.example.Main.APP_NAME;
import static com.example.Main.APP_VERSION;

@Command(
        name = APP_NAME,
        mixinStandardHelpOptions = true,
        version = APP_VERSION,
        description = "Full-screen TUI system command tool"
)
public class Main implements Callable<Integer> {

    public static final String APP_NAME = "Custom TUI 1.0";
    public static final String APP_VERSION = "1.0";

    @Override
    public Integer call() throws Exception {
        return TuiApp.create().run();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
