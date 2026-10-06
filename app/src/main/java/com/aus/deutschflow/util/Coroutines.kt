package com.aus.deutschflow.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cancels [scope]'s job and awaits completion of its children, bounded by [timeoutMillis].
 *
 * Cancellation and the await happen in the *caller's* coroutine - a separate, active
 * scope - never inside [scope] itself: a coroutine torn down along with its own scope
 * can never reach completion, so it could never join. This corrects the previous
 * "cancel then poll isActive" teardown, which returned the instant cancellation was
 * *requested* - i.e. while in-flight children (a DataStore reader/migration) were still
 * running their cleanup.
 *
 * A DataStore keeps its active reader and migrations attached to the scope passed to
 * create(); the file behind it must not be deleted or reopened by another owner until
 * that scope has truly drained, or the two race and the "multiple DataStores per file"
 * crash - or a truncated write - surfaces on whatever test runs next.
 *
 * @return true once the job has completed within the timeout; false if children were
 *         still running when [timeoutMillis] elapsed. Callers MUST treat false as a hard
 *         failure and leave the guarded resource untouched - never delete it as a
 *         "close enough" cleanup.
 */
internal suspend fun cancelAndDrain(scope: CoroutineScope, timeoutMillis: Long): Boolean {
    val job = scope.coroutineContext[Job] ?: return true
    return withTimeoutOrNull(timeoutMillis) {
        job.cancel()
        job.join()
        true
    } ?: false
}
