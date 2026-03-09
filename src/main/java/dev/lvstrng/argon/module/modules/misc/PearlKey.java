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
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public final class PearlKey extends Module implements TickListener {

    private final KeybindSetting activateKey = new KeybindSetting(
            EncryptedString.of("Activate Key"), GLFW.GLFW_KEY_P, true);
    private final NumberSetting throwDelay = new NumberSetting(
            EncryptedString.of("Throw Delay ms"), 100, 5000, 1000, 50)
            .setDescription(EncryptedString.of("Minimum time between pearl throws"));
    private final NumberSetting switchDelay = new NumberSetting(
            EncryptedString.of("Switch Delay ms"), 0, 500, 50, 10)
            .setDescription(EncryptedString.of("Time before switching back to original slot"));

    private final TimerUtils throwTimer  = new TimerUtils();
    private final TimerUtils switchTimer = new TimerUtils();

    private boolean keyPressed      = false;
    private boolean needsSwitchBack = false;
    private int     originalSlot    = -1;

    public PearlKey() {
        super(EncryptedString.of("Pearl Key"),
                EncryptedString.of("Throws an ender pearl on key press with cooldown support"),
                -1, Category.MISC);
        addSettings(activateKey, throwDelay, switchDelay);
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
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.currentScreen != null) return;

        boolean keyDown = KeyUtils.isKeyPressed(activateKey.getKey());

        if (keyDown && !keyPressed && throwTimer.hasReached(throwDelay.getValue())) {
            throwPearl();
            throwTimer.reset();
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
        if (mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL)) return;

        originalSlot = mc.player.getInventory().selectedSlot;
        InventoryUtils.setInvSlot(slot);

        ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);

        needsSwitchBack = true;
        switchTimer.reset();
    }

    private int findSlot(Item item) {
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        return -1;
    }

    private void reset() {
        keyPressed      = false;
        needsSwitchBack = false;
        originalSlot    = -1;
        throwTimer.reset();
        switchTimer.reset();
    }
}
