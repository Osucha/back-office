package pl.mos.backoffice.projects.adapter.out.langgraph

import org.bsc.langgraph4j.RunnableConfig
import org.bsc.langgraph4j.checkpoint.Checkpoint
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2
import java.util.LinkedList

/**
 * [PostgresSaverV2] that finds a thread's newest checkpoint by insertion order instead of the wall clock.
 *
 * langgraph4j-postgres-saver 1.9.1 loads a thread's checkpoints `ORDER BY saved_at DESC` and treats the first
 * one as the current state, with `saved_at DEFAULT CURRENT_TIMESTAMP` (the database server's transaction
 * start time). When the database clock steps backwards (NTP or VM time sync, failover to a server with a
 * skewed clock), checkpoints written after the step sort below older ones: `getState` and `GraphInput.resume()`
 * then see a stale checkpoint, and a run that actually reached its END node is failed with
 * "graph stopped before node ..." (or is resumed from an old node and repeats work).
 *
 * Here the order is `seq DESC NULLS LAST, saved_at DESC`, where `seq` is filled from a database sequence on
 * insert (V105). Rows written before V105 have no `seq` and keep the library's order among themselves.
 * Replacing a checkpoint in place (`updateState`) keeps its row and therefore its position, as before.
 */
class SequencedPostgresSaver(builder: PostgresSaverV2.Builder) : PostgresSaverV2(builder) {

    override fun loadCheckpoints(config: RunnableConfig): LinkedList<Checkpoint> {
        val checkpoints = LinkedList<Checkpoint>()
        datasource.connection.use { conn ->
            conn.prepareStatement(SELECT_CHECKPOINTS).use { ps ->
                ps.setString(1, threadId(config))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        checkpoints += Checkpoint.builder()
                            .id(rs.getString(1))
                            .nodeId(rs.getString(2))
                            .nextNodeId(rs.getString(3))
                            .state(decodeState(rs.getBytes(4), rs.getString(5)))
                            .build()
                    }
                }
            }
        }
        return checkpoints
    }

    companion object {
        /** Newest first; same columns as the library's `sqlSelectCheckpoints`. */
        const val SELECT_CHECKPOINTS = """
            SELECT c.checkpoint_id, c.node_id, c.next_node_id, c.state_data->>'binaryPayload' AS base64_data, c.state_content_type
            FROM lg4jthread t
            JOIN lg4jcheckpoint c ON c.thread_id = t.thread_id
            WHERE t.thread_name = ?
            ORDER BY c.seq DESC NULLS LAST, c.saved_at DESC
        """
    }
}
