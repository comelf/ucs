package com.example.util.lang;

import com.example.config.TuiConfig;
import com.example.err.ErrorHandler;
import com.example.core.KeyCode;
import com.example.util.AnsiUtil;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

public class LangSelector {

    private final Terminal terminal;
    private final Attributes savedAttrs;
    private final TuiConfig config;
    private final ErrorHandler errorHandler;

    public LangSelector(Terminal terminal, Attributes savedAttrs, TuiConfig config) {
        this.terminal = terminal;
        this.savedAttrs = savedAttrs;
        this.config = config;
        this.errorHandler = new ErrorHandler();
    }

    /** 언어 선택 UI를 표시. 언어가 변경되면 true, 취소 시 false 반환 */
    public boolean select() {
        terminal.enterRawMode();

        Messages.Lang[] langs = Messages.Lang.values();
        int cursor = 0;
        for (int i = 0; i < langs.length; i++) {
            if (langs[i] == Messages.getLang()) {
                cursor = i;
                break;
            }
        }

        boolean changed = false;
        try {
            boolean selecting = true;
            while (selecting) {
                System.out.print(AnsiUtil.clearScreen());
                System.out.println("\n" + AnsiUtil.boldCyan(Messages.get("lang.select_title")) + "\n");
                for (int i = 0; i < langs.length; i++) {
                    String current = (langs[i] == Messages.getLang()) ? " *" : "";
                    if (i == cursor) {
                        System.out.println("  " + AnsiUtil.boldYellow("▶ " + langs[i].getDisplayName() + current));
                    } else {
                        System.out.println("    " + langs[i].getDisplayName() + current);
                    }
                }
                System.out.println("\n" + AnsiUtil.gray(Messages.get("lang.nav_hint")));
                System.out.flush();

                int ch = terminal.reader().read();
                if (ch == KeyCode.ESC) {
                    int next = terminal.reader().peek(50);
                    if (next == '[') {
                        terminal.reader().read();
                        int arrow = terminal.reader().read();
                        if (arrow == 'A' && cursor > 0) cursor--;
                        else if (arrow == 'B' && cursor < langs.length - 1) cursor++;
                    } else {
                        selecting = false;
                    }
                } else if (ch == KeyCode.CR || ch == KeyCode.LF) {
                    Messages.setLang(langs[cursor]);
                    config.setLocale(langs[cursor].getLocale());
                    config.save();
                    changed = true;
                    selecting = false;
                }
            }
        } catch (Exception e) {
            errorHandler.handle(e, "LangSelector");
        }

        terminal.setAttributes(savedAttrs);
        return changed;
    }
}
