package me.dave.activityrewarder.config;

import me.dave.activityrewarder.ActivityRewarder;
import me.dave.activityrewarder.gui.InventoryHandler;
import me.dave.activityrewarder.importer.internal.ActivityRewarderConfigUpdater;
import me.dave.activityrewarder.module.dailyrewards.DailyRewardsModule;
import me.dave.activityrewarder.module.playtimedailygoals.PlaytimeDailyGoalsModule;
import me.dave.activityrewarder.module.playtimeglobalgoals.PlaytimeGlobalGoalsModule;
import me.dave.activityrewarder.module.playtimetracker.PlaytimeTrackerModule;
import me.dave.activityrewarder.utils.Debugger;
import me.dave.platyutils.module.Module;
import me.dave.platyutils.utils.SimpleItemStack;
import me.dave.platyutils.utils.StringUtils;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {
    private static final File modulesFolder = new File(ActivityRewarder.getInstance().getDataFolder(), "modules");

    private final ConcurrentHashMap<String, SimpleItemStack> categoryItems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SimpleItemStack> itemTemplates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messages = new ConcurrentHashMap<>();
    private File rewardsFile;
    private File dailyGoalsFile;
    private File globalGoalsFile;
    private boolean performanceMode;
    private LocalDate date;
    private boolean playtimeIgnoreAfk;
    private int reminderPeriod;
    private Sound reminderSound;

    public ConfigManager() {
        if (isOutdated()) {
            try {
                new ActivityRewarderConfigUpdater().startImport().thenAccept(success -> reloadConfig());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        ActivityRewarder.getInstance().saveDefaultConfig();
        ActivityRewarder.getInstance().saveDefaultResource("modules/daily-rewards.yml");
        ActivityRewarder.getInstance().saveDefaultResource("modules/daily-playtime-goals.yml");
        ActivityRewarder.getInstance().saveDefaultResource("modules/global-playtime-goals.yml");
    }

    public void reloadConfig() {
        InventoryHandler.closeAll();

        ActivityRewarder.getInstance().reloadConfig();
        FileConfiguration config = ActivityRewarder.getInstance().getConfig();

        Debugger.setDebugMode(Debugger.DebugMode.valueOf(config.getString("debug-mode", "NONE").toUpperCase()));
        performanceMode = config.getBoolean("enable-performance-mode", false);
        if (performanceMode) {
            date = LocalDate.now();
        }

        playtimeIgnoreAfk = config.getBoolean("playtime-ignore-afk", true);
        reminderPeriod = config.getInt("reminder-period", 1800) * 20;
        reminderSound = StringUtils.getEnum(config.getString("reminder-sound", "none"), Sound.class).orElse(null);

        ActivityRewarder.getInstance().unregisterModule(DailyRewardsModule.ID);
        ActivityRewarder.getInstance().unregisterModule(PlaytimeDailyGoalsModule.ID);
        ActivityRewarder.getInstance().unregisterModule(PlaytimeGlobalGoalsModule.ID);



        boolean requiresPlaytimeTracker = false;
        if (config.getBoolean("modules.daily-rewards", false)) {
            ActivityRewarder.getInstance().registerModule(new DailyRewardsModule(DailyRewardsModule.ID));
        }
        if (config.getBoolean("modules.daily-playtime-goals", false)) {
            ActivityRewarder.getInstance().registerModule(new PlaytimeDailyGoalsModule(PlaytimeDailyGoalsModule.ID));
            requiresPlaytimeTracker = true;
        }
        if (config.getBoolean("modules.global-playtime-goals", false)) {
            ActivityRewarder.getInstance().registerModule(new PlaytimeGlobalGoalsModule(PlaytimeGlobalGoalsModule.ID));
            requiresPlaytimeTracker = true;
        }

        Optional<Module> playtimeTrackerModule = ActivityRewarder.getInstance().getModule(PlaytimeTrackerModule.ID);
        if (requiresPlaytimeTracker && playtimeTrackerModule.isEmpty()) {
            ActivityRewarder.getInstance().registerModule(new PlaytimeTrackerModule(PlaytimeTrackerModule.ID));
        } else if (!requiresPlaytimeTracker && playtimeTrackerModule.isPresent()) {
            ActivityRewarder.getInstance().unregisterModule(PlaytimeTrackerModule.ID);
        }

        boolean enableUpdater = config.getBoolean("enable-updater", true);
        ActivityRewarder.getInstance().getUpdater().setEnabled(enableUpdater);
        if (enableUpdater) {
            ActivityRewarder.getInstance().getUpdater().queueCheck();
        }

        reloadCategoryMap(config.getConfigurationSection("categories"));
        reloadItemTemplates(config.getConfigurationSection("item-templates"));
        reloadMessages(config.getConfigurationSection("messages"));
        ActivityRewarder.getNotificationHandler().reloadNotifications();

        if (ActivityRewarder.getDataManager() != null) {
            ActivityRewarder.getDataManager().reloadRewardUsers();
        }
    }

    public YamlConfiguration getDailyRewardsConfig() {
        return YamlConfiguration.loadConfiguration(rewardsFile);
    }

    public YamlConfiguration getDailyGoalsConfig() {
        return YamlConfiguration.loadConfiguration(dailyGoalsFile);
    }

    public YamlConfiguration getGlobalGoalsConfig() {
        return YamlConfiguration.loadConfiguration(globalGoalsFile);
    }

    public String getMessage(String messageName) {
        return getMessage(messageName, "");
    }

    public String getMessage(String messageName, String def) {
        String output = messages.getOrDefault(messageName, def);

        if (messages.containsKey("prefix")) {
            return output.replaceAll("%prefix%", messages.get("prefix"));
        } else {
            return output;
        }
    }

    public Collection<String> getMessages() {
        return messages.values();
    }

    public SimpleItemStack getCategoryTemplate(String category) {
        SimpleItemStack itemTemplate = categoryItems.get(category.toLowerCase());
        if (itemTemplate == null) {
            ActivityRewarder.getInstance().getLogger().severe("Could not find category '" + category + "'");
            return new SimpleItemStack();
        }

        return itemTemplate.clone();
    }

    public SimpleItemStack getItemTemplate(String key) {
        SimpleItemStack itemTemplate = itemTemplates.get(key);
        if (itemTemplate == null) {
            ActivityRewarder.getInstance().getLogger().severe("Could not find item-template '" + key + "'");
            return new SimpleItemStack();
        }

        return itemTemplate.clone();
    }

    public boolean isPerformanceModeEnabled() {
        return performanceMode;
    }

    public void checkRefresh() {
        if (performanceMode && !date.isEqual(LocalDate.now())) {
            reloadConfig();
        }
    }

    public boolean getPlaytimeIgnoreAfk() {
        return playtimeIgnoreAfk;
    }

    public int getReminderPeriod() {
        return reminderPeriod;
    }

    public Sound getReminderSound() {
        return reminderSound;
    }

    private void reloadCategoryMap(ConfigurationSection categoriesSection) {
        // Clears category map
        categoryItems.clear();

        // Checks if categories section exists
        if (categoriesSection == null) {
            return;
        }

        // Repopulates category map
        categoriesSection.getValues(false).forEach((key, value) -> {
            if (value instanceof ConfigurationSection categorySection) {
                categoryItems.put(categorySection.getName(), SimpleItemStack.from(categorySection));
            }
        });
    }

    private void reloadItemTemplates(ConfigurationSection itemTemplatesSection) {
        // Clears category map
        itemTemplates.clear();

        // Checks if categories section exists
        if (itemTemplatesSection == null) {
            return;
        }

        // Repopulates category map
        itemTemplatesSection.getValues(false).forEach((key, value) -> {
            if (value instanceof ConfigurationSection categorySection) {
                itemTemplates.put(categorySection.getName(), SimpleItemStack.from(categorySection));
                ActivityRewarder.getInstance().getLogger().info("Loaded item-template: " + categorySection.getName());
            }
        });
    }

    private void reloadMessages(ConfigurationSection messagesSection) {
        // Clears messages map
        messages.clear();

        // Checks if messages section exists
        if (messagesSection == null) {
            return;
        }

        // Repopulates messages map
        messagesSection.getValues(false).forEach((key, value) -> messages.put(key, (String) value));
    }

    private void initRewardsYmls() {
        ActivityRewarder plugin = ActivityRewarder.getInstance();

        File dailyRewardsFile = new File(plugin.getDataFolder(), "modules/daily-rewards.yml");
        if (!dailyRewardsFile.exists()) {
            plugin.saveResource("modules/daily-rewards.yml", false);
            plugin.getLogger().info("File Created: daily-rewards.yml");
        }

        File dailyGoalsFile = new File(plugin.getDataFolder(), "modules/daily-playtime-goals.yml");
        if (!dailyGoalsFile.exists()) {
            plugin.saveResource("modules/daily-playtime-goals.yml", false);
            plugin.getLogger().info("File Created: daily-playtime-goals.yml");
        }

        File globalGoalsFile = new File(plugin.getDataFolder(), "modules/global-playtime-goals.yml");
        if (!globalGoalsFile.exists()) {
            plugin.saveResource("modules/global-playtime-goals.yml", false);
            plugin.getLogger().info("File Created: global-playtime-goals.yml");
        }

        this.rewardsFile = dailyRewardsFile;
        this.dailyGoalsFile = dailyGoalsFile;
        this.globalGoalsFile = globalGoalsFile;
    }

    private boolean isOutdated() {
        FileConfiguration config = ActivityRewarder.getInstance().getConfig();

        return config.contains("reward-days", true) && !config.contains("modules", true);
    }
}
