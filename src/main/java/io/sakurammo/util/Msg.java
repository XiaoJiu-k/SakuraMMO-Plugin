package io.sakurammo.util;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 语言文件读写。所有面向玩家的中文都必须从这里取，禁止硬编码进 Java。
 */
public final class Msg {

    private static YamlConfiguration config = new YamlConfiguration();
    private static String prefix = "";
    private static Logger logger = Logger.getLogger("SakuraMMO");

    private Msg() {
    }

    public static void load(File file, Logger pluginLogger) {
        logger = pluginLogger;
        if (!file.isFile()) {
            pluginLogger.log(Level.WARNING, "语言文件不存在：" + file.getAbsolutePath());
            config = new YamlConfiguration();
            prefix = "";
            return;
        }
        config = YamlConfiguration.loadConfiguration(file);
        prefix = Text.color(config.getString("prefix", ""));
    }

    /** 取原始文本（不带前缀）。kv 为 {key, value, key, value, ...}。 */
    public static String raw(String key, String... kv) {
        String value = config.getString(key);
        if (value == null) {
            return Text.color("&c<missing message: " + key + ">");
        }
        return Text.color(replace(value, kv));
    }

    /** 取带前缀的完整行。 */
    public static String get(String key, String... kv) {
        return prefix + raw(key, kv);
    }

    public static void send(CommandSender sender, String key, String... kv) {
        sender.sendMessage(get(key, kv));
    }

    public static void sendRaw(CommandSender sender, String key, String... kv) {
        sender.sendMessage(raw(key, kv));
    }

    public static String prefix() {
        return prefix;
    }

    public static int size() {
        return config.getKeys(true).size();
    }

    private static String replace(String template, String... kv) {
        if (kv == null || kv.length == 0) {
            return template;
        }
        String out = template;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return out;
    }
}
