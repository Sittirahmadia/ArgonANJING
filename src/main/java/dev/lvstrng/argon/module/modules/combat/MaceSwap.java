package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.InventoryUtils;
import net.minecraft.item.AxeItem;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.hit.EntityHitResult;

public final class MaceSwap extends Module implements AttackListener, TickListener {

    private final BooleanSetting swapSilently = new BooleanSetting(
            EncryptedString.of("Swap Silently"),
            false)
            .setDescription(EncryptedString.of("Swaps to mace via packet without visual change"));

    private int lastSlot = -1;

    public MaceSwap() {
        super(EncryptedString.of("Mace Swap"),
                EncryptedString.of("Automatically swaps to mace when hitting an enemy"),
                -1,
                Category.COMBAT);
        addSettings(swapSilently);
    }

    @Override
    public void onEnable() {
        eventManager.add(AttackListener.class, this);
        eventManager.add(TickListener.class, this);
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(AttackListener.class, this);
        eventManager.remove(TickListener.class, this);
        lastSlot = -1;
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (mc.player == null || lastSlot == -1) return;
        swap(lastSlot);
        lastSlot = -1;
    }

    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        if (mc.player == null || mc.world == null) return;

        int slot = findMaceSlot();
        if (slot == -1 || lastSlot != -1) return;
        if (slot == mc.player.getInventory().selectedSlot) return;
        if (mc.player.getMainHandStack().getItem() instanceof AxeItem) return;
        if (!(mc.crosshairTarget instanceof EntityHitResult)) return;

        lastSlot = mc.player.getInventory().selectedSlot;
        swap(slot);
    }

    private void swap(int slot) {
        if (swapSilently.getValue()) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        } else {
            InventoryUtils.setInvSlot(slot);
        }
    }

    private int findMaceSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof MaceItem) {
                return i;
            }
        }
        return -1;
    }
}
