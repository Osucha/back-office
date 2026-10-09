package pl.mos.backoffice.projects.domain.model

import java.util.UUID

/** Identifiers of the Projects & Workflows context. All are UUID value classes with `new()` / `of()` factories. */
@JvmInline
value class ProjectId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = ProjectId(UUID.randomUUID())
        fun of(value: String) = ProjectId(UUID.fromString(value))
    }
}

@JvmInline
value class ProjectCiTokenId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = ProjectCiTokenId(UUID.randomUUID())
        fun of(value: String) = ProjectCiTokenId(UUID.fromString(value))
    }
}

@JvmInline
value class WorkflowDefinitionId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = WorkflowDefinitionId(UUID.randomUUID())
        fun of(value: String) = WorkflowDefinitionId(UUID.fromString(value))
    }
}

@JvmInline
value class WorkflowRunId(val value: UUID) {
    override fun toString(): String = value.toString()
    /** First 8 characters, used in job labels and branch names. */
    val short: String get() = value.toString().substring(0, 8)
    companion object {
        fun new() = WorkflowRunId(UUID.randomUUID())
        fun of(value: String) = WorkflowRunId(UUID.fromString(value))
    }
}

@JvmInline
value class NodeExecutionId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = NodeExecutionId(UUID.randomUUID())
        fun of(value: String) = NodeExecutionId(UUID.fromString(value))
    }
}

@JvmInline
value class ArtifactId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = ArtifactId(UUID.randomUUID())
        fun of(value: String) = ArtifactId(UUID.fromString(value))
    }
}

@JvmInline
value class ApprovalRequestId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = ApprovalRequestId(UUID.randomUUID())
        fun of(value: String) = ApprovalRequestId(UUID.fromString(value))
    }
}

@JvmInline
value class CiExecutionId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new() = CiExecutionId(UUID.randomUUID())
        fun of(value: String) = CiExecutionId(UUID.fromString(value))
    }
}
