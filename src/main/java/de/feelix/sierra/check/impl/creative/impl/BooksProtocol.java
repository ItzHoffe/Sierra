package de.feelix.sierra.check.impl.creative.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import de.feelix.sierra.Sierra;
import de.feelix.sierra.check.impl.creative.ItemCheck;
import de.feelix.sierra.manager.storage.PlayerData;
import de.feelix.sierra.utilities.Pair;
import de.feelix.sierraapi.violation.PunishType;

public class BooksProtocol implements ItemCheck {

    @Override
    public Pair<String, PunishType> handleCheck(PacketReceiveEvent event, ItemStack clickedStack,
                                                NBTCompound nbtCompound, PlayerData playerData) {

        if (!Sierra.getPlugin().getSierraConfigEngine().config().getBoolean("disable-books-completely", false)) {
            return null;
        }

        if (clickedStack.getType() == ItemTypes.WRITTEN_BOOK || clickedStack.getType() == ItemTypes.WRITABLE_BOOK) {
            return new Pair<>("Using book", PunishType.BAN);
        }
        return null;
    }
}
