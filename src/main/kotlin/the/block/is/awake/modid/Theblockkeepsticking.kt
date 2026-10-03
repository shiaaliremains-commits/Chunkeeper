package the.block.is.awake.modid

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * المسار: src/main/kotlin/the/block/is/awake/modid/Theblockkeepsticking.kt  (استبدل الموجود)
 */
object Theblockkeepsticking : ModInitializer {
    const val MOD_ID = "theblockkeepsticking"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        ModAttachments.init()
        ChunkCatchUp.init()
        LOGGER.info("The Blocks Keep Ticking loaded.")
    }
}
