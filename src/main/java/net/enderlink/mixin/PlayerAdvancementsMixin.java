package net.enderlink.mixin;

import net.enderlink.EnderLink;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

/**
 * Fabric API has no advancement event, so the completion hook is taken here.
 *
 * <p>{@code award} fires once per <em>criterion</em>, not once per advancement, so "did this
 * just complete?" has to be derived. The obvious way — record {@code isDone()} at HEAD, compare
 * at RETURN — is wrong in two ways that both fail silently:
 *
 * <ol>
 *   <li><b>Reentrancy.</b> {@code award} calls {@code AdvancementRewards.grant} <i>before</i>
 *       returning, and that grants recipes, which fires {@code RecipeUnlockedTrigger}, which
 *       calls {@code award} again on this same instance. A nested call would overwrite the
 *       outer call's saved flag, and the outer advancement would never be announced.</li>
 *   <li><b>Unbalanced injection.</b> Another mod's cancellable HEAD injector (fabric-events-
 *       interaction-v0 has one here) can return early, skipping the RETURN callback and
 *       leaving any HEAD-pushed state stranded.</li>
 * </ol>
 *
 * <p>So instead of pairing HEAD with RETURN, this just remembers which advancements it has
 * already reported. {@code Set.add} answers "is this the first time?" atomically, needs no
 * matching call to stay correct, and is immune to nesting. The set is per-player and dies with
 * the player's {@code PlayerAdvancements}.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    /**
     * Lazily created rather than field-initialised: Mixin merges field initialisers into the
     * target's constructors, and not depending on that keeps this correct regardless.
     */
    @Unique
    private Set<AdvancementHolder> enderlink$reported;

    @Inject(method = "award", at = @At("RETURN"))
    private void enderlink$announceCompletion(AdvancementHolder advancement, String criterionKey,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        PlayerAdvancements self = (PlayerAdvancements) (Object) this;
        if (!self.getOrStartProgress(advancement).isDone()) {
            return;
        }

        if (this.enderlink$reported == null) {
            this.enderlink$reported = new HashSet<>();
        }
        if (!this.enderlink$reported.add(advancement)) {
            return;
        }

        EnderLink bridge = EnderLink.get();
        if (bridge != null && this.player != null) {
            bridge.onAdvancement(this.player, advancement);
        }
    }

    /**
     * Keeps {@code /advancement revoke} honest — without this, an advancement taken away and
     * earned again would never post a second time.
     */
    @Inject(method = "revoke", at = @At("RETURN"))
    private void enderlink$forgetRevoked(AdvancementHolder advancement, String criterionKey,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && this.enderlink$reported != null) {
            this.enderlink$reported.remove(advancement);
        }
    }
}
