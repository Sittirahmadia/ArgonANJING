package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.InventoryUtils;
import dev.lvstrng.argon.utils.RotationUtils;
import dev.lvstrng.argon.utils.rotation.Rotation;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.BlockHitResult;

public final class HitCob extends Module implements AttackListener {

    private final NumberSetting range = new NumberSetting(
            EncryptedString.of("Max Place Range"), 1.0, 6.0, 4.5, 0.1)
            .setDescription(EncryptedString.of("Maximum reach to place the cobweb"));

    private final BooleanSetting predictKnockback = new BooleanSetting(
            EncryptedString.of("Predict Knockback"), true)
            .setDescription(EncryptedString.of("Place web at the predicted post-knockback position"));

    private final BooleanSetting skipIfWebbed = new BooleanSetting(
            EncryptedString.of("Skip If Webbed"), true)
            .setDescription(EncryptedString.of("Don't waste a web if the target is already in one"));

    private static final double SPEED_STOPPED = 0.05;
    private static final double SPEED_WALKING = 0.13;

    public HitCob() {
        super(EncryptedString.of("Hit Cob"),
                EncryptedString.of("Places a cobweb at the enemy's feet when you hit them"),
                -1, Category.COMBAT);
        addSettings(range, predictKnockback, skipIfWebbed);
    }

    @Override
    public void onEnable() {
        eventManager.add(AttackListener.class, this);
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(AttackListener.class, this);
        super.onDisable();
    }

    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!(mc.player.getAttacking() instanceof LivingEntity target)) return;

        int webSlot = findCobwebSlot();
        if (webSlot == -1) return;

        BlockPos feetPos = target.getBlockPos();

        if (skipIfWebbed.getValue()
                && mc.world.getBlockState(feetPos).getBlock() == Blocks.COBWEB) return;

        // Decide placement position
        BlockPos placePos;
        if (predictKnockback.getValue()) {
            double speed = Math.hypot(target.getVelocity().x, target.getVelocity().z);
            Vec3d knockDir = target.getPos().subtract(mc.player.getPos()).normalize();

            double knockDist;
            if (speed < SPEED_STOPPED) {
                knockDist = mc.player.isSprinting() ? 1.8 : 1.2;
            } else if (speed < SPEED_WALKING) {
                knockDist = mc.player.isSprinting() ? 1.5 : 1.0;
            } else {
                knockDist = mc.player.isSprinting() ? 1.2 : 0.8;
            }

            Vec3d predicted = target.getPos().add(knockDir.x * knockDist, 0, knockDir.z * knockDist);
            placePos = BlockPos.ofFloored(predicted);

            // Already webbed there, or not air — fall back to feet
            if (mc.world.getBlockState(placePos).getBlock() == Blocks.COBWEB
                    || !mc.world.getBlockState(placePos).isAir()) {
                placePos = feetPos;
            }
        } else {
            placePos = feetPos;
        }

        // Needs solid floor
        BlockPos groundPos = placePos.down();
        if (mc.world.getBlockState(groundPos).isAir()) return;

        // Range check
        if (mc.player.getPos().distanceTo(Vec3d.ofCenter(placePos)) > range.getValue()) return;

        // Aim, place, restore
        int originalSlot    = mc.player.getInventory().selectedSlot;
        float originalYaw   = mc.player.getYaw();
        float originalPitch = mc.player.getPitch();

        Vec3d hitVec = Vec3d.ofCenter(groundPos).add(0, 0.5, 0);
        Rotation rot = RotationUtils.getDirection(mc.player, hitVec);
        mc.player.setYaw((float) rot.yaw());
        mc.player.setPitch((float) rot.pitch());

        InventoryUtils.setInvSlot(webSlot);

        BlockHitResult blockHit = new BlockHitResult(hitVec, Direction.UP, groundPos, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);

        InventoryUtils.setInvSlot(originalSlot);
        mc.player.setYaw(originalYaw);
        mc.player.setPitch(originalPitch);
    }

    private int findCobwebSlot() {
        for (int i = 0; i < 9; i++) {
            var stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.COBWEB) return i;
        }
        return -1;
    }
}