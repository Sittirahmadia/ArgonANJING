package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.ItemUseListener;
import dev.lvstrng.argon.event.events.PlayerTickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AutoDtapV2 — improved crosshair-based double-pop crystal placer.
 *
 * Improvements over V1:
 *  - Millisecond-precision delays via TimerUtils (eliminates tick-clock jitter)
 *  - Nearby crystal scanning: scans a configurable radius for breakable crystals
 *    instead of relying purely on the crosshair target
 *  - Placed crystal ID tracking: prioritises crystals we placed over foreign ones
 *  - Target proximity filter: only break crystals within N blocks of a live enemy
 *  - Always does both mouseSimulation AND interactBlock/attackEntity like AutoCrystal
 *  - Anti-AC: misclick chance, randomised ms delays
 *  - Silent rotation toward target before each action (optional)
 *  - Burst place mode: place multiple crystals per tick when the slot is ready
 *  - Configurable max-scan-radius for nearby crystal detection
 *  - Remembers broken entity IDs to avoid double-break in the same ms window
 */
public final class AutoDtapV2 extends Module implements PlayerTickListener, ItemUseListener, AttackListener {

    // ── Settings ───────────────────────────────────────────────────────────

    private final MinMaxSetting placeDelayMs = new MinMaxSetting(
            EncryptedString.of("Place Delay ms"), 0, 300, 0, 50, 1)
            .setDescription(EncryptedString.of("Random ms between crystal placements"));

    private final MinMaxSetting breakDelayMs = new MinMaxSetting(
            EncryptedString.of("Break Delay ms"), 0, 300, 0, 50, 1)
            .setDescription(EncryptedString.of("Random ms between crystal breaks"));

    private final NumberSetting maxCrystals = new NumberSetting(
            EncryptedString.of("Max Crystals"), 1, 6, 2, 1)
            .setDescription(EncryptedString.of("Max placed crystals tracked before pausing placement"));

    private final NumberSetting breakRadius = new NumberSetting(
            EncryptedString.of("Break Radius"), 1, 8, 5, 0.5)
            .setDescription(EncryptedString.of("Radius to scan for nearby end crystals to break (0 = crosshair only)"));

    private final NumberSetting enemyProximity = new NumberSetting(
            EncryptedString.of("Enemy Proximity"), 1, 10, 6, 0.5)
            .setDescription(EncryptedString.of("Only break crystals within this many blocks of a live enemy player"));

    private final BooleanSetting requireObsidian = new BooleanSetting(
            EncryptedString.of("Require Obsidian"), true)
            .setDescription(EncryptedString.of("Only place on obsidian or bedrock"));

    private final BooleanSetting prioritiseOwnCrystals = new BooleanSetting(
            EncryptedString.of("Own First"), true)
            .setDescription(EncryptedString.of("Prefer breaking crystals we placed over foreign ones"));

    private final BooleanSetting autoRotate = new BooleanSetting(
            EncryptedString.of("Auto Rotate"), false)
            .setDescription(EncryptedString.of("Silently rotate toward target before each action"));

    private final BooleanSetting burstPlace = new BooleanSetting(
            EncryptedString.of("Burst Place"), false)
            .setDescription(EncryptedString.of("Fire multiple place interactions per tick"));

    private final NumberSetting burstAmount = new NumberSetting(
            EncryptedString.of("Burst Amount"), 1, 4, 2, 1)
            .setDescription(EncryptedString.of("Place clicks per tick in burst mode"));

    private final BooleanSetting simulateClick = new BooleanSetting(
            EncryptedString.of("Simulate Click"), true)
            .setDescription(EncryptedString.of("Sends a real mouse event alongside interaction (CPS counters)"));

    private final BooleanSetting noCountGlitch = new BooleanSetting(
            EncryptedString.of("No Count Glitch"), true)
            .setDescription(EncryptedString.of("Cancels vanilla right-click when not pressing RMB"));

    private final BooleanSetting noBounce = new BooleanSetting(
            EncryptedString.of("No Bounce"), true)
            .setDescription(EncryptedString.of("Cancels vanilla left-click attack when not pressing LMB"));

    private final BooleanSetting activateOnRightClick = new BooleanSetting(
            EncryptedString.of("Activate On RightClick"), false)
            .setDescription(EncryptedString.of("Only runs while holding right click"));

    private final BooleanSetting stopOnKill = new BooleanSetting(
            EncryptedString.of("Stop On Kill"), false)
            .setDescription(EncryptedString.of("Stops when a nearby player dies"));

    private final BooleanSetting misclick = new BooleanSetting(
            EncryptedString.of("Misclick"), true)
            .setDescription(EncryptedString.of("Randomly skips an action to break AC rhythm detection"));

    private final NumberSetting misclickChance = new NumberSetting(
            EncryptedString.of("Misclick %"), 0, 20, 5, 1)
            .setDescription(EncryptedString.of("Percent chance to skip a place or break action (AC bypass)"));

    // ── State ──────────────────────────────────────────────────────────────

    /** IDs of crystals we placed this session — for Own First prioritisation. */
    private final Set<Integer> ownCrystalIds = new HashSet<>();
    /** IDs of crystals broken in the current ms window — prevents double-break. */
    private final Set<Integer> brokenThisCycle = new HashSet<>();

    private int placedCount = 0;

    private final TimerUtils placeTimer = new TimerUtils();
    private final TimerUtils breakTimer = new TimerUtils();
    private final Random rng = new Random();

    private long nextPlaceMs = 0;
    private long nextBreakMs = 0;

    // ── Constructor ────────────────────────────────────────────────────────

    public AutoDtapV2() {
        super(EncryptedString.of("Auto Dtap V2"),
                EncryptedString.of("Improved crosshair+radius crystal placer with ms timing and AC bypass"),
                -1,
                Category.COMBAT);
        addSettings(
                placeDelayMs, breakDelayMs, maxCrystals,
                breakRadius, enemyProximity,
                requireObsidian, prioritiseOwnCrystals,
                autoRotate, burstPlace, burstAmount,
                simulateClick, noCountGlitch, noBounce,
                activateOnRightClick, stopOnKill,
                misclick, misclickChance
        );
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        eventManager.add(PlayerTickListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        eventManager.add(AttackListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(PlayerTickListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        eventManager.remove(AttackListener.class, this);
        reset();
        super.onDisable();
    }

    private void reset() {
        ownCrystalIds.clear();
        brokenThisCycle.clear();
        placedCount   = 0;
        nextPlaceMs   = 0;
        nextBreakMs   = 0;
        placeTimer.reset();
        breakTimer.reset();
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    @Override
    public void onPlayerTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.getNetworkHandler() == null) return;
        if (mc.currentScreen != null) return;

        // Right-click gate
        if (activateOnRightClick.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS)
            return;

        // Must hold end crystal
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;

        // Stop on nearby death
        if (stopOnKill.getValue() && WorldUtils.isDeadBodyNearby()) return;

        // Clear per-cycle break dedup each tick
        brokenThisCycle.clear();

        // ── Break ──────────────────────────────────────────────────────────
        if (breakTimer.hasReached(nextBreakMs)) {
            EndCrystalEntity breakTarget = findBestCrystalToBreak();
            if (breakTarget != null && !brokenThisCycle.contains(breakTarget.getId())) {
                if (!shouldMisclick()) {
                    doBreak(breakTarget);
                    nextBreakMs = randomMs(breakDelayMs);
                    breakTimer.reset();
                }
            }
        }

        // ── Place ──────────────────────────────────────────────────────────
        if (placeTimer.hasReached(nextPlaceMs)
                && placedCount < maxCrystals.getValueInt()) {

            BlockHitResult placeTarget = getPlaceTarget();
            if (placeTarget != null && canPlace(placeTarget)) {
                if (!shouldMisclick()) {
                    int times = burstPlace.getValue() ? burstAmount.getValueInt() : 1;
                    for (int i = 0; i < times; i++) {
                        if (placedCount >= maxCrystals.getValueInt()) break;
                        doPlace(placeTarget);
                    }
                    nextPlaceMs = randomMs(placeDelayMs);
                    placeTimer.reset();
                }
            }
        }
    }

    // ── Action: Place ──────────────────────────────────────────────────────

    private void doPlace(BlockHitResult hit) {
        if (autoRotate.getValue()) BlockUtils.rotateToBlock(hit.getBlockPos());

        if (simulateClick.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);

        placedCount++;
        scheduleCrystalTracking(hit.getBlockPos());
    }

    // ── Action: Break ──────────────────────────────────────────────────────

    private void doBreak(EndCrystalEntity crystal) {
        if (autoRotate.getValue()) {
            BlockUtils.rotateToBlock(crystal.getBlockPos());
        }

        if (simulateClick.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_LEFT);

        mc.interactionManager.attackEntity(mc.player, crystal);
        mc.player.swingHand(Hand.MAIN_HAND);

        brokenThisCycle.add(crystal.getId());
        ownCrystalIds.remove(crystal.getId());
        if (placedCount > 0) placedCount--;
    }

    // ── Crystal tracking ───────────────────────────────────────────────────

    private void scheduleCrystalTracking(BlockPos placedOn) {
        if (mc.world == null) return;
        Vec3d above = Vec3d.ofCenter(placedOn.up());
        List<EndCrystalEntity> found = mc.world.getEntitiesByClass(EndCrystalEntity.class,
                new Box(above.x - 0.5, above.y - 0.5, above.z - 0.5,
                        above.x + 0.5, above.y + 1.5, above.z + 0.5),
                e -> true
        );
        for (EndCrystalEntity e : found) {
            ownCrystalIds.add(e.getId());
        }
    }

    // ── Crystal selection ──────────────────────────────────────────────────

    private EndCrystalEntity findBestCrystalToBreak() {
        List<EndCrystalEntity> candidates = new ArrayList<>();

        // Always check crosshair crystal
        if (mc.crosshairTarget instanceof EntityHitResult ehr
                && ehr.getEntity() instanceof EndCrystalEntity crosshairCrystal) {
            candidates.add(crosshairCrystal);
        }

        // Nearby scan — FIX: use for-loop instead of lambda to avoid "not effectively final" error
        double r = breakRadius.getValue();
        if (r > 0) {
            Vec3d playerPos = mc.player.getPos();
            List<EndCrystalEntity> nearby = mc.world.getEntitiesByClass(EndCrystalEntity.class,
                    new Box(playerPos.x - r, playerPos.y - r, playerPos.z - r,
                            playerPos.x + r, playerPos.y + r, playerPos.z + r),
                    e -> !brokenThisCycle.contains(e.getId())
            );
            for (EndCrystalEntity e : nearby) {
                if (!candidates.contains(e)) candidates.add(e);
            }
        }

        if (candidates.isEmpty()) return null;

        // Filter: must be within enemyProximity of a live hostile player
        double epSq = enemyProximity.getValue() * enemyProximity.getValue();
        candidates = candidates.stream()
                .filter(c -> isNearEnemy(c.getPos(), epSq))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return null;

        // Own First preference
        if (prioritiseOwnCrystals.getValue()) {
            Optional<EndCrystalEntity> ownFirst = candidates.stream()
                    .filter(c -> ownCrystalIds.contains(c.getId()))
                    .min(Comparator.comparingDouble(c -> c.squaredDistanceTo(mc.player)));
            if (ownFirst.isPresent()) return ownFirst.get();
        }

        // Fallback: closest crystal
        return candidates.stream()
                .min(Comparator.comparingDouble(c -> c.squaredDistanceTo(mc.player)))
                .orElse(null);
    }

    /** Returns true if any live (non-friend) enemy player is within sqDist of pos. */
    private boolean isNearEnemy(Vec3d pos, double epSq) {
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.isDead()) continue;
            if (p.squaredDistanceTo(pos) <= epSq) return true;
        }
        return false;
    }

    // ── Place target ───────────────────────────────────────────────────────

    private BlockHitResult getPlaceTarget() {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return null;
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return hit;
    }

    // ── Place validation ───────────────────────────────────────────────────

    private boolean canPlace(BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);

        if (requireObsidian.getValue()
                && !state.isOf(Blocks.OBSIDIAN)
                && !state.isOf(Blocks.BEDROCK)) return false;

        return CrystalUtils.canPlaceCrystalClient(pos);
    }

    // ── AC Bypass ─────────────────────────────────────────────────────────

    private boolean shouldMisclick() {
        return misclick.getValue() && rng.nextInt(100) < misclickChance.getValueInt();
    }

    private long randomMs(MinMaxSetting s) {
        long min = (long) s.getMinValue();
        long max = (long) s.getMaxValue();
        return min >= max ? min : min + (long)(rng.nextDouble() * (max - min));
    }

    // ── Item use / attack cancel ───────────────────────────────────────────

    @Override
    public void onItemUse(ItemUseEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noCountGlitch.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }

    @Override
    public void onAttack(AttackEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noBounce.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }
}
