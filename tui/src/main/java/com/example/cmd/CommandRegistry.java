package com.example.cmd;

import com.example.core.PluginLoader;
import com.example.cmd.custom.CustomCommand;
import com.example.cmd.custom.LargeFilesCommand;
import com.example.cmd.custom.SystemSummaryCommand;
import com.example.cmd.shell.ShellCommand;
import com.example.util.lang.Messages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CommandRegistry {

    public static final String GROUP_SHELL = "SHELL";
    public static final String GROUP_PLUGIN = "PLUGIN";

    private final List<CommandItem> topLevel = new ArrayList<>();
    private final Map<String, List<CommandItem>> groups = new LinkedHashMap<>();
    private final ShellRunner shellRunner;
    private final PluginLoader pluginLoader;

    public CommandRegistry(ShellRunner shellRunner) {
        this.shellRunner = shellRunner;
        this.pluginLoader = new PluginLoader(shellRunner);
        registerDefaults();
    }

    private void registerDefaults() {
        topLevel.clear();
        groups.clear();

        // Shell 그룹
        List<CommandItem> shellItems = new ArrayList<>();
        for (ShellCommand cmd : ShellCommand.values()) {
            shellItems.add(CommandItem.shell(cmd));
        }
        groups.put(GROUP_SHELL, shellItems);
        topLevel.add(CommandItem.group(Messages.get("cmd.shell"), Messages.get("desc.shell_group"), GROUP_SHELL));

        // Custom 명령어
        topLevel.add(CommandItem.custom(new SystemSummaryCommand(shellRunner)));
        topLevel.add(CommandItem.custom(new LargeFilesCommand(shellRunner)));

        // Plugin 그룹 (플러그인이 있을 때만)
        List<CustomCommand> plugins = pluginLoader.loadAll();
        if (!plugins.isEmpty()) {
            List<CommandItem> pluginItems = new ArrayList<>();
            for (CustomCommand plugin : plugins) {
                pluginItems.add(CommandItem.plugin(plugin));
            }
            groups.put(GROUP_PLUGIN, pluginItems);
            topLevel.add(CommandItem.group(Messages.get("cmd.plugin"), Messages.get("desc.plugin_group"), GROUP_PLUGIN));
        }

        topLevel.add(CommandItem.lang(Messages.get("cmd.lang"), Messages.get("desc.lang")));
    }

    public void refresh() {
        registerDefaults();
    }

    public List<CommandItem> getTopLevel() {
        return List.copyOf(topLevel);
    }

    public List<CommandItem> getGroupItems(String groupKey) {
        List<CommandItem> items = groups.get(groupKey);
        return items != null ? List.copyOf(items) : List.of();
    }

    public List<CommandItem> filter(List<CommandItem> items, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return items;
        }
        String lower = keyword.toLowerCase();
        return items.stream()
                .filter(c -> c.getName().toLowerCase().contains(lower)
                        || c.getDescription().toLowerCase().contains(lower))
                .collect(Collectors.toList());
    }
}
