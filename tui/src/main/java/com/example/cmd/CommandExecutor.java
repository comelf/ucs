package com.example.cmd;

import com.example.err.ErrorHandler;
import com.example.core.KeyCode;
import com.example.core.ScreenRenderer;
import com.example.util.AnsiUtil;
import com.example.util.lang.Messages;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

public class CommandExecutor {

    private final Terminal terminal;
    private final Attributes savedAttrs;
    private final ScreenRenderer renderer;
    private final ShellRunner shellRunner;
    private final ErrorHandler errorHandler;

    public CommandExecutor(Terminal terminal, Attributes savedAttrs,
                           ScreenRenderer renderer, ShellRunner shellRunner) {
        this.terminal = terminal;
        this.savedAttrs = savedAttrs;
        this.renderer = renderer;
        this.shellRunner = shellRunner;
        this.errorHandler = new ErrorHandler();
    }

    /**
     * 명령어 항목(SHELL/CUSTOM)을 전체화면 모드에서 실행
     */
    public void executeCommand(CommandItem item) {
        runInFullScreen(item.getName(), () -> {
            if (item.getType() == CommandItem.Type.SHELL) {
                shellRunner.runInteractive(item.getShellCommand());
            } else {
                CommandResult result = item.getExecutor().get();
                if (result.success()) {
                    System.out.println(result.output());
                } else {
                    errorHandler.handle(result.error(), item.getName());
                }
            }
        });
    }

    /**
     * 직접 입력한 셸 명령어를 전체화면 모드에서 실행
     */
    public void executeShellDirect(String command) {
        runInFullScreen(command, () -> shellRunner.runInteractive(command));
    }

    /**
     * 대체 화면을 나가고 action을 실행한 뒤 복귀하는 공통 패턴
     */
    private void runInFullScreen(String title, ThrowingRunnable action) {
        leaveAlternateScreen();

        try {
            System.out.println("\n" + AnsiUtil.boldCyan("=== " + title + " ===") + "\n");
            action.run();
            System.out.println("\n" + AnsiUtil.boldYellow(Messages.get("msg.press_enter")));
            waitForEnter();
        } catch (Exception e) {
            errorHandler.handle(e, title);
            System.out.println("\n" + AnsiUtil.boldYellow(Messages.get("msg.press_enter")));
            waitForEnter();
        }

        enterAlternateScreen();
    }

    public void leaveAlternateScreen() {
        terminal.puts(Capability.cursor_visible);
        terminal.puts(Capability.exit_ca_mode);
        terminal.flush();
        terminal.setAttributes(savedAttrs);
    }

    public void enterAlternateScreen() {
        terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.cursor_invisible);
        terminal.flush();
        renderer.reset();
    }

    private void waitForEnter() {
        try {
            System.out.flush();
            terminal.enterRawMode();
            int ch;
            do {
                ch = terminal.reader().read();
            } while (ch != KeyCode.CR && ch != KeyCode.LF && ch != KeyCode.ESC);
            terminal.setAttributes(savedAttrs);
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
