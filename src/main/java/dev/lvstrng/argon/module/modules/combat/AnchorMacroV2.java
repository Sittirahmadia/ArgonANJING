package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.ItemUseListener;
import dev.lvstrng.argon.event.events.PlayerTickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * AnchorMacroV2 — smart anchor combat module.
 *
 * Improvements over AnchorMacro (V1):
 *  - Millisecond-precision delays via TimerUtils (no tick-clock jitter)
 *  - Nearby anchor scanning with configurable radius and priority modes
 *  - Smart charge count based on nearest enemy HP (optional)
 *  - Silent auto-rotate toward anchor before each interaction
 *  - Burst charge mode: multiple charge clicks per tick
 *  - Adaptive post-explode idle pause (breaks AC rhythm patterns)
 *  - Misclick bypass: random delay on a charge to look human
 *  - Three activation modes: Always / OnRMB / OnWeapon
 *
 * Bug fixes over the original AnchorMacroV2 draft:
 *  - FIXED: BlockHitResult.createMissed() replaced with a real HIT result so
 *    interactBlock() actually accepts and processes the interaction.
 *  - FIXED: findTotemSlot() now scans hotbar slots 0–8 (matching V1 logic).
 *    The previous draft called InventoryUtils.findTotemSlot() which only
 *    searches inventory slots 9–36, making setInvSlot() receive an invalid
 *    hotbar index and causing the slot-swap to always fail.
 *  - FIXED: rightClickAnchor() now always calls interactBlock() AND optionally
 *    mouseSimulation (matching AutoDtap's doPlace pattern), so the anchor
 *    interaction is actually sent to the server instead of being a no-op.
 *  - FIXED: after IDLE_PAUSE the macro now goes through FIND again to refresh
 *    the target anchor in case the block was moved or destroyed.
 */
public final class AnchorMacroV2 extends Module implements PlayerTickListener, ItemUseListener {

    // ── Enums ──────────────────────────────────────────────────────────────

    public enum AnchorPriority { Closest, MostCharged, NearEnemy }
    public enum ChargeMode     { Smart, Fixed }
    public enum ActivationMode { Always, OnRMB, OnWeapon }

    // ── Settings ───────────────────────────────────────────────────────────

    private final ModeSetting<ActivationMode> activation = new ModeSetting<>(
            EncryptedString.of("Activation"), ActivationMode.Always, ActivationMode.class)
            .setDescription(EncryptedString.of("Always / Only on RMB / Only while holding a weapon"));

    private final BooleanSetting charger = new BooleanSetting(
            EncryptedString.of("Charger"), true)
            .setDescription(EncryptedString.of("Auto-charges the anchor with glowstone"));

    private final BooleanSetting exploder = new BooleanSetting(
            EncryptedString.of("Exploder"), true)
            .setDescription(EncryptedString.of("Auto-explodes the anchor after charging"));

    private final ModeSetting<ChargeMode> chargeMode = new ModeSetting<>(
            EncryptedString.of("Charge Mode"), ChargeMode.Fixed, ChargeMode.class)
            .setDescription(EncryptedString.of("Fixed = use Charges setting | Smart = estimate from target HP"));

    private final NumberSetting chargesNeeded = new NumberSetting(
            EncryptedString.of("Charges"), 1, 4, 4, 1)
            .setDescription(EncryptedString.of("Fixed charges to fill (Fixed mode only)"));

    private final BooleanSetting nearbySearch = new BooleanSetting(
            EncryptedString.of("Nearby Search"), true)
            .setDescription(EncryptedString.of("Scan nearby blocks for anchors, not just the crosshair"));

    private final NumberSetting searchRadius = new NumberSetting(
            EncryptedString.of("Search Radius"), 1, 6, 4, 0.5)
            .setDescription(EncryptedString.of("Radius to scan for anchors"));

    private final ModeSetting<AnchorPriority> priority = new ModeSetting<>(
            EncryptedString.of("Priority"), AnchorPriority.NearEnemy, AnchorPriority.class)
            .setDescription(EncryptedString.of("How to pick the best anchor when multiple are nearby"));

    private final BooleanSetting autoRotate = new BooleanSetting(
            EncryptedString.of("Auto Rotate"), true)
            .setDescription(EncryptedString.of("Silently rotates toward the anchor before interacting"));

    private final BooleanSetting burstCharge = new BooleanSetting(
            EncryptedString.of("Burst Charge"), false)
            .setDescription(EncryptedString.of("Fire multiple charge clicks per tick for maximum speed"));

    private final NumberSetting burstAmount = new NumberSetting(
            EncryptedString.of("Burst Amount"), 1, 4, 2, 1)
            .setDescription(EncryptedString.of("Clicks per tick in burst charge mode"));

    private final MinMaxSetting chargeDelayMs = new MinMaxSetting(
            EncryptedString.of("Charge Delay ms"), 0, 300, 0, 80, 1)
            .setDescription(EncryptedString.of("Random ms delay between charges"));

    private final MinMaxSetting swapDelayMs = new MinMaxSetting(
            EncryptedString.of("Swap Delay ms"), 0, 300, 0, 60, 1)
            .setDescription(EncryptedString.of("Random ms delay after slot swap"));

    private final MinMaxSetting explodeDelayMs = new MinMaxSetting(
            EncryptedString.of("Explode Delay ms"), 0, 300, 0, 80, 1)
            .setDescription(EncryptedString.of("Random ms pause before detonating"));

    private final MinMaxSetting idlePauseMs = new MinMaxSetting(
            EncryptedString.of("Idle Pause ms"), 0, 500, 40, 120, 1)
            .setDescription(EncryptedString.of("Random post-explode pause before recharging (AC bypass)"));

    private final BooleanSetting misclick = new BooleanSetting(
            EncryptedString.of("Misclick"), true)
            .setDescription(EncryptedString.of("Randomly delays a charge to break rhythm patterns"));

    private final NumberSetting misclickChance = new NumberSetting(
            EncryptedString.of("Misclick Chance %"), 0, 25, 6, 1)
            .setDescription(EncryptedString.of("Chance per charge to insert an extra random delay"));

    private final BooleanSetting safeExplode = new BooleanSetting(
            EncryptedString.of("Safe Explode"), true)
            .setDescription(EncryptedString.of("Sneaks before exploding to reduce self-damage"));

    private final BooleanSetting autoRecharge = new BooleanSetting(
            EncryptedString.of("Auto Recharge"), true)
            .setDescription(EncryptedString.of("Loops — recharge and explode again immediately"));

    private final BooleanSetting autoDisable = new BooleanSetting(
            EncryptedString.of("Auto Disable"), true)
            .setDescription(EncryptedString.of("Disables when out of glowstone or no anchor found"));

    private final BooleanSetting restoreSlot = new BooleanSetting(
            EncryptedString.of("Restore Slot"), true)
            .setDescription(EncryptedString.of("Returns to original hotbar slot when done"));

    private final BooleanSetting simulateClick = new BooleanSetting(
            EncryptedString.of("Simulate Click"), true)
            .setDescription(EncryptedString.of("Uses mouse simulation so CPS counters register the click"));

    // ── State ──────────────────────────────────────────────────────────────

    private enum State { IDLE, FIND, SWITCH_GLOWSTONE, CHARGE, SWITCH_TOTEM, EXPLODE, IDLE_PAUSE }

    private State    state        = State.IDLE;
    private BlockPos targetAnchor = null;
    private int      prevSlot     = -1;
    private boolean  wasSneaking  = false;

    private final TimerUtils timer = new TimerUtils();
    private long waitMs = 0;

    private final Random rng = new Random();

    // ── Constructor ────────────────────────────────────────────────────────

    public AnchorMacroV2() {
        super(
                EncryptedString.of("Anchor Macro V2"),
                EncryptedString.of("Smart anchor combat — nearby scan, health-based charges, ms-precision AC bypass"),
                -1,
                Category.COMBAT
        );
        addSettings(
                activation,
                charger, exploder,
                chargeMode, chargesNeeded,
                nearbySearch, searchRadius, priority,
                autoRotate, burstCharge, burstAmount,
                chargeDelayMs, swapDelayMs, explodeDelayMs, idlePauseMs,
                misclick, misclickChance,
                safeExplode, autoRecharge, autoDisable,
                restoreSlot, simulateClick
        );
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        eventManager.add(PlayerTickListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(PlayerTickListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        cleanup();
        super.onDisable();
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    @Override
    public void onPlayerTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (mc.getNetworkHandler() == null) return;

        // Activation gate
        if (!passesActivationGate()) {
            if (state != State.IDLE) cleanup();
            state = State.IDLE;
            return;
        }

        // Timer gate — wait until the current delay has elapsed
        if (waitMs > 0 && !timer.hasReached(waitMs)) return;
        waitMs = 0;

        switch (state) {

            // ── IDLE: save slot, move to FIND ─────────────────────────────
            case IDLE -> {
                if (prevSlot == -1)
                    prevSlot = mc.player.getInventory().selectedSlot;
                state = State.FIND;
            }

            // ── FIND: locate the best anchor ──────────────────────────────
            case FIND -> {
                targetAnchor = findBestAnchor();

                if (targetAnchor == null) {
                    if (autoDisable.getValue()) setEnabledStatus(false);
                    state = State.IDLE;
                    return;
                }

                int charges = getCharges(targetAnchor);
                int needed  = neededCharges(targetAnchor);

                if (charger.getValue() && charges < needed) {
                    if (findGlowstoneSlot() == -1) {
                        if (autoDisable.getValue()) setEnabledStatus(false);
                        state = State.IDLE;
                        return;
                    }
                    state  = State.SWITCH_GLOWSTONE;
                    waitMs = randomMs(swapDelayMs);
                    timer.reset();
                } else if (exploder.getValue() && charges > 0) {
                    state  = State.SWITCH_TOTEM;
                    waitMs = randomMs(swapDelayMs);
                    timer.reset();
                }
                // else: nothing to do (no charger or exploder enabled)
            }

            // ── SWITCH_GLOWSTONE ──────────────────────────────────────────
            case SWITCH_GLOWSTONE -> {
                int gs = findGlowstoneSlot();
                if (gs == -1) {
                    if (autoDisable.getValue()) setEnabledStatus(false);
                    state = State.IDLE;
                    return;
                }
                InventoryUtils.setInvSlot(gs);
                state  = State.CHARGE;
                waitMs = randomMs(chargeDelayMs);
                timer.reset();
            }

            // ── CHARGE ────────────────────────────────────────────────────
            case CHARGE -> {
                if (targetAnchor == null || !isAnchor(targetAnchor)) {
                    state = State.FIND;
                    return;
                }

                // Recover if glowstone slipped out of hand
                if (!mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
                    int gs = findGlowstoneSlot();
                    if (gs == -1) {
                        if (autoDisable.getValue()) setEnabledStatus(false);
                        state = State.IDLE;
                        return;
                    }
                    InventoryUtils.setInvSlot(gs);
                    waitMs = randomMs(swapDelayMs);
                    timer.reset();
                    return;
                }

                int charges = getCharges(targetAnchor);
                int needed  = neededCharges(targetAnchor);

                if (charges >= needed || charges >= 4) {
                    // Done charging
                    if (exploder.getValue()) {
                        state  = State.SWITCH_TOTEM;
                        waitMs = randomMs(swapDelayMs);
                        timer.reset();
                    } else {
                        cleanup();
                        state = State.IDLE;
                    }
                    return;
                }

                // Rotate toward anchor if enabled
                if (autoRotate.getValue()) BlockUtils.rotateToBlock(targetAnchor);

                // Misclick delay — random extra pause
                if (misclick.getValue() && rng.nextInt(100) < misclickChance.getValueInt()) {
                    waitMs = 50L + rng.nextInt(100);
                    timer.reset();
                    return;
                }

                // Burst: fire multiple charge clicks this tick
                int clicks = burstCharge.getValue() ? burstAmount.getValueInt() : 1;
                for (int i = 0; i < clicks; i++) {
                    rightClickAnchor(targetAnchor);
                    if (getCharges(targetAnchor) >= needed) break;
                }

                waitMs = randomMs(chargeDelayMs);
                timer.reset();
            }

            // ── SWITCH_TOTEM ──────────────────────────────────────────────
            case SWITCH_TOTEM -> {
                // FIX: scan HOTBAR (0–8) for totem, matching V1's findTotemSlot()
                int totemSlot = findHotbarTotemSlot();
                if (totemSlot == -1) {
                    if (autoDisable.getValue()) setEnabledStatus(false);
                    state = State.IDLE;
                    return;
                }
                InventoryUtils.setInvSlot(totemSlot);

                if (safeExplode.getValue()) {
                    mc.player.setSneaking(true);
                    wasSneaking = true;
                }

                state  = State.EXPLODE;
                waitMs = randomMs(explodeDelayMs);
                timer.reset();
            }

            // ── EXPLODE ───────────────────────────────────────────────────
            case EXPLODE -> {
                if (targetAnchor == null || !isAnchor(targetAnchor)) {
                    releaseSneakIfNeeded();
                    cleanup();
                    state = State.IDLE;
                    return;
                }

                // Totem sanity check
                if (!mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                    int totemSlot = findHotbarTotemSlot();
                    if (totemSlot == -1) {
                        if (autoDisable.getValue()) setEnabledStatus(false);
                        state = State.IDLE;
                        return;
                    }
                    InventoryUtils.setInvSlot(totemSlot);
                    waitMs = randomMs(swapDelayMs);
                    timer.reset();
                    return;
                }

                int charges = getCharges(targetAnchor);
                if (charges == 0) {
                    // Already detonated
                    releaseSneakIfNeeded();
                    afterExplode();
                    return;
                }

                if (autoRotate.getValue()) BlockUtils.rotateToBlock(targetAnchor);

                rightClickAnchor(targetAnchor);
                releaseSneakIfNeeded();
                afterExplode();
            }

            // ── IDLE_PAUSE: post-explode human rhythm pause ───────────────
            // FIX: go through FIND again to refresh targetAnchor, not straight to
            // SWITCH_GLOWSTONE — in case the anchor was destroyed and a new one placed.
            case IDLE_PAUSE -> {
                state  = State.FIND;
                waitMs = randomMs(swapDelayMs);
                timer.reset();
            }
        }
    }

    // ── Post-explode routing ───────────────────────────────────────────────

    private void afterExplode() {
        if (autoRecharge.getValue() && charger.getValue()) {
            long pause = randomMs(idlePauseMs);
            if (pause > 0) {
                state  = State.IDLE_PAUSE;
                waitMs = pause;
                timer.reset();
            } else {
                // Skip pause — go to FIND to re-validate anchor before charging
                state  = State.FIND;
                waitMs = randomMs(swapDelayMs);
                timer.reset();
            }
        } else {
            cleanup();
            state = State.IDLE;
            if (autoDisable.getValue()) setEnabledStatus(false);
        }
    }

    // ── Interaction ────────────────────────────────────────────────────────

    /**
     * Right-clicks the anchor block.
     *
     * FIX (critical): The previous draft used BlockHitResult.createMissed() which
     * produces a HitResult.Type.MISSED result. Minecraft's interactBlock() rejects
     * MISSED hit results and returns ActionResult.PASS without doing anything, so
     * the anchor was never actually charged or exploded.
     *
     * The correct approach (matching V1's rightClick and AutoDtap's doPlace):
     *  - Build a real BlockHitResult with new BlockHitResult(hitPos, side, blockPos, false)
     *    which produces HitResult.Type.BLOCK so interactBlock() processes it.
     *  - Always call interactBlock() for the actual network interaction.
     *  - Optionally also fire MouseSimulation for CPS counter registration.
     */
    private void rightClickAnchor(BlockPos pos) {
        // Aim at the top face of the anchor block
        Vec3d hitPos = Vec3d.ofCenter(pos).add(0, 0.5, 0);

        // FIX: use constructor (HitResult.Type.BLOCK), not createMissed (HitResult.Type.MISSED)
        BlockHitResult bhr = new BlockHitResult(hitPos, Direction.UP, pos, false);

        // Fire both simulate click AND interactBlock — matching AutoDtap's doPlace()
        if (simulateClick.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);
    }

    // ── Anchor search ──────────────────────────────────────────────────────

    private BlockPos findBestAnchor() {
        if (!nearbySearch.getValue()) return getCrosshairAnchor();

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos origin = mc.player.getBlockPos();
        int r = (int) Math.ceil(searchRadius.getValue());

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (isAnchor(p)) candidates.add(p);
                }
            }
        }

        if (candidates.isEmpty()) return null;

        return switch (priority.getMode()) {

            case Closest -> candidates.stream()
                    .min(Comparator.comparingDouble(p ->
                            mc.player.getPos().squaredDistanceTo(p.getX() + .5, p.getY() + .5, p.getZ() + .5)))
                    .orElse(null);

            case MostCharged -> candidates.stream()
                    .max(Comparator.comparingInt(this::getCharges))
                    .orElse(null);

            case NearEnemy -> {
                BlockPos best = null;
                double bestDist = Double.MAX_VALUE;
                for (BlockPos p : candidates) {
                    Vec3d center = Vec3d.ofCenter(p);
                    for (PlayerEntity player : mc.world.getPlayers()) {
                        if (player == mc.player) continue;
                        double d = player.squaredDistanceTo(center);
                        if (d < 36.0 && d < bestDist) {
                            bestDist = d;
                            best = p;
                        }
                    }
                }
                // Fall back to crosshair if no enemy is near any anchor
                yield best != null ? best : getCrosshairAnchor();
            }
        };
    }

    private BlockPos getCrosshairAnchor() {
        if (mc.crosshairTarget == null) return null;
        if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
        return isAnchor(pos) ? pos : null;
    }

    // ── Smart charge calculation ───────────────────────────────────────────

    /**
     * Smart mode: estimate charges needed to kill the nearest enemy near the anchor.
     * ~5 effective HP per overworld explosion (conservative armor estimate).
     * Falls back to the Fixed setting if no enemy is nearby.
     */
    private int neededCharges(BlockPos anchor) {
        if (chargeMode.isMode(ChargeMode.Fixed)) return chargesNeeded.getValueInt();
        if (mc.world == null) return chargesNeeded.getValueInt();

        Vec3d center = Vec3d.ofCenter(anchor);
        PlayerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            double d = p.squaredDistanceTo(center);
            if (d < 36.0 && d < best) { best = d; nearest = p; }
        }

        if (nearest == null) return chargesNeeded.getValueInt();

        float hp = nearest.getHealth() + nearest.getAbsorptionAmount();
        int charges = (int) Math.ceil(hp / 5.0f);
        return Math.max(1, Math.min(charges, 4));
    }

    // ── Activation ────────────────────────────────────────────────────────

    private boolean passesActivationGate() {
        return switch (activation.getMode()) {
            case Always   -> true;
            case OnRMB    -> GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            case OnWeapon -> {
                var item = mc.player.getMainHandStack().getItem();
                yield item instanceof SwordItem || item instanceof AxeItem || item instanceof MaceItem;
            }
        };
    }

    // ── Slot helpers ──────────────────────────────────────────────────────

    private int findGlowstoneSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).isOf(Items.GLOWSTONE)) return i;
        return -1;
    }

    /**
     * FIX (critical): Scans HOTBAR slots 0–8 for a Totem of Undying.
     *
     * The previous draft called InventoryUtils.findTotemSlot() which searches
     * inventory slots 9–36 (main storage). Passing those indices to
     * InventoryUtils.setInvSlot() (which sets the HOTBAR selected slot, 0–8)
     * always resulted in the wrong item being selected or an out-of-bounds slot,
     * causing the explode step to fire with whatever happened to be in the hotbar.
     *
     * This matches V1's correct inline loop over 0–8.
     */
    private int findHotbarTotemSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++)
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        return -1;
    }

    // ── Misc helpers ──────────────────────────────────────────────────────

    private boolean isAnchor(BlockPos pos) {
        return mc.world != null && BlockUtils.isBlock(pos, Blocks.RESPAWN_ANCHOR);
    }

    private int getCharges(BlockPos pos) {
        if (mc.world == null) return 0;
        return mc.world.getBlockState(pos).get(RespawnAnchorBlock.CHARGES);
    }

    private long randomMs(MinMaxSetting s) {
        long min = (long) s.getMinValue();
        long max = (long) s.getMaxValue();
        return min >= max ? min : min + (long)(rng.nextDouble() * (max - min));
    }

    private void releaseSneakIfNeeded() {
        if (wasSneaking && mc.player != null) {
            mc.player.setSneaking(false);
            wasSneaking = false;
        }
    }

    private void cleanup() {
        releaseSneakIfNeeded();
        if (restoreSlot.getValue() && prevSlot != -1 && mc.player != null)
            InventoryUtils.setInvSlot(prevSlot);
        prevSlot     = -1;
        targetAnchor = null;
        waitMs       = 0;
    }

    private void reset() {
        state        = State.IDLE;
        targetAnchor = null;
        prevSlot     = -1;
        wasSneaking  = false;
        waitMs       = 0;
        timer.reset();
    }

    // ── Cancel vanilla right-click on anchor ──────────────────────────────

    @Override
    public void onItemUse(ItemUseEvent event) {
        if (mc.player == null || mc.crosshairTarget == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        if (BlockUtils.isBlock(hit.getBlockPos(), Blocks.RESPAWN_ANCHOR))
            event.cancel();
    }
}
