/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kotlin.coroutine

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelFutureOnCancellation
import kotlinx.coroutines.cancelFutureOnCompletion
import kotlinx.coroutines.newCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.springframework.util.concurrent.ListenableFuture
import org.springframework.util.concurrent.ListenableFutureCallback
import org.springframework.util.concurrent.SettableListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine

/**
 * Starts new coroutine and returns its results an an implementation of [ListenableFuture].
 * This coroutine builder uses [DefaultDispatcher] context by default and is conceptually
 * similar to [CompletableFuture.supplyAsync].
 *
 * The running coroutine is cancelled when the resulting future is cancelled or otherwise completed.
 * If the [context] for the new coroutine is explicitly specified, then it must include [CoroutineDispatcher] element.
 * See [CoroutineDispatcher] for the standard [context] implementations that are provided by `kotlinx.coroutines`.
 * The specified context is added to the context of the parent running coroutine (if any)
 * inside which this function is invoked. The [Job] of the resulting coroutine is
 * a child of the job of the parent coroutine (if any).
 *
 * See [newCoroutineContext] for a description of debugging facilities that are
 * available for newly created coroutine.
 *
 * @author Konrad Kamiński
 * @author Roman Elizarov
 * @since 5.0
 */
@UseExperimental(InternalCoroutinesApi::class)
fun <T> listenableFuture(context: CoroutineContext = Dispatchers.Default, block: suspend () -> T): ListenableFuture<T> {
    val newContext = GlobalScope.newCoroutineContext(Dispatchers.Default + context)
    val job = Job(newContext[Job])

    return ListenableFutureCoroutine<T>(newContext + job).apply {
        job.cancelFutureOnCompletion(this)
        addCallback(job.asJobCancellingCallback())
        block.startCoroutine(this)
    }
}

/**
 * Converts this deferred value to the instance of [ListenableFuture].
 * The deferred value is cancelled when the resulting future is cancelled or otherwise completed.
 *
 * @author Konrad Kamiński
 * @author Roman Elizarov
 * @since 5.0
 */
fun <T> Deferred<T>.asListenableFuture(): ListenableFuture<T> =
        SettableListenableFuture<T>().apply {
            addCallback(this@asListenableFuture.asJobCancellingCallback())

            invokeOnCompletion {
                try {
                    set(getCompleted())
                }
                catch (exception: Exception) {
                    setException(exception)
                }
            }
        }

/**
 * Awaits for completion of the future without blocking a thread.
 *
 * This suspending function is cancellable.
 * If the [Job] of the current coroutine is completed while this suspending function is waiting,
 * this function immediately resumes with [CancellationException] .
 *
 * @author Konrad Kamiński
 * @author Roman Elizarov
 * @since 5.0
 */
suspend fun <T> ListenableFuture<T>.await(): T =
        if (isDone) {
            try {
                get()
            }
            catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } else {
            suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
                addCallback(object: ListenableFutureCallback<T> {
                    override fun onFailure(exception: Throwable) = cont.resumeWithException(exception)
                    override fun onSuccess(result: T) = cont.resume(result)
                })
                cont.cancelFutureOnCancellation(this)
            }
        }

private class ListenableFutureCoroutine<T>(
        override val context: CoroutineContext
): SettableListenableFuture<T>(), Continuation<T> {

    override fun resumeWith(result: Result<T>) {
        if (result.isSuccess) {
            set(result.getOrNull())
        } else {
            setException(result.exceptionOrNull()!!)
        }
    }
}

    private fun <T> Job.asJobCancellingCallback(): ListenableFutureCallback<T> =
            object : ListenableFutureCallback<T> {
                override fun onFailure(exception: Throwable) {
                    cancel(CancellationException(exception.message))
                }

                override fun onSuccess(result: T) {}
            }

