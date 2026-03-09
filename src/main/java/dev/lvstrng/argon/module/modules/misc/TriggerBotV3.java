package dev.lvstrng.argon.module.modules.misc;

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.PlayerTickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.modules.client.Friends;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.MouseSimulation;
import dev.lvstrng.argon.utils.TimerUtils;
import dev.lvstrng.argon.utils.WorldUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * TriggerBotV3 — Argon Client (Misc category)
 *
 * Improvements over original TriggerBotModule and V2:
 *  - Randomised click-hold duration (press→release) per attack — mimics real mouse behavior
 *  - Burst mode — fires 2-3 rapid clicks in a row with tiny gaps, like a real player spam-clicking
 *  - Adaptive CPS — dynamically adjusts attack speed based on target health (faster at low HP)
 *  - Post-attack random idle delay — avoids constant-rhythm pattern detection
 *  - Misclick simulation — occasionally skips an attack window to appear human
 *  - Focus target lock — locks to one entity until it's dead or leaves range
 *  - Range randomisation per attack cycle — avoids fixed-range fingerprinting
 *  - Full friend/shield/crit/weapon/health gate support
 */
public final class TriggerBotV3 extends Module implements PlayerTickListener, AttackListener {

    // ── Enums ─────────────────────────────────────────────────────────────────
    public enum AttackMode { Cooldown, Delay, Burst }
    public enum TargetFilter { Players, Mobs, Animals, All }
    public enum WeaponFilter { Any, Sword, Axe, SwordOrAxe, Mace, Trident }

    // ── Settings ──────────────────────────────────────────────────────────────

    private final ModeSetting<AttackMode> attackMode = new ModeSetting<>(
            EncryptedString.of("Attack Mode"), AttackMode.Cooldown, AttackMode.class)
            .setDescription(EncryptedString.of("Cooldown % / ms Delay / Burst (rapid multi-clicks)"));

    private final NumberSetting cooldownPct = new NumberSetting(
            EncryptedString.of("Cooldown %"), 50, 100, 93, 1)
            .setDescription(EncryptedString.of("Attack when swing cooldown reaches this % (Cooldown mode)"));

    private final MinMaxSetting attackDelay = new MinMaxSetting(
            EncryptedString.of("Attack Delay"), 0, 1000, 1, 60, 160)
            .setDescription(EncryptedString.of("Random ms delay between attacks (Delay mode)"));

    private final MinMaxSetting burstClicks = new MinMaxSetting(
            EncryptedString.of("Burst Clicks"), 1, 5, 1, 2, 3)
            .setDescription(EncryptedString.of("How many clicks to fire in a burst (Burst mode)"));

    private final MinMaxSetting burstDelay = new MinMaxSetting(
            EncryptedString.of("Burst Delay ms"), 0, 200, 1, 30, 70)
            .setDescription(EncryptedString.of("Random ms between burst clicks (Burst mode)"));

    private final MinMaxSetting clickHoldMs = new MinMaxSetting(
            EncryptedString.of("Click Hold ms"), 10, 120, 1, 30, 65)
            .setDescription(EncryptedString.of("How long the simulated click is held down (AC bypass)"));

    private final MinMaxSetting rangeVariance = new MinMaxSetting(
            EncryptedString.of("Range"), 10, 40, 1, 25, 32)
            .setDescription(EncryptedString.of("Random attack range (tenths of blocks, e.g. 30 = 3.0)"));

    private final ModeSetting<TargetFilter> targetFilter = new ModeSetting<>(
            EncryptedString.of("Target Filter"), TargetFilter.Players, TargetFilter.class)
            .setDescription(EncryptedString.of("Which entity types to attack"));

    private final ModeSetting<WeaponFilter> weaponFilter = new ModeSetting<>(
            EncryptedString.of("Weapon Filter"), WeaponFilter.Any, WeaponFilter.class)
            .setDescription(EncryptedString.of("Only trigger when holding this type of weapon"));

    private final BooleanSetting requireClick = new BooleanSetting(
            EncryptedString.of("Require Click"), false)
            .setDescription(EncryptedString.of("Only triggers while holding left mouse button"));

    private final BooleanSetting critOnly = new BooleanSetting(
            EncryptedString.of("Crit Only"), false)
            .setDescription(EncryptedString.of("Only attack when airborne (guaranteed crit)"));

    private final BooleanSetting adaptiveCPS = new BooleanSetting(
            EncryptedString.of("Adaptive CPS"), true)
            .setDescription(EncryptedString.of("Speeds up attacks when target HP is low (looks aggressive but human)"));

    private final BooleanSetting misclick = new BooleanSetting(
            EncryptedString.of("Misclick"), true)
            .setDescription(EncryptedString.of("Randomly skips some attack windows to appear human (AC bypass)"));

    private final NumberSetting misclickChance = new NumberSetting(
            EncryptedString.of("Misclick Chance %"), 0, 30, 5, 1)
            .setDescription(EncryptedString.of("Chance per window to skip (5 = 5%)"));

    private final BooleanSetting focusMode = new BooleanSetting(
            EncryptedString.of("Focus Mode"), false)
            .setDescription(EncryptedString.of("Lock onto a target until they die or leave range"));

    private final NumberSetting focusRange = new NumberSetting(
            EncryptedString.of("Focus Range"), 3, 10, 6, 0.5)
            .setDescription(EncryptedString.of("Range at which focus is broken"));

    private final BooleanSetting skipShielding = new BooleanSetting(
            EncryptedString.of("Skip Shielding"), true)
            .setDescription(EncryptedString.of("Don't attack players actively blocking with a shield"));

    private final BooleanSetting skipFriends = new BooleanSetting(
            EncryptedString.of("Skip Friends"), true)
            .setDescription(EncryptedString.of("Don't attack players on your friends list"));

    private final BooleanSetting skipInvisible = new BooleanSetting(
            EncryptedString.of("Skip Invisible"), false)
            .setDescription(EncryptedString.of("Don't attack invisible entities"));

    private final BooleanSetting healthGate = new BooleanSetting(
            EncryptedString.of("Health Gate"), false)
            .setDescription(EncryptedString.of("Only attack when target HP is below threshold"));

    private final NumberSetting healthThreshold = new NumberSetting(
            EncryptedString.of("HP Threshold"), 1, 40, 20, 1)
            .setDescription(EncryptedString.of("HP threshold for Health Gate"));

    private final BooleanSetting noBounce = new BooleanSetting(
            EncryptedString.of("No Bounce"), true)
            .setDescription(EncryptedString.of("Cancels vanilla LMB attack when not pressing mouse"));

    // ── State ─────────────────────────────────────────────────────────────────
    private final TimerUtils hitTimer   = new TimerUtils();
    private final TimerUtils idleTimer  = new TimerUtils();
    private final Random rng            = new Random();

    private LivingEntity focusedTarget  = null;
    private double currentRange         = -1;      // randomised each cycle
    private boolean attackedThisTick    = false;
    private boolean inIdlePause         = false;   // post-attack human pause
    private int burstRemaining          = 0;       // clicks left in burst

    // ── Constructor ───────────────────────────────────────────────────────────
    public TriggerBotV3() {
        super(
                EncryptedString.of("TriggerBot V3"),
                EncryptedString.of("Human-like crosshair attacker with burst, adaptive CPS & full AC bypass"),
                -1,
                Category.MISC
        );
        addSettings(
                attackMode, cooldownPct, attackDelay,
                burstClicks, burstDelay, clickHoldMs, rangeVariance,
                targetFilter, weaponFilter,
                requireClick, critOnly,
                adaptiveCPS, misclick, misclickChance,
                focusMode, focusRange,
                skipShielding, skipFriends, skipInvisible,
                healthGate, healthThreshold,
                noBounce
        );
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        eventManager.add(PlayerTickListener.class, this);
        eventManager.add(AttackListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(PlayerTickListener.class, this);
        eventManager.remove(AttackListener.class, this);
        reset();
        super.onDisable();
    }

    private void reset() {
        focusedTarget   = null;
        currentRange    = -1;
        attackedThisTick = false;
        inIdlePause     = false;
        burstRemaining  = 0;
        hitTimer.reset();
        idleTimer.reset();
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @Override
    public void onPlayerTick() {
        attackedThisTick = false;

        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        // ── LMB gate ──────────────────────────────────────────────────────
        if (requireClick.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS)
            return;

        // ── Weapon filter ─────────────────────────────────────────────────
        if (!passesWeaponFilter()) return;

        // ── Crosshair entity ──────────────────────────────────────────────
        if (!(mc.crosshairTarget instanceof EntityHitResult entityHit)) return;
        if (!(entityHit.getEntity() instanceof LivingEntity target)) return;
        if (target.isDead() || target.isRemoved()) return;

        // ── Target filter ─────────────────────────────────────────────────
        if (!passesTargetFilter(target)) return;

        // ── Friend check ──────────────────────────────────────────────────
        if (skipFriends.getValue() && target instanceof PlayerEntity player) {
            Friends fm = Argon.INSTANCE.getModuleManager().getModule(Friends.class);
            if (fm != null && Argon.INSTANCE.getFriendManager().isFriend(player)) return;
        }

        // ── Shield check ──────────────────────────────────────────────────
        if (skipShielding.getValue()
                && target instanceof PlayerEntity p
                && p.isBlocking()
                && p.isHolding(Items.SHIELD)) return;

        // ── Invisible check ───────────────────────────────────────────────
        if (skipInvisible.getValue() && target.isInvisible()) return;

        // ── Health gate ───────────────────────────────────────────────────
        if (healthGate.getValue() && target.getHealth() > healthThreshold.getValue()) return;

        // ── Crit gate ─────────────────────────────────────────────────────
        if (critOnly.getValue() && !canCrit()) return;

        // ── Focus mode ────────────────────────────────────────────────────
        if (focusMode.getValue()) {
            if (focusedTarget != null
                    && (focusedTarget.isDead()
                    || focusedTarget.isRemoved()
                    || focusedTarget.distanceTo(mc.player) > focusRange.getValue())) {
                focusedTarget = null;
                currentRange  = -1;
            }
            if (focusedTarget == null) focusedTarget = target;
            if (focusedTarget != target) return;
        }

        // ── Randomise attack range per cycle ──────────────────────────────
        if (currentRange < 0)
            currentRange = (rangeVariance.getMinInt() + rng.nextInt(
                    rangeVariance.getMaxInt() - rangeVariance.getMinInt() + 1)) / 10.0;

        double dist = mc.player.getCameraPosVec(net.minecraft.client.render.RenderTickCounter.ONE.getTickDelta(false))
                .squaredDistanceTo(mc.crosshairTarget.getPos());
        if (dist > currentRange * currentRange) return;

        // ── Post-attack idle pause (human rhythm) ─────────────────────────
        if (inIdlePause) {
            long idleMs = 40L + rng.nextInt(60); // 40-100 ms pause
            if (!idleTimer.hasReached(idleMs)) return;
            inIdlePause = false;
        }

        // ── Misclick: random skip ─────────────────────────────────────────
        if (misclick.getValue() && rng.nextInt(100) < misclickChance.getValueInt()) {
            currentRange = -1; // re-roll range next window
            return;
        }

        // ── Timing gate ───────────────────────────────────────────────────
        if (!timingOk(target)) return;

        // ── Fire ──────────────────────────────────────────────────────────
        if (attackMode.isMode(AttackMode.Burst)) {
            fireBurst(target);
        } else {
            fireOne(target);
        }
    }

    // ── Attack helpers ────────────────────────────────────────────────────────

    /** Fires a single click-attack with a randomised hold duration. */
    private void fireOne(LivingEntity target) {
        if (attackedThisTick) return;

        int holdMs = rangeInt(clickHoldMs.getMinInt(), clickHoldMs.getMaxInt());
        MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, holdMs);

        WorldUtils.hitEntity(target, true);
        hitTimer.reset();
        attackedThisTick = true;
        currentRange     = -1;

        // Start idle pause
        inIdlePause = true;
        idleTimer.reset();
    }

    /** Fires multiple rapid clicks asynchronously (Burst mode). */
    private void fireBurst(LivingEntity target) {
        if (attackedThisTick) return;

        int clicks = rangeInt(burstClicks.getMinInt(), burstClicks.getMaxInt());

        // Submit burst on a separate thread to avoid stalling the game tick
        MouseSimulation.clickExecutor.submit(() -> {
            for (int i = 0; i < clicks; i++) {
                try {
                    int holdMs = rangeInt(clickHoldMs.getMinInt(), clickHoldMs.getMaxInt());
                    MouseSimulation.mousePress(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    Thread.sleep(holdMs);
                    MouseSimulation.mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT);

                    if (i < clicks - 1) {
                        int gapMs = rangeInt(burstDelay.getMinInt(), burstDelay.getMaxInt());
                        Thread.sleep(gapMs);
                    }
                } catch (InterruptedException ignored) {}
            }
        });

        WorldUtils.hitEntity(target, true);
        hitTimer.reset();
        attackedThisTick = true;
        currentRange     = -1;

        inIdlePause = true;
        idleTimer.reset();
    }

    // ── Timing ────────────────────────────────────────────────────────────────

    private boolean timingOk(LivingEntity target) {
        return switch (attackMode.getMode()) {
            case Cooldown -> {
                float pct = (float)(cooldownPct.getValue() * 0.01);
                // Adaptive CPS: lower threshold when target is almost dead
                if (adaptiveCPS.getValue() && target.getHealth() < 6f)
                    pct = Math.max(0.80f, pct - 0.05f);
                yield mc.player.getAttackCooldownProgress(0f) >= pct;
            }
            case Delay  -> hitTimer.hasReached(attackDelay.getRandomValue());
            case Burst  -> hitTimer.hasReached(attackDelay.getRandomValue());
        };
    }

    // ── Filter helpers ────────────────────────────────────────────────────────

    private boolean passesWeaponFilter() {
        var item = mc.player.getMainHandStack().getItem();
        return switch (weaponFilter.getMode()) {
            case Any        -> true;
            case Sword      -> item instanceof SwordItem;
            case Axe        -> item instanceof AxeItem;
            case SwordOrAxe -> item instanceof SwordItem || item instanceof AxeItem;
            case Mace       -> item instanceof MaceItem;
            case Trident    -> item instanceof TridentItem;
        };
    }

    private boolean passesTargetFilter(LivingEntity entity) {
        return switch (targetFilter.getMode()) {
            case Players -> entity instanceof PlayerEntity;
            case Mobs    -> entity instanceof MobEntity;
            case Animals -> entity instanceof AnimalEntity;
            case All     -> true;
        };
    }

    private boolean canCrit() {
        return mc.player.fallDistance > 0.0f
                && !mc.player.isOnGround()
                && !mc.player.isClimbing()
                && !mc.player.isTouchingWater()
                && !mc.player.isInLava()
                && mc.player.getAttackCooldownProgress(0.5f) > 0.9f;
    }

    // ── No Bounce ─────────────────────────────────────────────────────────────

    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        if (mc.player == null) return;
        if (noBounce.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private int rangeInt(int min, int max) {
        if (min >= max) return min;
        return min + rng.nextInt(max - min + 1);
    }
}
