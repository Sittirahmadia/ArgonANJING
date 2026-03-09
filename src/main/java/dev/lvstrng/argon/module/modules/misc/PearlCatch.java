package dev.lvstrng.argon.module.modules.misc;

import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.KeybindSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.InventoryUtils;
import dev.lvstrng.argon.utils.KeyUtils;
import dev.lvstrng.argon.utils.TimerUtils;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

public final class PearlCatch extends Module implements TickListener {

    private final KeybindSetting activateKey = new KeybindSetting(
            EncryptedString.of("Activate Key"), GLFW.GLFW_KEY_H, true);
    private final NumberSetting windDelay = new NumberSetting(
            EncryptedString.of("Wind Delay ms"), 0, 2000, 200, 1)
            .setDescription(EncryptedString.of("Delay between throwing pearl and wind charge"));
    private final NumberSetting switchDelay = new NumberSetting(
            EncryptedString.of("Switch Delay ms"), 0, 500, 50, 10)
            .setDescription(EncryptedString.of("Ticks before switching back to original slot"));

    private final TimerUtils pearlTimer  = new TimerUtils();
    private final TimerUtils switchTimer = new TimerUtils();

    private boolean keyPressed      = false;
    private boolean pearlThrown     = false;
    private boolean needsSwitchBack = false;
    private int     originalSlot    = -1;

    public PearlCatch() {
        super(EncryptedString.of("Pearl Catch"),
                EncryptedString.of("Throws ender pearl then wind charge on key press"),
                -1, Category.MISC);
        addSettings(activateKey, windDelay, switchDelay);
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        reset();
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.currentScreen != null) return;

        boolean keyDown = KeyUtils.isKeyPressed(activateKey.getKey());

        if (keyDown && !keyPressed) throwPearl();

        if (pearlThrown && pearlTimer.hasReached(windDelay.getValue())) {
            throwWindCharge();
            pearlThrown = false;
        }

        if (needsSwitchBack && switchTimer.hasReached(switchDelay.getValue())) {
            mc.player.getInventory().selectedSlot = originalSlot;
            needsSwitchBack = false;
            originalSlot    = -1;
        }

        keyPressed = keyDown;
    }

    private void throwPearl() {
        int slot = findSlot(Items.ENDER_PEARL);
        if (slot == -1) return;
        if (mc.player.getItemCooldownManager().isCoolingDown(new ItemStack(Items.ENDER_PEARL).getItem())) return;

        originalSlot = mc.player.getInventory().selectedSlot;
        InventoryUtils.setInvSlot(slot);
        useCurrentItem();

        needsSwitchBack = true;
        switchTimer.reset();
        pearlThrown = true;
        pearlTimer.reset();
    }

    private void throwWindCharge() {
        int slot = findSlot(Items.WIND_CHARGE);
        if (slot == -1) return;
        if (mc.player.getItemCooldownManager().isCoolingDown(new ItemStack(Items.WIND_CHARGE).getItem())) return;

        originalSlot = mc.player.getInventory().selectedSlot;
        InventoryUtils.setInvSlot(slot);
        useCurrentItem();

        needsSwitchBack = true;
        switchTimer.reset();
    }

    private void useCurrentItem() {
        ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);
    }

    private int findSlot(Item item) {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        return -1;
    }

    private void reset() {
        keyPressed      = false;
        pearlThrown     = false;
        needsSwitchBack = false;
        originalSlot    = -1;
        pearlTimer.reset();
        switchTimer.reset();
    }
}
