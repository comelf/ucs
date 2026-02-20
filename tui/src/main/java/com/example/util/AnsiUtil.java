package com.example.util;

public class AnsiUtil {
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_BLACK = "\u001B[30m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_BLUE = "\u001B[34m";
    public static final String ANSI_PURPLE = "\u001B[35m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_WHITE = "\u001B[37m";
    public static final String ANSI_WHITE_ON_NAVY = "\u001B[37;48;5;17m"; // white on dark blue
    public static final String ANSI_WHITE_ON_BLUE = "\u001B[37;44m";
    public static final String ANSI_BOLD = "\u001B[1m";
    public static final String ANSI_UNDERLINE = "\u001B[4m";
    public static final String ANSI_GRAY = "\u001B[90m";
    public static final String ANSI_CLEAR_SCREEN = "\u001B[2J\u001B[H";

    public static String red(Object s) {
        return ANSI_RED + s + ANSI_RESET;
    }

    public static String yellow(Object s) {
        return ANSI_YELLOW + s + ANSI_RESET;
    }

    public static String green(Object s) {
        return ANSI_GREEN + s + ANSI_RESET;
    }

    public static String cyan(Object s) {
        return ANSI_CYAN + s + ANSI_RESET;
    }

    public static String blue(Object s) {
        return ANSI_BLUE + s + ANSI_RESET;
    }

    public static String purple(Object s) {
        return ANSI_PURPLE + s + ANSI_RESET;
    }

    public static String whiteOnBlue(Object s) {
        return ANSI_WHITE_ON_BLUE + s + ANSI_RESET;
    }

    public static String whiteOnNavy(Object s) {
        return ANSI_WHITE_ON_NAVY + s + ANSI_RESET;
    }

    public static String bold(Object s) {
        return ANSI_BOLD + s + ANSI_RESET;
    }

    public static String boldRed(Object s) {
        return "\u001B[1;31m" + s + ANSI_RESET;
    }

    public static String boldYellow(Object s) {
        return "\u001B[1;33m" + s + ANSI_RESET;
    }

    public static String boldCyan(Object s) {
        return "\u001B[1;36m" + s + ANSI_RESET;
    }

    public static String gray(Object s) {
        return ANSI_GRAY + s + ANSI_RESET;
    }

    public static String underline(Object s) {
        return ANSI_UNDERLINE + s + ANSI_RESET;
    }

    public static String clearScreen() {
        return ANSI_CLEAR_SCREEN;
    }
}
