package net.fayber.graves;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// mod entrypoint: load config, wire up events and the /graves command
public class GravesMod implements ModInitializer {
    public static final String MOD_ID = "graves";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        GraveConfig.load();
        GraveEvents.register();

        LOGGER.info("[Graves] Initialized. Config: {}", GraveConfig.get());
    }
}
