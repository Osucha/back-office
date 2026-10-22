package pl.mos.backoffice.shared.runner

/**
 * ADR 0051: tokens of project repositories, across contexts. The projects context resolves which token a job gets
 * ([JobCredentialRef] in the signed spec); the runner context releases it sealed to the runner holding the lease and
 * needs the plaintext only at that moment.
 */
fun interface RepositoryTokenSource {
    /** The token named by [ref], or null when it no longer exists or cannot be opened. */
    fun open(ref: String): String?
}
