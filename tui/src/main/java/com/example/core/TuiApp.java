package com.example.core;

import com.example.cmd.CommandExecutor;
import com.example.cmd.CommandItem;
import com.example.cmd.CommandRegistry;
import com.example.cmd.ShellRunner;
import com.example.config.TuiConfig;
import com.example.util.lang.LangSelector;
import com.example.util.lang.Messages;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;

import java.util.List;
import java.util.Locale;

public class TuiApp {

    private enum Action {
        UP, DOWN, ENTER, FILTER, INPUT, LANG, QUIT, ESC, UNKNOWN
    }

    private final ShellRunner shellRunner;
    private final CommandRegistry registry;
    private final TuiConfig config;
    private Terminal terminal;
    private ScreenRenderer renderer;
    private CommandExecutor executor;
    private LangSelector langSelector;
    private Attributes savedAttrs;
    private int selectedIndex = 0;
    private AppMode mode = AppMode.NORMAL;
    private String inputBuffer = "";
    private String currentGroup = null;
    private List<CommandItem> currentItems;

    public TuiApp(ShellRunner shellRunner, CommandRegistry registry, TuiConfig config) {
        this.shellRunner = shellRunner;
        this.registry = registry;
        this.config = config;
        this.currentItems = registry.getTopLevel();
    }

    public static TuiApp create() {
        ShellRunner runner = new ShellRunner();
        TuiConfig config = TuiConfig.load();

        Locale locale = config.getLocale();
        if (locale != null) {
            for (Messages.Lang lang : Messages.Lang.values()) {
                if (lang.getLocale().getLanguage().equals(locale.getLanguage())) {
                    Messages.setLang(lang);
                    break;
                }
            }
        }

        return new TuiApp(runner, new CommandRegistry(runner), config);
    }

    public int run() throws Exception {
        terminal = TerminalBuilder.builder()
                .system(true)
                .nativeSignals(true)
                .jansi(true)
                .build();
        renderer = new ScreenRenderer(terminal);
        savedAttrs = terminal.enterRawMode();
        executor = new CommandExecutor(terminal, savedAttrs, renderer, shellRunner);
        langSelector = new LangSelector(terminal, savedAttrs, config);

        terminal.handle(Signal.WINCH, s -> renderer.reset());

        try {
            terminal.puts(Capability.enter_ca_mode);
            terminal.puts(Capability.cursor_invisible);
            terminal.flush();

            mainLoop();
        } finally {
            terminal.puts(Capability.cursor_visible);
            terminal.puts(Capability.exit_ca_mode);
            terminal.flush();
            terminal.setAttributes(savedAttrs);
            terminal.close();
        }
        return 0;
    }

    private List<CommandItem> getBaseItems() {
        return currentGroup != null ? registry.getGroupItems(currentGroup) : registry.getTopLevel();
    }

    private void mainLoop() {
        BindingReader bindingReader = new BindingReader(terminal.reader());
        KeyMap<Action> keyMap = buildKeyMap();

        boolean running = true;
        while (running) {
            renderer.render(currentItems, selectedIndex, mode, inputBuffer, currentGroup != null);

            if (mode == AppMode.NORMAL) {
                Action action = bindingReader.readBinding(keyMap);
                if (action == null) action = Action.UNKNOWN;

                switch (action) {
                    case UP:
                        if (!currentItems.isEmpty() && selectedIndex > 0) selectedIndex--;
                        break;
                    case DOWN:
                        if (!currentItems.isEmpty() && selectedIndex < currentItems.size() - 1) selectedIndex++;
                        break;
                    case ENTER:
                        if (!currentItems.isEmpty()) {
                            CommandItem selected = currentItems.get(selectedIndex);
                            if (selected.getType() == CommandItem.Type.GROUP) {
                                currentGroup = selected.getGroupKey();
                                currentItems = registry.getGroupItems(currentGroup);
                                selectedIndex = 0;
                            } else if (selected.getType() == CommandItem.Type.LANG) {
                                executeLangSelect();
                            } else {
                                executor.executeCommand(selected);
                            }
                        }
                        break;
                    case FILTER:
                        mode = AppMode.FILTER;
                        inputBuffer = "";
                        break;
                    case INPUT:
                        mode = AppMode.INPUT;
                        inputBuffer = "";
                        break;
                    case LANG:
                        executeLangSelect();
                        break;
                    case ESC:
                        if (currentGroup != null) {
                            currentGroup = null;
                            currentItems = registry.getTopLevel();
                            selectedIndex = 0;
                        }
                        break;
                    case QUIT:
                        running = false;
                        break;
                    default:
                        break;
                }
            } else {
                // FILTER 또는 INPUT 모드
                int ch = bindingReader.readCharacter();
                if (ch == KeyCode.ESC) {
                    AppMode previousMode = mode;
                    mode = AppMode.NORMAL;
                    inputBuffer = "";
                    if (previousMode == AppMode.FILTER) {
                        currentItems = getBaseItems();
                        selectedIndex = 0;
                    } else {
                        clampSelectedIndex();
                    }
                } else if (ch == KeyCode.CR || ch == KeyCode.LF) {
                    if (mode == AppMode.FILTER) {
                        mode = AppMode.NORMAL;
                        // 필터 결과 유지
                    } else if (mode == AppMode.INPUT) {
                        if (!inputBuffer.isBlank()) {
                            executor.executeShellDirect(inputBuffer);
                        }
                        mode = AppMode.NORMAL;
                        inputBuffer = "";
                        currentItems = getBaseItems();
                        clampSelectedIndex();
                    }
                } else if (ch == KeyCode.BACKSPACE || ch == KeyCode.CTRL_H) {
                    if (!inputBuffer.isEmpty()) {
                        inputBuffer = inputBuffer.substring(0, inputBuffer.length() - 1);
                    }
                    if (mode == AppMode.FILTER) {
                        currentItems = registry.filter(getBaseItems(), inputBuffer);
                        selectedIndex = 0;
                    }
                } else if (ch >= KeyCode.SPACE) {
                    inputBuffer += (char) ch;
                    if (mode == AppMode.FILTER) {
                        currentItems = registry.filter(getBaseItems(), inputBuffer);
                        selectedIndex = 0;
                    }
                }
            }
        }
    }

    private void executeLangSelect() {
        executor.leaveAlternateScreen();
        if (langSelector.select()) {
            registry.refresh();
            currentItems = getBaseItems();
            clampSelectedIndex();
        }
        executor.enterAlternateScreen();
    }

    private void clampSelectedIndex() {
        selectedIndex = currentItems.isEmpty() ? 0
                : Math.min(selectedIndex, currentItems.size() - 1);
    }

    private KeyMap<Action> buildKeyMap() {
        KeyMap<Action> keyMap = new KeyMap<>();
        keyMap.setAmbiguousTimeout(100);

        // 방향키
        keyMap.bind(Action.UP, KeyMap.key(terminal, Capability.key_up));
        keyMap.bind(Action.DOWN, KeyMap.key(terminal, Capability.key_down));
        keyMap.bind(Action.UP, "\033[A");
        keyMap.bind(Action.DOWN, "\033[B");

        // Enter
        keyMap.bind(Action.ENTER, "\r");
        keyMap.bind(Action.ENTER, "\n");

        // ESC (ambiguousTimeout으로 방향키와 구분)
        keyMap.bind(Action.ESC, "\033");

        // 모드 전환
        keyMap.bind(Action.FILTER, "/");
        keyMap.bind(Action.INPUT, ":");

        // 언어 전환
        keyMap.bind(Action.LANG, "l");

        // 종료
        keyMap.bind(Action.QUIT, "q");
        keyMap.bind(Action.QUIT, KeyMap.ctrl('C'));

        return keyMap;
    }
}
