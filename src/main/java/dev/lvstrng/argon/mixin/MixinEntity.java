package dev.lvstrng.argon.mixin;

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.module.modules.combat.Hitboxes;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MixinEntity {

    @Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
    private void argon$expandHitbox(CallbackInfoReturnable<Box> cir) {
        Hitboxes hitboxes = Argon.INSTANCE.getModuleManager().getModule(Hitboxes.class);
        if (hitboxes == null || !hitboxes.isEnabled()) return;

        Entity self = (Entity) (Object) this;
        double expandXZ = hitboxes.getExpansionFor(self);
        if (expandXZ <= 0) return;

        double expandY = hitboxes.getYExpansionFor(self);
        Box original = cir.getReturnValue();
        cir.setReturnValue(original.expand(expandXZ, expandY, expandXZ));
    }
}
