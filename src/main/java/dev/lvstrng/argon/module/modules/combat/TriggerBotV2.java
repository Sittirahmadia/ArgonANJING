package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.TickListener;
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
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/**
 * TriggerBotV2 — crosshair-based auto-attacker.
 *
 * Modes:
 *   Cooldown — attacks when swing cooldown reaches the configured %
 *   Delay    — attacks on a randomised ms timer
 *   Instant  — attacks every single tick (no cooldown check)
 *
 * Filters:
 *   Target   — Players / Mobs / Animals / All
 *   Weapon   — Any / Sword / Axe / SwordOrAxe / Mace / Trident
 *
 * Extra:
 *   Crit Only     — only attack when airborne (guarantees a crit)
 *   Skip Shielding — skip actively-blocking players
 *   Skip Friends  — skip FriendManager friends
 *   Require Click — only fires while LMB is held
 *   Health Gate   — only attack targets below X HP
 *   Simulate Click — sends a real LMB event with each attack
 *   No Bounce     — cancels vanilla LMB when not pressing LMB
 */
public final class TriggerBotV2 extends Module implements TickListener, AttackListener {

    // ── Enums ──────────────────────────────────────────────────────────────
    public enum AttackMode { Cooldown, Delay, Instant }
    public enum TargetFilter { Players, Mobs, Animals, All }
    public enum WeaponFilter { Any, Sword, Axe, SwordOrAxe, Mace, Trident }

    // ── Settings ───────────────────────────────────────────────────────────

    private final ModeSetting<AttackMode> attackMode = new ModeSetting<>(
            EncryptedString.of("Attack Mode"), AttackMode.Cooldown, AttackMode.class)
            .setDescription(EncryptedString.of("When to fire: Cooldown %, ms Delay, or every tick"));

    private final NumberSetting cooldownPct = new NumberSetting(
            EncryptedString.of("Cooldown %"), 50, 100, 95, 1)
            .setDescription(EncryptedString.of("Attack when cooldown reaches this % (Cooldown mode)"));

    private final MinMaxSetting attackDelay = new MinMaxSetting(
            EncryptedString.of("Attack Delay"), 0, 1000, 1, 50, 150)
            .setDescription(EncryptedString.of("Random ms delay between attacks (Delay mode)"));

    private final ModeSetting<TargetFilter> targetFilter = new ModeSetting<>(
            EncryptedString.of("Target Filter"), TargetFilter.Players, TargetFilter.class)
            .setDescription(EncryptedString.of("Which entity types to target"));

    private final ModeSetting<WeaponFilter> weaponFilter = new ModeSetting<>(
            EncryptedString.of("Weapon Filter"), WeaponFilter.Any, WeaponFilter.class)
            .setDescription(EncryptedString.of("Only trigger when holding this weapon type"));

    private final BooleanSetting critOnly = new BooleanSetting(
            EncryptedString.of("Crit Only"), false)
            .setDescription(EncryptedString.of("Only attack when airborne for a guaranteed crit"));

    private final BooleanSetting skipShielding = new BooleanSetting(
            EncryptedString.of("Skip Shielding"), true)
            .setDescription(EncryptedString.of("Skip players actively blocking with a shield"));

    private final BooleanSetting skipFriends = new BooleanSetting(
            EncryptedString.of("Skip Friends"), true)
            .setDescription(EncryptedString.of("Skip players in your friend list"));

    private final BooleanSetting requireClick = new BooleanSetting(
            EncryptedString.of("Require Click"), false)
            .setDescription(EncryptedString.of("Only trigger while holding left click"));

    private final BooleanSetting healthGate = new BooleanSetting(
            EncryptedString.of("Health Gate"), false)
            .setDescription(EncryptedString.of("Only attack when target HP is below the threshold"));

    private final NumberSetting healthThreshold = new NumberSetting(
            EncryptedString.of("HP Threshold"), 1, 40, 20, 1)
            .setDescription(EncryptedString.of("HP threshold for Health Gate"));

    private final BooleanSetting simulateClick = new BooleanSetting(
            EncryptedString.of("Simulate Click"), true)
            .setDescription(EncryptedString.of("Sends a real LMB event with each attack"));

    private final BooleanSetting noBounce = new BooleanSetting(
            EncryptedString.of("No Bounce"), true)
            .setDescription(EncryptedString.of("Cancels vanilla LMB attack when you are not pressing LMB"));

    // ── State ──────────────────────────────────────────────────────────────
    private final TimerUtils hitTimer = new TimerUtils();
    private boolean attackedThisTick = false;

    // ── Constructor ────────────────────────────────────────────────────────
    public TriggerBotV2() {
        super(EncryptedString.of("TriggerBot V2"),
                EncryptedString.of("Auto-attacks any entity in your crosshair"),
                -1,
                Category.COMBAT);
        addSettings(
                attackMode, cooldownPct, attackDelay,
                targetFilter, weaponFilter,
                critOnly, skipShielding, skipFriends,
                requireClick, healthGate, healthThreshold,
                simulateClick, noBounce
        );
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        eventManager.add(AttackListener.class, this);
        hitTimer.reset();
        attackedThisTick = false;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        eventManager.remove(AttackListener.class, this);
        super.onDisable();
    }

    // ── Filter helpers ─────────────────────────────────────────────────────

    private boolean passesWeaponFilter() {
        var item = mc.player.getMainHandStack().getItem();
        return switch (weaponFilter.getMode()) {
            case Any      -> true;
            case Sword    -> item instanceof SwordItem;
            case Axe      -> item instanceof AxeItem;
            case SwordOrAxe -> item instanceof SwordItem || item instanceof AxeItem;
            case Mace     -> item instanceof MaceItem;
            case Trident  -> item instanceof TridentItem;
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

    /** True when our player can land a crit this tick. */
    private boolean canCrit() {
        return mc.player.fallDistance > 0.0f
                && !mc.player.isOnGround()
                && !mc.player.isClimbing()
                && !mc.player.isTouchingWater()
                && !mc.player.isInLava()
                && mc.player.getAttackCooldownProgress(0.5f) > 0.9f;
    }

    /** True when the timing condition for the selected mode is satisfied. */
    private boolean timingOk() {
        return switch (attackMode.getMode()) {
            case Cooldown -> mc.player.getAttackCooldownProgress(0f) >= cooldownPct.getValue() * 0.01f;
            case Delay    -> hitTimer.hasReached(attackDelay.getRandomValue());
            case Instant  -> true;
        };
    }

    // ── Attack ─────────────────────────────────────────────────────────────
    private void doAttack(LivingEntity target) {
        if (simulateClick.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        WorldUtils.hitEntity(target, true);
        hitTimer.reset();
        attackedThisTick = true;
    }

    // ── Tick ───────────────────────────────────────────────────────────────
    @Override
    public void onTick() {
        attackedThisTick = false;

        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;

        // LMB gate
        if (requireClick.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS)
            return;

        // Weapon gate
        if (!passesWeaponFilter()) return;

        // Must be looking at a living entity
        if (!(mc.crosshairTarget instanceof EntityHitResult entityHit)) return;
        if (!(entityHit.getEntity() instanceof LivingEntity target)) return;

        // Must be alive
        if (target.isDead()) return;

        // Target filter
        if (!passesTargetFilter(target)) return;

        // Friend check
        if (skipFriends.getValue() && target instanceof PlayerEntity player) {
            Friends friendsModule = Argon.INSTANCE.getModuleManager().getModule(Friends.class);
            if (friendsModule != null && Argon.INSTANCE.getFriendManager().isFriend(player))
                return;
        }

        // Shield check
        if (skipShielding.getValue()
                && target instanceof PlayerEntity player
                && player.isBlocking()
                && player.isHolding(Items.SHIELD))
            return;

        // Crit gate
        if (critOnly.getValue() && !canCrit()) return;

        // Health gate
        if (healthGate.getValue() && target.getHealth() > healthThreshold.getValue()) return;

        // Timing gate
        if (!timingOk()) return;

        // One attack per tick max
        if (attackedThisTick) return;

        doAttack(target);
    }

    // ── No Bounce ──────────────────────────────────────────────────────────
    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        if (mc.player == null) return;
        if (noBounce.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }
}
