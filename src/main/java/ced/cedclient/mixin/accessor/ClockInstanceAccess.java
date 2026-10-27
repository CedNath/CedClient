package ced.cedclient.mixin.accessor;

import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;

/** Lets a client clock instance remember which world clock it belongs to. */
public interface ClockInstanceAccess {
    void cedclient$setDefinition(Holder<WorldClock> definition);

    Holder<WorldClock> cedclient$getDefinition();
}
