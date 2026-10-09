package pl.mos.backoffice.projects.adapter.out.langgraph

import org.bsc.langgraph4j.RunnableConfig
import org.bsc.langgraph4j.action.InterruptionMetadata
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver
import org.bsc.langgraph4j.checkpoint.Checkpoint
import org.bsc.langgraph4j.state.AgentState
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.Optional
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource

/**
 * [DataSource] wrapper that remembers every connection handed out while a [scoped] block runs on the
 * current thread, so callers can close what a library forgot to. Outside a scope it is transparent.
 */
class TrackingDataSource(private val delegate: DataSource) : DataSource by delegate {

    private val open = ThreadLocal<MutableSet<Connection>?>()

    fun <T> scoped(block: () -> T): T {
        val outer = open.get()
        val mine = LinkedHashSet<Connection>()
        open.set(mine)
        try {
            return block()
        } finally {
            open.set(outer)
            mine.toList().forEach { c ->
                try {
                    if (!c.isClosed) {
                        log.debug("closing connection leaked by checkpoint saver")
                        c.close()
                    }
                } catch (e: Exception) {
                    log.warn("could not close leaked connection: {}", e.message)
                }
            }
        }
    }

    override fun getConnection(): Connection = track(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection = track(delegate.getConnection(username, password))

    private fun track(real: Connection): Connection {
        val set = open.get() ?: return real
        val proxy = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { self, method, args ->
            if (method.name == "close" && (args == null || args.isEmpty())) set.remove(self as Connection)
            try {
                if (args == null) method.invoke(real) else method.invoke(real, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
        set += proxy
        return proxy
    }

    companion object {
        private val log = LoggerFactory.getLogger(TrackingDataSource::class.java)
    }
}

/**
 * Delegating [BaseCheckpointSaver] that runs every call inside a [TrackingDataSource.scoped] block.
 * Works around langgraph4j 1.9.1 `AbstractPostgresSaver.exec()` not closing the connection it borrows
 * (only `execTransaction` does), which otherwise exhausts the Hikari pool after a handful of checkpoints.
 */
class LeakSafeCheckpointSaver(private val delegate: BaseCheckpointSaver, private val tracking: TrackingDataSource) : BaseCheckpointSaver {

    override fun list(config: RunnableConfig): Collection<Checkpoint> = tracking.scoped { delegate.list(config) }

    override fun get(config: RunnableConfig): Optional<Checkpoint> = tracking.scoped { delegate.get(config) }

    override fun put(config: RunnableConfig, checkpoint: Checkpoint): RunnableConfig = tracking.scoped { delegate.put(config, checkpoint) }

    override fun release(config: RunnableConfig): BaseCheckpointSaver.Tag = tracking.scoped { delegate.release(config) }

    override fun release(config: RunnableConfig, message: String?): BaseCheckpointSaver.Tag = tracking.scoped { delegate.release(config, message) }

    override fun releaseOnError(config: RunnableConfig, error: Throwable?): BaseCheckpointSaver.Tag = tracking.scoped { delegate.releaseOnError(config, error) }

    override fun <State : AgentState> registerInterruption(
        config: RunnableConfig,
        interruptionMetadata: InterruptionMetadata<State>,
    ): CompletableFuture<InterruptionMetadata<State>> = tracking.scoped { delegate.registerInterruption(config, interruptionMetadata) }

    override fun tag(config: RunnableConfig, version: Int?): Optional<BaseCheckpointSaver.Tag> = tracking.scoped { delegate.tag(config, version) }

    override fun putSubGraphSaver(config: RunnableConfig, subGraphConfig: RunnableConfig, saver: BaseCheckpointSaver) =
        tracking.scoped { delegate.putSubGraphSaver(config, subGraphConfig, saver) }

    override fun listSubGraphSaver(config: RunnableConfig): Collection<BaseCheckpointSaver.SubGraphSaver> = tracking.scoped { delegate.listSubGraphSaver(config) }
}
