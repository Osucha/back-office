package pl.mos.backoffice.tasks.adapter.config

import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.tasks.application.port.`in`.ManageSkills
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskInterfaces
import pl.mos.backoffice.tasks.application.port.`in`.ManageTaskTemplates

/*
 * Transaction boundaries of the Task Library use cases. The core services are plain Kotlin; these
 * delegating wrappers give every use-case call one transaction, so the change and its audit entry
 * (`AuditPort` writes in the caller's transaction, ADR 0018) commit or roll back together.
 */

@Transactional
class TransactionalManageTaskTemplates(delegate: ManageTaskTemplates) : ManageTaskTemplates by delegate

@Transactional
class TransactionalManageTaskInterfaces(delegate: ManageTaskInterfaces) : ManageTaskInterfaces by delegate

@Transactional
class TransactionalManageSkills(delegate: ManageSkills) : ManageSkills by delegate
