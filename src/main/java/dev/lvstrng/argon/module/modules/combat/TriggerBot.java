package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.MathUtils;
import dev.lvstrng.argon.utils.TimerUtils;
import dev.lvstrng.argon.utils.WorldUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.TridentItem;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

public final class TriggerBot extends Module implements TickListener, AttackListener {

    private final ModeSetting<AttackMode> attackMode = new ModeSetting<>(
            EncryptedString.of("Attack Mode"), AttackMode.Cooldown, AttackMode.class)
            .setDescription(EncryptedString.of("Cooldown waits for full cooldown, Delay uses a timer"));

    private final MinMaxSetting attackDelay = new MinMaxSetting(
            EncryptedString.of("Attack Delay"), 0, 500, 1, 10, 25);

    private final NumberSetting cooldown = new NumberSetting(
            EncryptedString.of("Cooldown %"), 0, 100, 95, 1)
            .setDescription(EncryptedString.of("Attack when cooldown reaches this percentage"));

    private final BooleanSetting holdingWeapon = new BooleanSetting(
            EncryptedString.of("Only Weapon"), false)
            .setDescription(EncryptedString.of("Only triggers when holding a sword, axe, trident or mace"));

    private final BooleanSetting requireClick = new BooleanSetting(
            EncryptedString.of("Require Click"), false)
            .setDescription(EncryptedString.of("Only triggers when holding left click"));

    private final BooleanSetting ignoreShield = new BooleanSetting(
            EncryptedString.of("Ignore Shield"), false)
            .setDescription(EncryptedString.of("Skip attacking shielding players"));

    private final ModeSetting<TargetMode> targetMode = new ModeSetting<>(
            EncryptedString.of("Target"), TargetMode.Player, TargetMode.class);

    public enum AttackMode { Cooldown, Delay }
    public enum TargetMode { Player, All }

    private final TimerUtils hitTimer = new TimerUtils();
    private boolean attackedThisTick;

    public TriggerBot() {
        super(EncryptedString.of("Trigger Bot"),
                EncryptedString.of("Automatically attacks enemies in your crosshair"),
                -1,
                Category.COMBAT);
        addSettings(attackMode, attackDelay, cooldown, holdingWeapon, requireClick, ignoreShield, targetMode);
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        eventManager.add(AttackListener.class, this);
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        eventManager.remove(AttackListener.class, this);
        super.onDisable();
    }

    @Override
    public void onTick() {
        attackedThisTick = false;

        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        if (holdingWeapon.getValue()) {
            var item = mc.player.getMainHandStack().getItem();
            if (!(item instanceof SwordItem || item instanceof AxeItem
                    || item instanceof TridentItem || item instanceof MaceItem)) return;
        }

        if (requireClick.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS)
            return;

        if (attackMode.isMode(AttackMode.Cooldown)) {
            float progress = mc.player.getAttackCooldownProgress(
                    (float) 0);
            if (progress < cooldown.getValue() * 0.01f) return;
        } else {
            if (!hitTimer.hasReached(MathUtils.randomInt(attackDelay.getMinInt(), attackDelay.getMaxInt()))) return;
        }

        if (!(mc.crosshairTarget instanceof EntityHitResult entityHitResult)) return;
        if (!(entityHitResult.getEntity() instanceof LivingEntity target)) return;

        if (targetMode.isMode(TargetMode.Player) && !(target instanceof PlayerEntity)) return;

        if (ignoreShield.getValue()) {
            if (target instanceof PlayerEntity p && p.isBlocking() && p.isHolding(Items.SHIELD)) return;
        }

        if (attackedThisTick) return;
        attackedThisTick = true;

        WorldUtils.hitEntity(target, true);
        hitTimer.reset();
    }

    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        // no-op
    }
}
