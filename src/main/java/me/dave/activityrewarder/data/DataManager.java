package me.dave.activityrewarder.data;

`import me.dave.activityrewarder.ActivityRewarder;
import me.dave.activityrewarder.api.event.RewardUserLoadEvent;
import me.dave.activityrewarder.api.event.RewardUserUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.enchantedskies.EnchantedStorage.IOHandler;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DataManager {
    private final IOHandler<RewardUser> ioHandler = new IOHandler<>(new YmlStorage());
    private final ConcurrentHashMap<UUID, RewardUser> uuidToRewardUser = new ConcurrentHashMap<>();

    public DataManager() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            getOrLoadRewardUser(player).thenAccept((rewardUser) -> rewardUser.setUsername(player.getName()));
        }
    }

    @NotNull
    public CompletableFuture<RewardUser> getOrLoadRewardUser(@NotNull Player player) {
        CompletableFuture<RewardUser> completableFuture = new CompletableFuture<>();

        UUID uuid = player.getUniqueId();
        RewardUser rewardUser = uuidToRewardUser.get(uuid);
        if (rewardUser != null) {
            completableFuture.complete(rewardUser);
        } else {
            loadRewardUser(uuid).thenAccept(completableFuture::complete);
        }

        return completableFuture;
    }

    public CompletableFuture<RewardUser> loadRewardUser(UUID uuid) {
        return ioHandler.loadPlayer(uuid).thenApply((rewardUser) -> {
            uuidToRewardUser.put(uuid, rewardUser);
            Bukkit.getScheduler().runTask(ActivityRewarder.getInstance(), () -> ActivityRewarder.getInstance().callEvent(new RewardUserLoadEvent(rewardUser)));
            return rewardUser;
        });
    }

    public void unloadRewarderUser(UUID uuid) {
        RewardUser rewardUser = uuidToRewardUser.get(uuid);

        if (rewardUser != null && ActivityRewarder.getInstance().callEvent(new RewardUserUnloadEvent(rewardUser))) {
            uuidToRewardUser.remove(uuid);
        }
    }

    public void saveRewardUser(RewardUser rewardUser) {
        ioHandler.savePlayer(rewardUser);
    }

    public void saveAll() {
        uuidToRewardUser.values().forEach(this::saveRewardUser);
    }

    @NotNull
    public RewardUser getRewardUser(@NotNull Player player) {
        UUID uuid = player.getUniqueId();

        RewardUser rewardUser = uuidToRewardUser.get(uuid);
        if (rewardUser == null) {
            rewardUser = new RewardUser(uuid, player.getName(), LocalDate.now().toString(), LocalDate.now().minusDays(1).toString(), 1, 1, 0, 0);
        }

        return rewardUser;
    }

    public boolean isRewardUserLoaded(UUID uuid) {
        return uuidToRewardUser.containsKey(uuid);
    }

    public IOHandler<RewardUser> getIoHandler() {
        return ioHandler;
    }
}
