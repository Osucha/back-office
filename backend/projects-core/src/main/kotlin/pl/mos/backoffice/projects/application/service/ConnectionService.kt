package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManageConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageConnections.ConnectionCommand
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageProjectJiraConnections
import pl.mos.backoffice.projects.application.port.`in`.ManageProjectJiraConnections.ProjectJiraConnectionsView
import pl.mos.backoffice.projects.application.port.`in`.RemindExpiringConnections
import pl.mos.backoffice.projects.application.port.`in`.RewrapConnectionSecrets
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.application.port.out.IssueTrackerFactory
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.SecretSealer
import pl.mos.backoffice.projects.domain.event.ConnectionExpiring
import pl.mos.backoffice.projects.domain.model.Connection
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.ConnectionId
import pl.mos.backoffice.projects.domain.model.ConnectionScope
import pl.mos.backoffice.projects.domain.model.ConnectionVerification
import pl.mos.backoffice.projects.domain.model.JiraProbe
import pl.mos.backoffice.projects.domain.model.JiraTokenKind
import pl.mos.backoffice.projects.domain.model.ProbeFailure
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectJiraConnections
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.VerificationCode
import pl.mos.backoffice.projects.domain.service.ConnectionChoice
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.projects.domain.service.ConnectionVerificationPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.IntegrationException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.security.PublicOperation
import pl.mos.backoffice.shared.security.SystemOperation
import java.time.LocalDate
import java.util.UUID

/**
 * Implements [ManageConnections], [ManagePersonalConnections], [ManageProjectJiraConnections] and the system jobs
 * (ADR 0030, ADR 0035). Secrets are sealed through [SecretSealer] before they reach the repository and never appear in
 * results, audit details or logs. Create, update and rotate verify the connection against Jira right away (a failed
 * verification is stored, it does not block the save). Scoped tokens get the site's cloud id discovered on save;
 * classic tokens need the "can do everything your account can" acknowledgement.
 */
class ConnectionService(
    private val connections: ConnectionRepository,
    private val projects: ProjectRepository,
    private val legacy: LegacyConnectionSource,
    private val sealer: SecretSealer,
    private val trackers: IssueTrackerFactory,
    private val selector: ConnectionSelector,
    private val verificationPolicy: ConnectionVerificationPolicy,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
    private val events: EventPublisher = EventPublisher.NOOP,
) : ManageConnections, ManagePersonalConnections, ManageProjectJiraConnections, RewrapConnectionSecrets, RemindExpiringConnections {

    /** Where a connection belongs: the platform, one project, or one person (ADR 0035). */
    private sealed interface Owner {
        val scope: ConnectionScope
        val projectId: ProjectId? get() = null
        val userId: UUID? get() = null

        data object Platform : Owner {
            override val scope = ConnectionScope.PLATFORM
        }

        data class OfProject(override val projectId: ProjectId) : Owner {
            override val scope = ConnectionScope.PROJECT
        }

        data class Personal(override val userId: UUID, val name: String) : Owner {
            override val scope = ConnectionScope.USER
        }
    }

    private fun owner(projectId: ProjectId?): Owner = projectId?.let { Owner.OfProject(it) } ?: Owner.Platform

    /** Checks the manage permission of [owner] and returns the acting user. */
    @Suppress("NOTHING_TO_INLINE") // inlined so the gate call is in the use-case method itself (DenyByDefaultArchitectureTest)
    private inline fun requireManage(owner: Owner): CurrentUser = when (owner) {
        Owner.Platform -> gate.require(ProjectsPermissions.CONNECTIONS_MANAGE)
        is Owner.OfProject -> gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, owner.projectId.value)
        is Owner.Personal -> gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
    }

    /** The caller as owner of personal connections: a person (session or personal token), with `connections.personal`. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun me(): Pair<CurrentUser, Owner.Personal> {
        val user = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val id = user.userId?.value
        if (id == null || (user.kind != PrincipalKind.USER && user.kind != PrincipalKind.PERSONAL_TOKEN)) {
            throw InsufficientPermissionException(ProjectsPermissions.CONNECTIONS_PERSONAL)
        }
        return user to Owner.Personal(id, user.displayName)
    }

    // ---- platform and project connections (ADR 0030)

    override fun listPlatform(): List<Connection> {
        gate.require(ProjectsPermissions.INTEGRATIONS_VIEW)
        return platformCandidates()
    }

    override fun listProject(projectId: ProjectId): List<Connection> {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        loadProject(projectId)
        return connections.findByProject(projectId).sortedBy { it.name.lowercase() }
    }

    override fun usable(projectId: ProjectId): List<Connection> {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        loadProject(projectId)
        return selector.order(connections.findByProject(projectId), platformCandidates())
    }

    override fun get(projectId: ProjectId?, id: ConnectionId): Connection {
        if (projectId == null) gate.require(ProjectsPermissions.INTEGRATIONS_VIEW) else gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        return load(owner(projectId), id, allowLegacy = true)
    }

    override fun create(projectId: ProjectId?, command: ConnectionCommand): Connection {
        val owner = owner(projectId)
        val actor = requireManage(owner).actor
        projectId?.let(::loadProject)
        return doCreate(owner, command, actor)
    }

    override fun update(projectId: ProjectId?, id: ConnectionId, command: ConnectionCommand): Connection {
        val owner = owner(projectId)
        val actor = requireManage(owner).actor
        return doUpdate(owner, id, command, actor)
    }

    override fun rotateSecret(projectId: ProjectId?, id: ConnectionId, secret: String, expiresAt: LocalDate?): Connection {
        val owner = owner(projectId)
        val actor = requireManage(owner).actor
        return doRotate(owner, id, secret, expiresAt, actor)
    }

    override fun delete(projectId: ProjectId?, id: ConnectionId) {
        val owner = owner(projectId)
        requireManage(owner)
        doDelete(owner, id)
    }

    override fun verify(projectId: ProjectId?, id: ConnectionId, sampleProjectKey: String?): Connection {
        val owner = owner(projectId)
        requireManage(owner)
        val connection = load(owner, id, allowLegacy = projectId == null)
        return verifyAndStore(connection, sample(sampleProjectKey))
    }

    override fun test(projectId: ProjectId?, command: ConnectionCommand, existingId: ConnectionId?, sampleProjectKey: String?): ConnectionVerification {
        val owner = owner(projectId)
        requireManage(owner)
        projectId?.let(::loadProject)
        return doTest(owner, command, existingId, sampleProjectKey)
    }

    // ---- personal connections (ADR 0035)

    override fun listMine(): List<Connection> {
        val (_, owner) = me()
        return connections.findByOwner(owner.userId).filter { it.ownedBy(owner.userId) }.sortedBy { it.name.lowercase() }
    }

    override fun getMine(id: ConnectionId): Connection {
        val (_, owner) = me()
        return load(owner, id, allowLegacy = false)
    }

    override fun createMine(command: ConnectionCommand): Connection {
        val (user, owner) = me()
        return doCreate(owner, command, user.actor)
    }

    override fun updateMine(id: ConnectionId, command: ConnectionCommand): Connection {
        val (user, owner) = me()
        return doUpdate(owner, id, command, user.actor)
    }

    override fun rotateMine(id: ConnectionId, secret: String, expiresAt: LocalDate?): Connection {
        val (user, owner) = me()
        return doRotate(owner, id, secret, expiresAt, user.actor)
    }

    override fun deleteMine(id: ConnectionId) {
        val (_, owner) = me()
        doDelete(owner, id)
    }

    override fun verifyMine(id: ConnectionId, sampleProjectKey: String?): Connection {
        val (_, owner) = me()
        return verifyAndStore(load(owner, id, allowLegacy = false), sample(sampleProjectKey))
    }

    override fun testMine(command: ConnectionCommand, existingId: ConnectionId?, sampleProjectKey: String?): ConnectionVerification {
        val (_, owner) = me()
        return doTest(owner, command, existingId, sampleProjectKey)
    }

    override fun listAllPersonal(): List<Connection> {
        gate.require(ProjectsPermissions.CONNECTIONS_MANAGE)
        return connections.findAll().filter { it.personal }.sortedWith(compareBy({ it.ownerName?.lowercase() }, { it.name.lowercase() }))
    }

    // ---- the project's system connection and list (ADR 0035)

    override fun jiraConnections(projectId: ProjectId): ProjectJiraConnectionsView {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        return view(loadProject(projectId))
    }

    override fun updateJiraConnections(projectId: ProjectId, settings: ProjectJiraConnections): ProjectJiraConnectionsView {
        gate.require(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, projectId.value)
        val project = loadProject(projectId)
        val available = available(projectId).associateBy { it.id }
        settings.list().firstOrNull { it !in available }?.let { unknown ->
            throw ValidationException(
                "connection $unknown is not a connection of this project or of the platform (personal connections cannot be chosen)",
                if (unknown == settings.systemConnectionId) "systemConnectionId" else "additionalConnectionIds",
            )
        }
        val saved = projects.save(project.withJiraConnections(settings, clock.now()))
        val names = { ids: List<ConnectionId> -> ids.map { available[it]?.name ?: it.toString() } }
        val before = project.jiraConnections
        val changes = AuditableChanges.changed(
            "systemConnection" to (before.systemConnectionId?.let { available[it]?.name ?: it.toString() } to settings.systemConnectionId?.let { available[it]?.name }),
            "additionalConnections" to (names(before.additionalConnectionIds) to names(settings.additionalConnectionIds)),
        )
        if (changes.isNotEmpty()) {
            audit.record(AuditEntry(JIRA_CONNECTIONS_UPDATE, "Project", projectId.toString(), projectId = projectId.value, details = changes))
        }
        return view(saved)
    }

    private fun available(projectId: ProjectId): List<Connection> = selector.order(connections.findByProject(projectId), platformCandidates())

    private fun view(project: Project): ProjectJiraConnectionsView {
        val available = available(project.id)
        val byId = available.associateBy { it.id }
        val settings = project.jiraConnections
        val system = settings.systemConnectionId?.let { byId[it] }
        val additional = settings.additionalConnectionIds.mapNotNull { byId[it] }
        val cleaned = ProjectJiraConnections(system?.id, additional.map { it.id }.filterNot { it == system?.id })
        return ProjectJiraConnectionsView(cleaned, system, additional, available)
    }

    // ---- reminders, resolution, re-wrap

    @PublicOperation("lists only connections the caller may manage (admin.connections.manage / project.connections.manage / own personal ones)")
    override fun expiring(): List<Connection> {
        val user = gate.currentUser()
        val now = clock.now()
        val horizon = now.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(Connection.EXPIRY_REMINDER_DAYS)
        return connections.findAll()
            .filter { c -> c.secretExpiresAt?.let { !it.isAfter(horizon) } == true }
            .filter { c ->
                val project = c.projectId
                when {
                    c.personal -> c.ownedBy(user.userId?.value)
                    project == null -> user.can(ProjectsPermissions.CONNECTIONS_MANAGE)
                    else -> user.can(ProjectsPermissions.PROJECT_CONNECTIONS_MANAGE, project.value)
                }
            }
            .sortedWith(compareBy({ it.secretExpiresAt }, { it.name.lowercase() }))
    }

    @SystemOperation("daily connection expiry reminder (ShedLock singleton); publishes names and dates only")
    override fun remindExpiring(): Int {
        val now = clock.now()
        var count = 0
        connections.findAll().filter { it.needsExpiryReminder(now) }.forEach { connection ->
            val expires = connection.secretExpiresAt ?: return@forEach
            val daysLeft = connection.daysUntilExpiry(now) ?: return@forEach
            connections.markExpiryReminded(connection.id, expires)
            events.publish(
                ConnectionExpiring(connection.id.toString(), connection.projectId, connection.name, connection.scope.name, expires, daysLeft, now, connection.ownerUserId),
            )
            audit.record(
                AuditEntry(
                    EXPIRY_REMINDER, ENTITY, connection.id.toString(), projectId = connection.projectId?.value, actor = Actor.SYSTEM,
                    details = mapOf("name" to connection.name, "secretExpiresAt" to expires.toString(), "daysLeft" to daysLeft),
                ),
            )
            count++
        }
        return count
    }

    override fun resolve(projectId: ProjectId, capabilities: Set<ConnectionCapability>, pinned: ConnectionId?, jiraProjectKey: String?): ConnectionChoice {
        gate.require(ProjectsPermissions.PROJECT_VIEW, projectId.value)
        val project = loadProject(projectId)
        if (capabilities.isEmpty()) throw ValidationException("name at least one capability", "capabilities")
        val key = jiraProjectKey?.trim()?.uppercase()?.ifEmpty { null } ?: project.jiraProjectKey
        val projectConnections = connections.findByProject(projectId)
        return if (pinned != null) {
            selector.selectPinned(capabilities, projectConnections, platformCandidates(), key, pinned)
        } else {
            selector.selectSystem(capabilities, project.jiraConnections, projectConnections, platformCandidates(), key)
        }
    }

    @SystemOperation("startup job of the worker role after a key rotation; changes no plaintext and no permissions")
    override fun rewrapSecrets(): Int {
        if (!sealer.configured) return 0
        var count = 0
        connections.findAll().forEach { connection ->
            val sealed = connection.secret ?: return@forEach
            // a secret that cannot be opened (unknown key, recovery mode) stays as it is until its token is replaced
            val rewrapped = runCatching { sealer.rewrap(sealed, Connection.secretAssociatedData(connection.id)) }.getOrNull() ?: return@forEach
            if (connections.replaceSecret(connection.id, sealed, rewrapped)) {
                count++
                audit.record(
                    AuditEntry(
                        REWRAP, ENTITY, connection.id.toString(), projectId = connection.projectId?.value, actor = Actor.SYSTEM,
                        details = mapOf("name" to connection.name, "fromKeyId" to sealed.keyId, "toKeyId" to rewrapped.keyId),
                    ),
                )
            }
        }
        return count
    }

    // ---- shared implementation

    private fun doCreate(owner: Owner, command: ConnectionCommand, actor: Actor): Connection {
        requireSealer()
        val secret = command.secret?.trim()?.takeIf { it.isNotEmpty() } ?: throw ValidationException("is required", "secret")
        requireUniqueName(owner, command.name, null)
        val token = tokenSettings(command, null)
        val now = clock.now()
        val id = ConnectionId.new()
        val connection = Connection(
            id = id,
            scope = owner.scope,
            projectId = owner.projectId,
            type = command.type,
            name = command.name.trim(),
            siteUrl = Connection.normalizeSiteUrl(command.siteUrl),
            accountEmail = command.accountEmail.trim(),
            capabilities = command.capabilities,
            allowedProjectKeys = Connection.normalizeKeys(command.allowedProjectKeys),
            verifyProjectKey = command.verifyProjectKey?.trim()?.uppercase()?.ifEmpty { null },
            secret = sealer.seal(secret, Connection.secretAssociatedData(id)),
            secretRotatedAt = now,
            secretExpiresAt = command.secretExpiresAt,
            createdAt = now,
            createdBy = actor,
            updatedAt = now,
            updatedBy = actor,
            ownerUserId = owner.userId,
            ownerName = (owner as? Owner.Personal)?.name,
            tokenKind = token.kind,
            cloudId = token.cloudId,
            tokenScopes = token.scopes,
            classicTokenAcknowledged = token.acknowledged,
        )
        val saved = connections.save(connection)
        audit.record(AuditEntry(CREATE, ENTITY, id.toString(), projectId = owner.projectId?.value, details = describe(saved)))
        return verifyAndStore(saved, null)
    }

    private fun doUpdate(owner: Owner, id: ConnectionId, command: ConnectionCommand, actor: Actor): Connection {
        val existing = load(owner, id, allowLegacy = false)
        if (command.type != existing.type) throw ValidationException("the type of a connection cannot be changed", "type")
        requireUniqueName(owner, command.name, id)
        val token = tokenSettings(command, existing)
        val now = clock.now()
        var updated = existing.update(
            command.name, command.siteUrl, command.accountEmail, command.capabilities, command.allowedProjectKeys,
            command.verifyProjectKey, command.secretExpiresAt, actor, now,
            tokenKind = token.kind, cloudId = token.cloudId, tokenScopes = token.scopes, classicTokenAcknowledged = token.acknowledged,
        )
        val newSecret = command.secret?.trim()?.takeIf { it.isNotEmpty() }
        if (newSecret != null) {
            requireSealer()
            updated = updated.rotate(sealer.seal(newSecret, Connection.secretAssociatedData(id)), command.secretExpiresAt, actor, now)
        }
        val saved = connections.save(updated)
        val changes = AuditableChanges.changed(
            "name" to (existing.name to saved.name),
            "siteUrl" to (existing.siteUrl to saved.siteUrl),
            "accountEmail" to (existing.accountEmail to saved.accountEmail),
            "capabilities" to (existing.capabilities.sorted().map { it.name } to saved.capabilities.sorted().map { it.name }),
            "allowedProjectKeys" to (existing.allowedProjectKeys to saved.allowedProjectKeys),
            "verifyProjectKey" to (existing.verifyProjectKey to saved.verifyProjectKey),
            "secretExpiresAt" to (existing.secretExpiresAt?.toString() to saved.secretExpiresAt?.toString()),
            "tokenKind" to (existing.tokenKind.name to saved.tokenKind.name),
            "cloudId" to (existing.cloudId to saved.cloudId),
            "tokenScopes" to (existing.tokenScopes to saved.tokenScopes),
            "classicTokenAcknowledged" to (existing.classicTokenAcknowledged to saved.classicTokenAcknowledged),
        ) + (if (newSecret != null) mapOf("secret" to "replaced", "keyId" to saved.secret?.keyId) else emptyMap())
        if (changes.isNotEmpty()) {
            audit.record(
                AuditEntry(UPDATE, ENTITY, id.toString(), projectId = owner.projectId?.value, details = mapOf("connection" to saved.name, "scope" to saved.scope.name) + changes),
            )
        }
        return verifyAndStore(saved, null)
    }

    private fun doRotate(owner: Owner, id: ConnectionId, secret: String, expiresAt: LocalDate?, actor: Actor): Connection {
        val existing = load(owner, id, allowLegacy = false)
        requireSealer()
        val plaintext = secret.trim().takeIf { it.isNotEmpty() } ?: throw ValidationException("is required", "secret")
        val saved = connections.save(existing.rotate(sealer.seal(plaintext, Connection.secretAssociatedData(id)), expiresAt, actor, clock.now()))
        audit.record(
            AuditEntry(
                ROTATE, ENTITY, id.toString(), projectId = owner.projectId?.value,
                details = mapOf("name" to saved.name, "scope" to saved.scope.name, "keyId" to saved.secret?.keyId, "secretExpiresAt" to saved.secretExpiresAt?.toString()),
            ),
        )
        return verifyAndStore(saved, null)
    }

    private fun doDelete(owner: Owner, id: ConnectionId) {
        val existing = load(owner, id, allowLegacy = false)
        // ADR 0035: a deleted connection leaves the system connection and the lists of the projects that named it
        val affected = when (owner) {
            is Owner.OfProject -> listOfNotNull(projects.findById(owner.projectId))
            Owner.Platform -> projects.findAll(ProjectFilter.All)
            is Owner.Personal -> emptyList()
        }.filter { id in it.jiraConnections.list() }
        affected.forEach { project ->
            projects.save(project.withJiraConnections(project.jiraConnections.without(id), clock.now()))
            audit.record(
                AuditEntry(
                    JIRA_CONNECTIONS_UPDATE, "Project", project.id.toString(), projectId = project.id.value,
                    details = mapOf("removedConnection" to existing.name, "reason" to "connection deleted",
                        "systemConnectionCleared" to (project.jiraConnections.systemConnectionId == id)),
                ),
            )
        }
        connections.delete(id)
        audit.record(AuditEntry(DELETE, ENTITY, id.toString(), projectId = owner.projectId?.value, details = describe(existing)))
    }

    private fun doTest(owner: Owner, command: ConnectionCommand, existingId: ConnectionId?, sampleProjectKey: String?): ConnectionVerification {
        val existing = existingId?.let { load(owner, it, allowLegacy = false) }
        if (existing != null && command.type != existing.type) throw ValidationException("the type of a connection cannot be changed", "type")
        val plaintext = command.secret?.trim()?.takeIf { it.isNotEmpty() }
        // the draft only lives in memory: a fresh id binds the in-memory seal, nothing is stored
        val id = existing?.id ?: ConnectionId.new()
        val secret = when {
            plaintext != null -> {
                requireSealer()
                sealer.seal(plaintext, Connection.secretAssociatedData(id))
            }
            existing?.secret != null -> existing.secret
            else -> throw ValidationException("is required to test a new connection", "secret")
        }
        // a test does not need the classic acknowledgement: it stores nothing
        val token = tokenSettings(command.copy(acknowledgeClassicToken = true), existing)
        val draft = Connection(
            id = id,
            scope = owner.scope,
            projectId = owner.projectId,
            type = command.type,
            name = command.name.trim().ifEmpty { "draft" },
            siteUrl = Connection.normalizeSiteUrl(command.siteUrl),
            accountEmail = command.accountEmail.trim(),
            capabilities = command.capabilities,
            allowedProjectKeys = Connection.normalizeKeys(command.allowedProjectKeys),
            verifyProjectKey = command.verifyProjectKey?.trim()?.uppercase()?.ifEmpty { null },
            secret = secret,
            ownerUserId = owner.userId,
            ownerName = (owner as? Owner.Personal)?.name,
            tokenKind = token.kind,
            cloudId = token.cloudId,
            tokenScopes = token.scopes,
            classicTokenAcknowledged = token.acknowledged,
        )
        val result = check(draft, sample(sampleProjectKey))
        audit.record(
            AuditEntry(
                TEST, ENTITY, existing?.id?.toString(), projectId = owner.projectId?.value,
                details = mapOf(
                    "name" to draft.name, "scope" to draft.scope.name, "siteUrl" to draft.siteUrl, "accountEmail" to draft.accountEmail,
                    "capabilities" to draft.capabilities.sorted().map { it.name }, "allowedProjectKeys" to draft.allowedProjectKeys,
                    "tokenKind" to draft.tokenKind.name, "tokenScopes" to draft.tokenScopes,
                    "secret" to if (plaintext != null) "draft" else "stored", "status" to result.status.name,
                    "missing" to result.missing.map { it.name }, "excess" to result.excess.map { it.name },
                ),
            ),
        )
        return result
    }

    private data class TokenSettings(val kind: JiraTokenKind, val cloudId: String?, val scopes: List<String>, val acknowledged: Boolean)

    /**
     * ADR 0035: a scoped token gets its cloud id (given, kept when the site is unchanged, or discovered from
     * `<site>/_edge/tenant_info`) and its declared scopes; a classic token needs the acknowledgement (given now, or
     * already stored for a classic token).
     */
    private fun tokenSettings(command: ConnectionCommand, existing: Connection?): TokenSettings = when (command.tokenKind) {
        JiraTokenKind.SCOPED -> {
            val site = Connection.normalizeSiteUrl(command.siteUrl)
            val given = command.cloudId?.trim()?.lowercase()?.ifEmpty { null }
            val kept = existing?.takeIf { it.tokenKind == JiraTokenKind.SCOPED && it.siteUrl == site }?.cloudId
            val cloudId = given ?: kept ?: try {
                trackers.discoverCloudId(site)
            } catch (e: IntegrationException) {
                throw ValidationException("the cloud id of $site could not be discovered (${e.message}); enter it (from $site/_edge/tenant_info)", "cloudId")
            }
            val scopes = Connection.normalizeScopes(command.tokenScopes)
            if (scopes.isEmpty()) throw ValidationException("list the scopes chosen when the scoped token was created", "tokenScopes")
            TokenSettings(JiraTokenKind.SCOPED, cloudId, scopes, false)
        }
        JiraTokenKind.CLASSIC -> {
            val stored = existing?.takeIf { it.tokenKind == JiraTokenKind.CLASSIC }?.classicTokenAcknowledged == true
            if (!command.acknowledgeClassicToken && !stored) {
                throw ValidationException(
                    "confirm that a classic token can do everything its account can (Back Office limits each step to its capability); " +
                        "or use a scoped token (manual: integrations-jira#jira-token)",
                    "acknowledgeClassicToken",
                )
            }
            TokenSettings(JiraTokenKind.CLASSIC, null, emptyList(), true)
        }
    }

    private fun sample(sampleProjectKey: String?): String? {
        val sample = sampleProjectKey?.trim()?.uppercase()?.ifEmpty { null }
        if (sample != null && !Connection.JIRA_KEY.matches(sample)) throw ValidationException("is not a Jira project key", "projectKey")
        return sample
    }

    private fun platformCandidates(): List<Connection> =
        connections.findPlatform().filterNot { it.personal }.sortedBy { it.name.lowercase() } + listOfNotNull(legacy.legacy())

    private fun verifyAndStore(connection: Connection, sample: String?): Connection {
        val result = check(connection, sample)
        val stored = if (connection.legacy) connection.verified(result) else connections.save(connection.verified(result))
        audit.record(
            AuditEntry(
                VERIFY, ENTITY, connection.id.toString(), projectId = connection.projectId?.value,
                details = mapOf(
                    "name" to connection.name, "scope" to connection.scope.name, "status" to result.status.name, "account" to result.account?.displayName,
                    "projects" to result.projects.map { it.projectKey }, "missing" to result.missing.map { it.name }, "excess" to result.excess.map { it.name },
                ),
            ),
        )
        return stored
    }

    private fun check(connection: Connection, sample: String?): ConnectionVerification {
        val probe = try {
            trackers.probe(connection, connection.keysToVerify(sample))
        } catch (e: RuntimeException) {
            JiraProbe(null, ProbeFailure(VerificationCode.HTTP_ERROR, "verification failed: ${e.message ?: e.javaClass.simpleName}"))
        }
        return verificationPolicy.evaluate(connection.capabilities, probe, clock.now(), connection.tokenKind, connection.tokenScopes)
    }

    private fun requireSealer() {
        if (!sealer.configured) {
            throw InvalidStateException(
                "BACKOFFICE_SECRETS_KEY is not configured, so connection secrets cannot be stored; set it (base64 of 32 random bytes, " +
                    "docs/guides/configuration.md) and restart",
            )
        }
    }

    private fun siblings(owner: Owner): List<Connection> = when (owner) {
        Owner.Platform -> connections.findPlatform().filterNot { it.personal }
        is Owner.OfProject -> connections.findByProject(owner.projectId)
        is Owner.Personal -> connections.findByOwner(owner.userId)
    }

    private fun requireUniqueName(owner: Owner, name: String, except: ConnectionId?) {
        if (siblings(owner).any { it.id != except && it.name.equals(name.trim(), ignoreCase = true) }) {
            throw ValidationException("a connection named ${name.trim()} already exists here", "name")
        }
    }

    /** Loads a connection and checks it belongs where the caller says (the platform, that project, or the caller). */
    private fun load(owner: Owner, id: ConnectionId, allowLegacy: Boolean): Connection {
        if (id == ConnectionId.LEGACY_JIRA) {
            val connection = legacy.legacy()?.takeIf { owner == Owner.Platform } ?: throw NotFoundException(ENTITY, id)
            if (!allowLegacy) throw InvalidStateException("the legacy Jira connection comes from JIRA_* environment variables and is read-only")
            return connection
        }
        val connection = connections.findById(id) ?: throw NotFoundException(ENTITY, id)
        // a personal connection is invisible everywhere but to its owner (404, not 403: its existence is not revealed)
        val belongs = connection.scope == owner.scope && connection.projectId == owner.projectId &&
            (owner !is Owner.Personal || connection.ownedBy(owner.userId))
        if (!belongs) throw NotFoundException(ENTITY, id)
        return connection
    }

    private fun loadProject(id: ProjectId): Project = projects.findById(id) ?: throw NotFoundException("Project", id)

    private fun describe(c: Connection): Map<String, Any?> = mapOf(
        "name" to c.name, "scope" to c.scope.name, "type" to c.type.name, "siteUrl" to c.siteUrl, "accountEmail" to c.accountEmail,
        "capabilities" to c.capabilities.sorted().map { it.name }, "allowedProjectKeys" to c.allowedProjectKeys,
        "secretExpiresAt" to c.secretExpiresAt?.toString(), "keyId" to c.secret?.keyId,
        "tokenKind" to c.tokenKind.name, "cloudId" to c.cloudId, "tokenScopes" to c.tokenScopes,
        "owner" to c.ownerName, "ownerUserId" to c.ownerUserId?.toString(),
    )

    companion object {
        const val ENTITY = "Connection"
        const val CREATE = "CONNECTION_CREATE"
        const val UPDATE = "CONNECTION_UPDATE"
        const val ROTATE = "CONNECTION_ROTATE"
        const val DELETE = "CONNECTION_DELETE"
        const val VERIFY = "CONNECTION_VERIFY"
        const val REWRAP = "CONNECTION_SECRET_REWRAP"
        const val TEST = "CONNECTION_TEST"
        const val EXPIRY_REMINDER = "CONNECTION_EXPIRY_REMINDER"
        const val JIRA_CONNECTIONS_UPDATE = "PROJECT_JIRA_CONNECTIONS_UPDATE"
    }
}
