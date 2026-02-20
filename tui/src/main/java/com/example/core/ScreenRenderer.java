package com.example.core;

import com.example.cmd.CommandItem;
import com.example.cmd.CommandRegistry;
import com.example.util.lang.Messages;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.WCWidth;

import java.util.ArrayList;
import java.util.List;

import static com.example.Main.APP_NAME;

public class ScreenRenderer {

    private final Terminal terminal;
    private final Display display;

    public ScreenRenderer(Terminal terminal) {
        this.terminal = terminal;
        this.display = new Display(terminal, true);
    }

    /**
     * Display 캐시를 초기화하여 다음 render에서 전체 다시 그리기
     */
    public void reset() {
        display.clear();
    }

    /**
     * 문자열의 터미널 표시 너비를 계산 (CJK 문자 = 2칸)
     */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = WCWidth.wcwidth(cp);
            w += (cw > 0) ? cw : 1;
            i += Character.charCount(cp);
        }
        return w;
    }

    /**
     * 문자열을 targetColumns 너비로 오른쪽 공백 패딩
     */
    private static String padRight(String s, int targetColumns) {
        int dw = displayWidth(s);
        int padding = targetColumns - dw;
        if (padding <= 0) return s;
        return s + " ".repeat(padding);
    }

    public void render(List<CommandItem> items, int selectedIndex,
                       AppMode mode, String filterText, boolean inSubmenu) {
        int width = terminal.getWidth();
        int height = terminal.getHeight();
        List<AttributedString> lines = new ArrayList<>();

        // 헤더
        lines.add(buildHeader(width));
        lines.add(buildSeparator(width));

        // 명령어 목록 (헤더2줄 + 하단2줄 = 4줄 제외)
        int listHeight = height - 4;
        if (listHeight < 1) listHeight = 1;

        int scrollOffset = 0;
        if (selectedIndex >= listHeight) {
            scrollOffset = selectedIndex - listHeight + 1;
        }

        for (int i = 0; i < listHeight; i++) {
            int idx = scrollOffset + i;
            if (idx < items.size()) {
                lines.add(buildItemLine(items.get(idx), idx == selectedIndex, width));
            } else {
                lines.add(new AttributedString(""));
            }
        }

        // 하단 구분선 + 상태바
        lines.add(buildSeparator(width));
        lines.add(buildStatusBar(mode, filterText, width, selectedIndex, items.size(), inSubmenu));

        display.resize(height, width);
        display.update(lines, -1);
    }

    private AttributedString buildHeader(int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();

        String left = "  " + APP_NAME;
        String right = Messages.get("header.right");
        int leftW = displayWidth(left);
        int rightW = displayWidth(right);
        int pad = width - leftW - rightW;

        sb.style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN));
        sb.append(left);
        sb.style(AttributedStyle.DEFAULT);
        if (pad > 0) sb.append(" ".repeat(pad));
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
        sb.append(right);
        return sb.toAttributedString();
    }

    private AttributedString buildSeparator(int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
        // '-' (ASCII)를 사용하여 모든 터미널에서 1칸 보장
        sb.append("-".repeat(Math.max(width, 1)));
        return sb.toAttributedString();
    }

    private AttributedString buildItemLine(CommandItem item, boolean selected, int width) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        int usedColumns = 0;

        // 커서
        if (selected) {
            sb.style(AttributedStyle.BOLD.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE));
            sb.append("▶ ");
            usedColumns += displayWidth("▶ "); // 3칸
        } else {
            sb.style(AttributedStyle.DEFAULT);
            sb.append("  ");
            usedColumns += 2;
        }

        // 타입 태그
        String tag;
        if (item.getType() == CommandItem.Type.SHELL) {
            sb.style(selected
                    ? AttributedStyle.BOLD.background(AttributedStyle.BLUE).foreground(AttributedStyle.GREEN)
                    : AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
            tag = "[SHELL]  ";
        } else if (item.getType() == CommandItem.Type.GROUP) {
            sb.style(selected
                    ? AttributedStyle.BOLD.background(AttributedStyle.BLUE).foreground(AttributedStyle.YELLOW)
                    : AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW));
            tag = CommandRegistry.GROUP_PLUGIN.equals(item.getGroupKey())
                    ? "[PLUGIN] " : "[SHELL]  ";
        } else if (item.getType() == CommandItem.Type.PLUGIN) {
            sb.style(selected
                    ? AttributedStyle.BOLD.background(AttributedStyle.BLUE).foreground(AttributedStyle.GREEN)
                    : AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
            tag = "[PLUGIN] ";
        } else if (item.getType() == CommandItem.Type.LANG) {
            sb.style(selected
                    ? AttributedStyle.BOLD.background(AttributedStyle.BLUE).foreground(AttributedStyle.CYAN)
                    : AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
            tag = "[LANG]   ";
        } else {
            sb.style(selected
                    ? AttributedStyle.BOLD.background(AttributedStyle.BLUE).foreground(AttributedStyle.MAGENTA)
                    : AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA));
            tag = "[CUSTOM] ";
        }
        sb.append(tag);
        usedColumns += displayWidth(tag);

        // 이름 (표시 너비 기준 20칸으로 패딩)
        sb.style(selected
                ? AttributedStyle.BOLD.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE)
                : AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
        String paddedName = padRight(item.getName(), 20);
        sb.append(paddedName);
        usedColumns += 20; // padRight가 정확히 20칸을 보장

        // 설명
        sb.style(selected
                ? AttributedStyle.DEFAULT.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE)
                : AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT));
        sb.append(item.getDescription());
        usedColumns += displayWidth(item.getDescription());

        // GROUP 항목: 하위 메뉴 진입 표시
        if (item.getType() == CommandItem.Type.GROUP) {
            sb.append(" ▸");
            usedColumns += displayWidth(" ▸");
        }

        // 선택 행: 나머지를 공백으로 채워 배경색 유지
        if (selected) {
            int remaining = width - usedColumns;
            if (remaining > 0) {
                sb.append(" ".repeat(remaining));
            }
        }

        return sb.toAttributedString();
    }

    private AttributedString buildStatusBar(AppMode mode, String filterText,
                                            int width, int selectedIndex, int totalItems,
                                            boolean inSubmenu) {
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW));

        switch (mode) {
            case FILTER:
                sb.append(" /");
                sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
                sb.append(filterText);
                sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT));
                sb.append("  ").append(Messages.get("status.filter"));
                break;
            case INPUT:
                sb.append(" :");
                sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE));
                sb.append(filterText);
                sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT));
                sb.append("  ").append(Messages.get("status.input"));
                break;
            default:
                String statusKey = inSubmenu ? "status.submenu" : "status.normal";
                sb.append(" ").append(Messages.get(statusKey));
                sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT));
                sb.append(String.format("  [%d/%d]", selectedIndex + 1, totalItems));
                break;
        }

        return sb.toAttributedString();
    }
}
