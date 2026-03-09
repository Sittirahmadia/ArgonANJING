package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.KeybindSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.FakeInvScreen;
import dev.lvstrng.argon.utils.InventoryUtils;
import dev.lvstrng.argon.utils.MathUtils;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

public final class AutoInventoryTotem extends Module implements TickListener {

    public enum Mode { Blatant, Random }
    public enum TriggerMode { Always, OnKey, OnLowHP }

    // ── Mode ───────────────────────────────────────────────────────────────
    private final ModeSetting<Mode> pickMode = new ModeSetting<>(
            EncryptedString.of("Pick Mode"), Mode.Blatant, Mode.class)
            .setDescription(EncryptedString.of("Blatant = first totem found, Random = random slot"));

    private final ModeSetting<TriggerMode> triggerMode = new ModeSetting<>(
            EncryptedString.of("Trigger"), TriggerMode.Always, TriggerMode.class)
            .setDescription(EncryptedString.of("Always = every tick, OnKey = hold key, OnLowHP = only when health is low"));

    // ── Slots ──────────────────────────────────────────────────────────────
    private final BooleanSetting offhand = new BooleanSetting(
            EncryptedString.of("Offhand"), true)
            .setDescription(EncryptedString.of("Keep a totem in your offhand slot"));

    private final BooleanSetting hotbar = new BooleanSetting(
            EncryptedString.of("Hotbar"), true)
            .setDescription(EncryptedString.of("Keep a totem in a hotbar slot"));

    private final NumberSetting totemSlot = new NumberSetting(
            EncryptedString.of("Totem Slot"), 1, 9, 1, 1)
            .setDescription(EncryptedString.of("Hotbar slot (1-9) to keep the totem in"));

    private final BooleanSetting forceTotem = new BooleanSetting(
            EncryptedString.of("Force Totem"), false)
            .setDescription(EncryptedString.of("Replace the slot even if something is already there"));

    // ── Auto Switch ────────────────────────────────────────────────────────
    private final BooleanSetting autoSwitch = new BooleanSetting(
            EncryptedString.of("Auto Switch"), false)
            .setDescription(EncryptedString.of("Switch hotbar selection to Totem Slot when inventory is open"));

    // ── Auto Open (silent fake inventory) ─────────────────────────────────
    private final BooleanSetting autoOpen = new BooleanSetting(
            EncryptedString.of("Auto Open"), false)
            .setDescription(EncryptedString.of("Opens a hidden fake inventory to totem without pressing E"));

    private final NumberSetting stayOpenFor = new NumberSetting(
            EncryptedString.of("Stay Open Ticks"), 0, 20, 0, 1)
            .setDescription(EncryptedString.of("Extra ticks to keep the fake inventory open after filling"));

    // ── Timings ────────────────────────────────────────────────────────────
    private final MinMaxSetting delay = new MinMaxSetting(
            EncryptedString.of("Delay"), 0, 20, 0, 0, 1)
            .setDescription(EncryptedString.of("Random tick delay before acting after inventory opens (min-max)"));

    // ── Low HP trigger ─────────────────────────────────────────────────────
    private final NumberSetting hpThreshold = new NumberSetting(
            EncryptedString.of("HP Threshold"), 1, 20, 8, 1)
            .setDescription(EncryptedString.of("Health (hearts) below which OnLowHP mode activates"));

    // ── Key gate ───────────────────────────────────────────────────────────
    private final KeybindSetting activateKey = new KeybindSetting(
            EncryptedString.of("Activate Key"), GLFW.GLFW_KEY_C, false)
            .setDescription(EncryptedString.of("Hold to trigger (only used when Trigger = OnKey)"));

    // ── Re-equip protection ────────────────────────────────────────────────
    private final BooleanSetting reEquip = new BooleanSetting(
            EncryptedString.of("Re-Equip"), true)
            .setDescription(EncryptedString.of("Re-fills slots automatically after using a totem"));

    // ── State ──────────────────────────────────────────────────────────────
    private int invClock   = -1;
    private int closeClock = -1;
    // Track last known totem count to detect totem usage
    private int lastTotemCount = 0;

    public AutoInventoryTotem() {
        super(EncryptedString.of("Auto Inventory Totem"),
                EncryptedString.of("Automatically keeps totems in offhand and hotbar"),
                -1,
                Category.COMBAT);
        addSettings(pickMode, triggerMode, offhand, hotbar, totemSlot, forceTotem,
                autoSwitch, autoOpen, stayOpenFor, delay, hpThreshold, activateKey, reEquip);
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        resetClocks();
        lastTotemCount = 0;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        if (mc.currentScreen instanceof FakeInvScreen)
            mc.currentScreen.close();
        resetClocks();
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.getNetworkHandler() == null) return;

        // ── Re-equip detection ─────────────────────────────────────────────
        if (reEquip.getValue()) {
            int currentCount = InventoryUtils.countItemExceptHotbar(i -> i == Items.TOTEM_OF_UNDYING);
            boolean totemUsed = currentCount < lastTotemCount;
            lastTotemCount = currentCount;
            // If a totem was just used and auto-open is off, we still want to act
            if (totemUsed && !autoOpen.getValue()
                    && mc.currentScreen instanceof InventoryScreen) {
                invClock = 0; // act immediately on next tick
            }
        }

        // ── Trigger gate ───────────────────────────────────────────────────
        if (!isTriggerActive()) {
            resetClocks();
            return;
        }

        // ── Auto-open fake inventory ───────────────────────────────────────
        if (autoOpen.getValue() && shouldOpenScreen()
                && !(mc.currentScreen instanceof InventoryScreen)) {
            mc.setScreen(new FakeInvScreen(mc.player));
        }

        if (!(mc.currentScreen instanceof InventoryScreen)) {
            resetClocks();
            return;
        }

        // ── Init clocks on first tick in inventory ─────────────────────────
        if (invClock == -1) {
            // Random delay in [min, max]
            int min = (int) delay.getMinValue();
            int max = (int) delay.getMaxValue();
            invClock = min >= max ? min : MathUtils.randomInt(min, max);
        }
        if (closeClock == -1)
            closeClock = stayOpenFor.getValueInt();

        // ── Auto switch selection ──────────────────────────────────────────
        if (autoSwitch.getValue())
            mc.player.getInventory().selectedSlot = totemSlot.getValueInt() - 1;

        // ── Wait out the open delay ────────────────────────────────────────
        if (invClock > 0) { invClock--; return; }

        int syncId = ((InventoryScreen) mc.currentScreen).getScreenHandler().syncId;
        PlayerInventory inv = mc.player.getInventory();
        boolean didAction = false;

        // ── Fill offhand ───────────────────────────────────────────────────
        if (offhand.getValue()
                && inv.offHand.get(0).getItem() != Items.TOTEM_OF_UNDYING) {
            int slot = pickTotem();
            if (slot != -1) {
                mc.interactionManager.clickSlot(syncId, slot, 40, SlotActionType.SWAP, mc.player);
                didAction = true;
            }
        }

        // ── Fill hotbar slot ───────────────────────────────────────────────
        if (!didAction && hotbar.getValue()) {
            int targetSlot = totemSlot.getValueInt() - 1;
            ItemStack stackInSlot = inv.main.get(targetSlot);
            boolean needsTotem = stackInSlot.isEmpty()
                    || (forceTotem.getValue() && stackInSlot.getItem() != Items.TOTEM_OF_UNDYING);
            if (needsTotem) {
                int slot = pickTotem();
                if (slot != -1) {
                    mc.interactionManager.clickSlot(syncId, slot, targetSlot, SlotActionType.SWAP, mc.player);
                    didAction = true;
                }
            }
        }

        // ── Auto-close fake screen when done ──────────────────────────────
        if (!didAction && autoOpen.getValue() && mc.currentScreen instanceof FakeInvScreen) {
            if (shouldCloseScreen()) {
                if (closeClock > 0) closeClock--;
                else { mc.currentScreen.close(); resetClocks(); }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private boolean isTriggerActive() {
        return switch (triggerMode.getMode()) {
            case Always  -> true;
            case OnKey   -> GLFW.glfwGetKey(mc.getWindow().getHandle(), activateKey.getKey()) == GLFW.GLFW_PRESS;
            case OnLowHP -> mc.player != null && mc.player.getHealth() <= hpThreshold.getValueInt() * 2f;
        };
    }

    private int pickTotem() {
        return pickMode.isMode(Mode.Blatant)
                ? InventoryUtils.findTotemSlot()
                : InventoryUtils.findRandomTotemSlot();
    }

    private boolean shouldOpenScreen() {
        if (mc.player == null) return false;
        if (InventoryUtils.countItemExceptHotbar(i -> i == Items.TOTEM_OF_UNDYING) == 0)
            return false;
        boolean offhandMissing = offhand.getValue()
                && mc.player.getOffHandStack().getItem() != Items.TOTEM_OF_UNDYING;
        boolean hotbarMissing  = hotbar.getValue()
                && mc.player.getInventory().getStack(totemSlot.getValueInt() - 1).getItem() != Items.TOTEM_OF_UNDYING;
        return offhandMissing || hotbarMissing;
    }

    private boolean shouldCloseScreen() {
        if (mc.player == null) return true;
        boolean offhandOk = !offhand.getValue()
                || mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING;
        boolean hotbarOk  = !hotbar.getValue()
                || mc.player.getInventory().getStack(totemSlot.getValueInt() - 1).getItem() == Items.TOTEM_OF_UNDYING;
        return offhandOk && hotbarOk;
    }

    private void resetClocks() {
        invClock   = -1;
        closeClock = -1;
    }
}
