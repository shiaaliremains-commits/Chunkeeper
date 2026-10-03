package the.block.is.awake.modid

import java.util.ArrayDeque
import java.util.IdentityHashMap
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.block.AbstractFurnaceBlock
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk

/**
 * المسار: src/main/kotlin/the/block/is/awake/modid/ChunkCatchUp.kt  (استبدل الموجود)
 *
 * التصميم الجديد: نراقب "حالة التشغيل" (ticking) لكل Chunk محمّل.
 *  - يتوقف عن العمل (ابتعدت عنه)  -> نختم الوقت الحالي.
 *  - يرجع يعمل (رجعت له)          -> نحسب الفرق ونطبق Random Ticks + الأفران.
 * يعمل سواء الـ Chunk انحذف من الذاكرة أو بقي محمّلاً بدون tick.
 *
 * الختم (attachment) يعني: "الـ Chunk متوقف عن العمل منذ هذا الوقت".
 * بدون ختم = يعمل حالياً.
 */
object ChunkCatchUp {
    private const val MIN_ELAPSED_TICKS = 100L
    private const val MAX_TICKS_PER_BLOCK = 256
    private const val MAX_FURNACE_TICKS = 72_000L
    private const val BUDGET_NANOS = 2_000_000L
    /** كل كم tick نفحص حالة الـ Chunks (10 = نصف ثانية). */
    private const val POLL_INTERVAL = 10

    private const val DEBUG = true
    private var debugCount = 0
    private var pollTimer = 0

    private class Tracked(val chunk: LevelChunk) {
        var ticking = false
    }

    private val QUEUE = ArrayDeque<Job>()
    private val LOADED = IdentityHashMap<ServerLevel, MutableMap<ChunkPos, Tracked>>()

    private fun debug(msg: String) {
        if (DEBUG && debugCount < 200) {
            debugCount++
            Theblockkeepsticking.LOGGER.info("[CatchUp] $msg")
        }
    }

    fun init() {
        // عند فتح العالم: نضيف وقت الغياب الحقيقي إلى ساعة المود
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val nowMs = System.currentTimeMillis()
            for (level in server.allLevels) {
                val shutdown: Long = level.getAttached(ModAttachments.SHUTDOWN_MILLIS) ?: continue
                level.removeAttached(ModAttachments.SHUTDOWN_MILLIS)
                val gapTicks = ((nowMs - shutdown) / 50L).coerceAtLeast(0L)
                val total = (level.getAttached(ModAttachments.OFFLINE_TICKS) ?: 0L) + gapTicks
                level.setAttached(ModAttachments.OFFLINE_TICKS, total)
                debug("offline gap = $gapTicks ticks, total offline = $total")
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { reset() }

        // عند الإغلاق: الـ Chunks الشغّالة نختمها بالوقت الحالي، والمتوقفة نتركها بختمها القديم
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            for ((level, map) in LOADED) {
                val now = virtualTime(level)
                for (t in map.values) {
                    if (t.ticking) t.chunk.setAttached(ModAttachments.LAST_SEEN_TICK, now)
                }
            }
            val ms = System.currentTimeMillis()
            for (level in server.allLevels) level.setAttached(ModAttachments.SHUTDOWN_MILLIS, ms)
            reset()
        }

        ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ ->
            LOADED.computeIfAbsent(level) { HashMap() }[chunk.pos] = Tracked(chunk)
        }
        ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk -> onUnload(level, chunk) }

        ServerTickEvents.END_SERVER_TICK.register {
            if (++pollTimer >= POLL_INTERVAL) {
                pollTimer = 0
                poll()
            }
            processQueue()
        }
    }

    /** ساعة المود = وقت اللعبة + وقت الغياب وأنت خارج العالم. */
    private fun virtualTime(level: ServerLevel): Long =
        level.gameTime + (level.getAttached(ModAttachments.OFFLINE_TICKS) ?: 0L)

    private fun reset() {
        QUEUE.clear()
        LOADED.clear()
    }

    private fun onUnload(level: ServerLevel, chunk: LevelChunk) {
        val tracked = LOADED[level]?.remove(chunk.pos)
        if (tracked != null && tracked.ticking) {
            chunk.setAttached(ModAttachments.LAST_SEEN_TICK, virtualTime(level))
            debug("unload-while-ticking ${chunk.pos}")
        }
        QUEUE.removeIf { it.chunk === chunk }
    }

    private fun poll() {
        for ((level, map) in LOADED) {
            val now = virtualTime(level)
            for (t in map.values) {
                val c = t.chunk
                val nowTicking = level.shouldTickBlocksAt(BlockPos(c.pos.middleBlockX, 0, c.pos.middleBlockZ))
                if (nowTicking == t.ticking) continue
                t.ticking = nowTicking
                if (nowTicking) resume(level, c, now) else {
                    c.setAttached(ModAttachments.LAST_SEEN_TICK, now)
                    debug("stopped ${c.pos} stamp=$now")
                }
            }
        }
    }

    private fun resume(level: ServerLevel, chunk: LevelChunk, now: Long) {
        val last: Long = chunk.getAttached(ModAttachments.LAST_SEEN_TICK) ?: return
        chunk.removeAttached(ModAttachments.LAST_SEEN_TICK)

        val elapsed = now - last
        if (elapsed < MIN_ELAPSED_TICKS) return

        val rts: Int = level.gameRules.get(GameRules.RANDOM_TICK_SPEED)
        val expected = elapsed * (rts.coerceAtLeast(0) / 4096.0)
        debug("resume ${chunk.pos} elapsed=$elapsed expectedPerBlock=$expected")
        QUEUE.add(Job(level, chunk, elapsed, expected))
    }

    private fun processQueue() {
        if (QUEUE.isEmpty()) return
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (QUEUE.isNotEmpty() && System.nanoTime() < deadline) {
            if (QUEUE.peek().run(deadline)) QUEUE.poll()
        }
    }

    private fun skip(state: BlockState): Boolean =
        !state.isRandomlyTicking || state.block is FireBlock

    private class Job(
        val level: ServerLevel,
        val chunk: LevelChunk,
        val elapsed: Long,
        val expectedPerBlock: Double
    ) {
        private val pos = BlockPos.MutableBlockPos()
        private val baseX = chunk.pos.minBlockX
        private val baseZ = chunk.pos.minBlockZ
        private var sectionIndex = 0
        private var blockIndex = 0
        private var randomDone = expectedPerBlock <= 0.0

        private var furnaces: List<AbstractFurnaceBlockEntity>? = null
        private var furnaceIdx = 0
        private var furnaceTicks = 0L
        private var unlitStreak = 0

        fun run(deadline: Long): Boolean {
            if (!randomDone) {
                if (!runRandom(deadline)) return false
                randomDone = true
            }
            return runFurnaces(deadline)
        }

        private fun runRandom(deadline: Long): Boolean {
            val sections = chunk.sections
            while (sectionIndex < sections.size) {
                val section = sections[sectionIndex]
                if (section.hasOnlyAir() || !section.isRandomlyTickingBlocks) {
                    sectionIndex++
                    blockIndex = 0
                    continue
                }
                val baseY = chunk.getSectionYFromSectionIndex(sectionIndex) shl 4

                while (blockIndex < 4096) {
                    val i = blockIndex++
                    val x = i and 15
                    val z = (i shr 4) and 15
                    val y = i shr 8
                    var state = section.getBlockState(x, y, z)
                    if (!skip(state)) {
                        val n = rollTicks()
                        if (n > 0) {
                            pos.set(baseX + x, baseY + y, baseZ + z)
                            for (t in 0 until n) {
                                state.randomTick(level, pos, level.getRandom())
                                state = level.getBlockState(pos)
                                if (skip(state)) break
                            }
                        }
                    }
                    if ((i and 63) == 0 && System.nanoTime() >= deadline) return false
                }
                sectionIndex++
                blockIndex = 0
            }
            return true
        }

        private fun runFurnaces(deadline: Long): Boolean {
            val list = furnaces ?: chunk.blockEntities.values
                .filterIsInstance<AbstractFurnaceBlockEntity>()
                .also { furnaces = it }
            val limit = minOf(elapsed, MAX_FURNACE_TICKS)

            while (furnaceIdx < list.size) {
                val be = list[furnaceIdx]
                while (furnaceTicks < limit) {
                    val state = level.getBlockState(be.blockPos)
                    if (be.isRemoved || state.block !is AbstractFurnaceBlock) break

                    AbstractFurnaceBlockEntity.serverTick(level, be.blockPos, state, be)
                    furnaceTicks++

                    val after = level.getBlockState(be.blockPos)
                    val lit = after.hasProperty(AbstractFurnaceBlock.LIT) &&
                        after.getValue(AbstractFurnaceBlock.LIT)
                    if (lit) unlitStreak = 0 else if (++unlitStreak >= 2) break

                    if ((furnaceTicks and 63L) == 0L && System.nanoTime() >= deadline) return false
                }
                furnaceIdx++
                furnaceTicks = 0
                unlitStreak = 0
            }
            return true
        }

        private fun rollTicks(): Int {
            var base = expectedPerBlock.toInt()
            val frac = expectedPerBlock - base
            if (frac > 0 && level.getRandom().nextDouble() < frac) base++
            return minOf(base, MAX_TICKS_PER_BLOCK)
        }
    }
}
