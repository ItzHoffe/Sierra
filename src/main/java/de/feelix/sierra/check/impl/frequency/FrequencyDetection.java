package de.feelix.sierra.check.impl.frequency;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import de.feelix.sierra.Sierra;
import de.feelix.sierra.check.SierraDetection;
import de.feelix.sierra.check.violation.ViolationDocument;
import de.feelix.sierra.manager.packet.IngoingProcessor;
import de.feelix.sierra.manager.packet.OutgoingProcessor;
import de.feelix.sierra.manager.storage.PlayerData;
import de.feelix.sierra.utilities.CastUtil;
import de.feelix.sierra.manager.init.impl.start.Ticker;
import de.feelix.sierraapi.check.SierraCheckData;
import de.feelix.sierraapi.check.CheckType;
import de.feelix.sierraapi.violation.PunishType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;

@SierraCheckData(checkType = CheckType.FREQUENCY)
public class FrequencyDetection extends SierraDetection implements IngoingProcessor, OutgoingProcessor {

    private int  lastBookEditTick     = 0;
    private int  lastDropItemTick     = 0;
    private int  lastCraftRequestTick = 0;
    private int  dropCount            = 0;

    private long lastFlyingTime = 0L;
    private long balance        = 0L;

    private static final long MAX_BAL       = 0;
    private static final long BAL_RESET     = -50;
    private static final long BAL_SUB_ON_TP = 50;

    private final HashMap<PacketTypeCommon, Integer> packetCounts = new HashMap<>();

    public FrequencyDetection(PlayerData playerData) {
        super(playerData);
    }

    @Override
    public void handle(PacketReceiveEvent event, PlayerData playerData) {

        YamlConfiguration config = Sierra.getPlugin().getSierraConfigEngine().config();

        if (!config.getBoolean("prevent-packet-frequency", true)) {
            return;
        }

        playerData.getTimingProcessor().getFrequencyTask().prepare();
        PacketTypeCommon packetType = event.getPacketType();

        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            long current = System.currentTimeMillis();

            packetCounts.merge(packetType, 1, Integer::sum);

            int limit       = retrieveLimitFromConfiguration(packetType, config);
            int packetCount = packetCounts.getOrDefault(packetType, 0);

            if (packetCount > limit) {
                String debugInfo = String.format(
                    "%s, %dL, %dPPS, %dms", packetType.getName(), limit, packetCount,
                    System.currentTimeMillis() - current
                );
                triggerViolation(event, debugInfo, PunishType.KICK);
                return;
            }
        }

        int transaction = playerData.getTransactionProcessor().lastTransactionSent.get() + 1;

        if (packetType.equals(PacketType.Play.Client.EDIT_BOOK)) {
            handleEditBook(event);
        } else if (packetType.equals(PacketType.Play.Client.PLUGIN_MESSAGE)) {
            handlePluginMessage(event, playerData);
        } else if (packetType.equals(PacketType.Play.Client.CRAFT_RECIPE_REQUEST)) {
            handleCraftRecipeRequest(event);
        } else if (packetType.equals(PacketType.Play.Client.PLAYER_DIGGING)) {
            handlePlayerDigging(event, playerData);
        } else if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            handleFlyingDelay(event, playerData);
        }

        if (playerData.getTransactionProcessor().getLastRunnableId() != transaction) {
            playerData.getTransactionProcessor().addRealTimeTask(transaction, packetCounts::clear);
        }

        playerData.getTimingProcessor().getFrequencyTask().end();
    }

    private void handleFlyingDelay(PacketReceiveEvent event, PlayerData data) {
        if (!Sierra.getPlugin().getSierraConfigEngine().config().getBoolean("prevent-timer-cheats", true)) {
            return;
        }

        boolean noExempt = System.currentTimeMillis() - data.getJoinTime() > 1000;

        if (lastFlyingTime != 0L && noExempt) {
            long now = System.currentTimeMillis();
            balance += 50L;
            balance -= now - lastFlyingTime;
            if (balance > MAX_BAL) {
                triggerViolation(event, "Movement frequency: bal:~" + balance,
                                 violations() > 100 ? PunishType.KICK : PunishType.MITIGATE
                );
                balance = BAL_RESET;
            }
        }
        lastFlyingTime = System.currentTimeMillis();
    }

    private int retrieveLimitFromConfiguration(PacketTypeCommon packetType, YamlConfiguration config) {
        int limit = config.getInt("generic-packet-frequency-default", 30);
        for (String string : config.getStringList("generic-packet-frequency-limit")) {
            String[] parts = string.split(":");
            if (parts[0].equals(packetType.getName())) {
                limit = Integer.parseInt(parts[1]);
                break;
            }
        }
        return limit;
    }

    private void handleEditBook(PacketReceiveEvent event) {
        if (isSpamming(lastBookEditTick)) {
            triggerViolation(event, "Spammed edit book", PunishType.KICK);
        }
    }

    private void handlePluginMessage(PacketReceiveEvent event, PlayerData playerData) {
        WrapperPlayClientPluginMessage wrapper = CastUtil.getSupplier(
            () -> new WrapperPlayClientPluginMessage(event), playerData::exceptionDisconnect);

        String channelName = wrapper.getChannelName();
        if (channelName.contains("MC|BEdit") || channelName.contains("MC|BSign")) {
            if (isSpamming(lastBookEditTick)) {
                triggerViolation(event, "Spammed payload", PunishType.KICK);
            }
        }
    }

    private void handleCraftRecipeRequest(PacketReceiveEvent event) {
        int currentTick = Ticker.getInstance().getCurrentTick();
        if (lastCraftRequestTick + 10 > currentTick) {
            triggerViolation(event, "Spammed recipe request", PunishType.MITIGATE);
            //noinspection UnstableApiUsage
            ((Player) getPlayerData().getPlayer()).updateInventory();
        } else {
            lastCraftRequestTick = currentTick;
        }
    }

    private void handlePlayerDigging(PacketReceiveEvent event, PlayerData playerData) {
        WrapperPlayClientPlayerDigging wrapper = CastUtil.getSupplier(
            () -> new WrapperPlayClientPlayerDigging(event), playerData::exceptionDisconnect);

        if (wrapper.getAction() == DiggingAction.DROP_ITEM) {
            int currentTick = Ticker.getInstance().getCurrentTick();

            if (playerData.getGameMode() != GameMode.SPECTATOR) {
                if (lastDropItemTick != currentTick) {
                    dropCount = 0;
                    lastDropItemTick = currentTick;
                } else {
                    dropCount++;
                    if (dropCount >= 20) {
                        triggerViolation(event, "Spammed digging", PunishType.KICK);
                    }
                }
            }
        }
    }

    private boolean isSpamming(int lastActionTick) {
        int     currentTick = Ticker.getInstance().getCurrentTick();
        boolean isSpamming  = lastActionTick + 20 > currentTick;
        if (!isSpamming) {
            lastBookEditTick = currentTick;
        }
        return isSpamming;
    }

    private void triggerViolation(PacketReceiveEvent event, String debugInformation, PunishType punishType) {
        violation(event, ViolationDocument.builder()
            .debugInformation(debugInformation)
            .punishType(punishType)
            .build());
    }

    @Override
    public void handle(PacketSendEvent event, PlayerData playerData) {
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK
            || event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            balance -= BAL_SUB_ON_TP;
        }
    }
}
