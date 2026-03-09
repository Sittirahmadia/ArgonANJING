package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.ItemUseListener;
import dev.lvstrng.argon.event.events.PlayerTickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * AnchorMacro — fast totem-only anchor combat module.
 *
 * Flow:
 *   IDLE → SWITCH_GLOWSTONE → CHARGE (x N) → SWITCH_TOTEM → EXPLODE → loop
 *
 * Key design:
 *  - Explode slot is ALWAYS a totem slot — scans hotbar for totem of undying
 *  - All delays default to 0 for maximum speed (configurable)
 *  - Charges all 4 levels in one burst before switching to totem
 *  - Slot swap + right-click happen same tick when delays are 0
 */
public final class AnchorMacro extends Module implements PlayerTickListener, ItemUseListener {

    // ── Settings ───────────────────────────────────────────────────────────

    private final BooleanSetting charger = new BooleanSetting(
            EncryptedString.of("Charger"), true)
            .setDescription(EncryptedString.of("Auto-charges the anchor with glowstone"));

    private final BooleanSetting exploder = new BooleanSetting(
            EncryptedString.of("Exploder"), true)
            .setDescription(EncryptedString.of("Auto-explodes the anchor after charging"));

    private final NumberSetting chargesNeeded = new NumberSetting(
            EncryptedString.of("Charges"), 1, 4, 4, 1)
            .setDescription(EncryptedString.of("Charges to fill before exploding (1-4)"));

    private final MinMaxSetting swapDelay = new MinMaxSetting(
            EncryptedString.of("Swap Delay"), 0, 10, 0, 0, 1)
            .setDescription(EncryptedString.of("Random ticks between slot swaps (0 = instant)"));

    private final MinMaxSetting explodeDelay = new MinMaxSetting(
            EncryptedString.of("Explode Delay"), 0, 10, 0, 0, 1)
            .setDescription(EncryptedString.of("Random ticks before detonating (0 = instant)"));

    private final BooleanSetting safeExplode = new BooleanSetting(
            EncryptedString.of("Safe Explode"), true)
            .setDescription(EncryptedString.of("Sneaks before exploding to reduce self-damage"));

    private final BooleanSetting autoRecharge = new BooleanSetting(
            EncryptedString.of("Auto Recharge"), true)
            .setDescription(EncryptedString.of("Loops — recharges and explodes again instantly"));

    private final BooleanSetting autoDisable = new BooleanSetting(
            EncryptedString.of("Auto Disable"), true)
            .setDescription(EncryptedString.of("Disables when out of glowstone or no anchor targeted"));

    private final BooleanSetting requireRightClick = new BooleanSetting(
            EncryptedString.of("Require Right Click"), false)
            .setDescription(EncryptedString.of("Only runs while holding right click"));

    private final BooleanSetting restoreSlot = new BooleanSetting(
            EncryptedString.of("Restore Slot"), true)
            .setDescription(EncryptedString.of("Returns to original slot when macro finishes"));

    private final BooleanSetting simulateClick = new BooleanSetting(
            EncryptedString.of("Simulate Click"), true)
            .setDescription(EncryptedString.of("Uses mouse simulation for interaction"));

    // ── State ──────────────────────────────────────────────────────────────

    private enum State {
        IDLE,
        SWITCH_TO_GLOWSTONE,
        CHARGE,
        SWITCH_TO_TOTEM,
        EXPLODE
    }

    private State   state       = State.IDLE;
    private int     clock       = 0;
    private int     prevSlot    = -1;
    private boolean wasSneaking = false;

    // ── Constructor ────────────────────────────────────────────────────────

    public AnchorMacro() {
        super(EncryptedString.of("Anchor Macro"),
                EncryptedString.of("Fast anchor combat — charges and explodes with totem only"),
                -1,
                Category.COMBAT);
        addSettings(charger, exploder, chargesNeeded,
                swapDelay, explodeDelay,
                safeExplode, autoRecharge, autoDisable,
                requireRightClick, restoreSlot, simulateClick);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        eventManager.add(PlayerTickListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        state       = State.IDLE;
        clock       = 0;
        prevSlot    = -1;
        wasSneaking = false;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(PlayerTickListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        cleanup();
        super.onDisable();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void cleanup() {
        if (wasSneaking && mc.player != null) {
            mc.player.setSneaking(false);
            wasSneaking = false;
        }
        if (restoreSlot.getValue() && prevSlot != -1 && mc.player != null) {
            InventoryUtils.setInvSlot(prevSlot);
            prevSlot = -1;
        }
    }

    private int randomDelay(MinMaxSetting s) {
        int min = (int) s.getMinValue();
        int max = (int) s.getMaxValue();
        return min >= max ? min : MathUtils.randomInt(min, max);
    }

    /** Scans hotbar for glowstone. Returns slot or -1. */
    private int findGlowstoneSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.GLOWSTONE))
                return i;
        }
        return -1;
    }

    /**
     * Scans hotbar for a Totem of Undying.
     * This is the ONLY valid explode slot — we never use random items.
     * Returns slot index or -1 if no totem in hotbar.
     */
    private int findTotemSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING))
                return i;
        }
        return -1;
    }

    /** Returns the targeted anchor BlockPos or null. */
    private BlockPos getTargetedAnchor() {
        if (mc.player == null || mc.world == null) return null;
        if (mc.crosshairTarget == null) return null;
        if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return null;
        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = hit.getBlockPos();
        return BlockUtils.isBlock(pos, Blocks.RESPAWN_ANCHOR) ? pos : null;
    }

    /** Returns current charge level of the anchor (0–4). */
    private int getCharges(BlockPos pos) {
        if (mc.world == null) return 0;
        return mc.world.getBlockState(pos).get(RespawnAnchorBlock.CHARGES);
    }

    /** Right-clicks the targeted block. */
    private void rightClick() {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        if (simulateClick.getValue()) {
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        } else {
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            if (result.isAccepted() && result.shouldSwingHand())
                mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    @Override
    public void onPlayerTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.getNetworkHandler() == null) return;
        if (mc.currentScreen != null) return;

        // Right-click gate
        if (requireRightClick.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            if (state != State.IDLE) cleanup();
            state = State.IDLE;
            return;
        }

        // Clock countdown — skip tick if waiting
        if (clock > 0) { clock--; return; }

        switch (state) {

            // ── IDLE: decide what to do ────────────────────────────────────
            case IDLE -> {
                BlockPos anchor = getTargetedAnchor();
                if (anchor == null) {
                    if (autoDisable.getValue()) setEnabledStatus(false);
                    return;
                }

                // Save slot once
                if (prevSlot == -1)
                    prevSlot = mc.player.getInventory().selectedSlot;

                int charges = getCharges(anchor);
                int needed  = chargesNeeded.getValueInt();

                if (charger.getValue() && charges < needed) {
                    // Need glowstone
                    if (findGlowstoneSlot() == -1) {
                        if (autoDisable.getValue()) setEnabledStatus(false);
                        return;
                    }
                    state = State.SWITCH_TO_GLOWSTONE;
                    clock = randomDelay(swapDelay);
                } else if (exploder.getValue() && charges > 0) {
                    // Already charged — go explode
                    state = State.SWITCH_TO_TOTEM;
                    clock = randomDelay(swapDelay);
                }
            }

            // ── SWITCH_TO_GLOWSTONE ────────────────────────────────────────
            case SWITCH_TO_GLOWSTONE -> {
                int gs = findGlowstoneSlot();
                if (gs == -1) {
                    if (autoDisable.getValue()) setEnabledStatus(false);
                    return;
                }
                InventoryUtils.setInvSlot(gs);
                state = State.CHARGE;
                clock = randomDelay(swapDelay);
            }

            // ── CHARGE: right-click with glowstone until anchor is full ────
            case CHARGE -> {
                BlockPos anchor = getTargetedAnchor();
                if (anchor == null) { state = State.IDLE; return; }

                // Recover if glowstone left hand somehow
                if (!mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
                    int gs = findGlowstoneSlot();
                    if (gs == -1) {
                        if (autoDisable.getValue()) setEnabledStatus(false);
                        return;
                    }
                    InventoryUtils.setInvSlot(gs);
                    clock = randomDelay(swapDelay);
                    return;
                }

                int charges = getCharges(anchor);
                int needed  = chargesNeeded.getValueInt();

                if (charges < needed && charges < 4) {
                    rightClick();
                    // Stay in CHARGE — next tick will re-evaluate charges
                    clock = randomDelay(swapDelay);
                } else {
                    // Done charging — switch to totem for explode
                    if (exploder.getValue()) {
                        state = State.SWITCH_TO_TOTEM;
                        clock = randomDelay(swapDelay);
                    } else {
                        cleanup();
                        state = State.IDLE;
                    }
                }
            }

            // ── SWITCH_TO_TOTEM: must have totem in hotbar to explode ──────
            case SWITCH_TO_TOTEM -> {
                int totemSlot = findTotemSlot();
                if (totemSlot == -1) {
                    // No totem in hotbar — cannot explode safely
                    if (autoDisable.getValue()) setEnabledStatus(false);
                    return;
                }
                InventoryUtils.setInvSlot(totemSlot);

                // Apply sneak for safe explode
                if (safeExplode.getValue()) {
                    mc.player.setSneaking(true);
                    wasSneaking = true;
                }

                state = State.EXPLODE;
                clock = randomDelay(explodeDelay);
            }

            // ── EXPLODE: right-click with totem in hand ────────────────────
            case EXPLODE -> {
                BlockPos anchor = getTargetedAnchor();
                if (anchor == null) { cleanup(); state = State.IDLE; return; }

                // Verify we actually have totem in hand before detonating
                if (!mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                    // Totem not in hand — try to find one
                    int totemSlot = findTotemSlot();
                    if (totemSlot == -1) {
                        if (autoDisable.getValue()) setEnabledStatus(false);
                        return;
                    }
                    InventoryUtils.setInvSlot(totemSlot);
                    clock = randomDelay(swapDelay);
                    return;
                }

                int charges = getCharges(anchor);
                if (charges == 0) {
                    // Anchor already exploded (by us or someone else)
                    if (wasSneaking) { mc.player.setSneaking(false); wasSneaking = false; }
                    if (autoRecharge.getValue() && charger.getValue()) {
                        state = State.SWITCH_TO_GLOWSTONE;
                        clock = randomDelay(swapDelay);
                    } else {
                        cleanup(); state = State.IDLE;
                        if (autoDisable.getValue()) setEnabledStatus(false);
                    }
                    return;
                }

                // Detonate
                rightClick();

                // Release sneak immediately after click
                if (wasSneaking) {
                    mc.player.setSneaking(false);
                    wasSneaking = false;
                }

                if (autoRecharge.getValue() && charger.getValue()) {
                    // Fast loop back to charge
                    state = State.SWITCH_TO_GLOWSTONE;
                    clock = randomDelay(swapDelay);
                } else {
                    cleanup();
                    state = State.IDLE;
                    if (autoDisable.getValue()) setEnabledStatus(false);
                }
            }
        }
    }

    // ── Cancel vanilla right-click on anchor ───────────────────────────────

    @Override
    public void onItemUse(ItemUseEvent event) {
        if (mc.player == null || mc.crosshairTarget == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        if (BlockUtils.isBlock(hit.getBlockPos(), Blocks.RESPAWN_ANCHOR))
            event.cancel();
    }
}
