package git.chexson.chexsonsaeutils.parts.playersimulation;

import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationCooldownsTest {
    @Test void cooldownSurvivesReloadAndExpiresAtCorrectTick() {
        var cooldowns = new SimulationCooldowns();
        cooldowns.addCooldown(Items.ENDER_PEARL,20);
        for (int i = 0; i < 7; i++) cooldowns.tick();
        var loaded = new SimulationCooldowns();
        loaded.load(cooldowns.save());
        for (int i = 0; i < 12; i++) { assertTrue(loaded.isOnCooldown(Items.ENDER_PEARL)); loaded.tick(); }
        assertTrue(loaded.isOnCooldown(Items.ENDER_PEARL));
        loaded.tick();
        assertFalse(loaded.isOnCooldown(Items.ENDER_PEARL));
        assertTrue(loaded.save().isEmpty());
    }
    @Test void ignoringSelectedItemDoesNotClearOtherCooldowns() {
        var cooldowns = new SimulationCooldowns();
        cooldowns.addCooldown(Items.ENDER_PEARL,20);
        cooldowns.addCooldown(Items.SNOWBALL,8);
        cooldowns.removeCooldown(Items.ENDER_PEARL);
        assertFalse(cooldowns.isOnCooldown(Items.ENDER_PEARL));
        assertTrue(cooldowns.isOnCooldown(Items.SNOWBALL));
    }
}
