/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.interactors

import androidx.paging.PagedList
import app.tivi.util.ObservableLoadingCounter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

interface Interactor<in P> {
    val dispatcher: CoroutineDispatcher
    suspend operator fun invoke(params: P)
}

interface ObservableInteractor<in P, T> : Interactor<P> {
    fun observe(): Flow<T>
}

abstract class PagingInteractor<P : PagingInteractor.Parameters<T>, T> : SubjectInteractor<P, PagedList<T>>() {
    interface Parameters<T> {
        val pagingConfig: PagedList.Config
        val boundaryCallback: PagedList.BoundaryCallback<T>?
    }
}

abstract class SuspendingWorkInteractor<P : Any, T : Any> : ObservableInteractor<P, T> {
    private val channel = ConflatedBroadcastChannel<T>()

    override suspend operator fun invoke(params: P) = channel.send(doWork(params))

    abstract suspend fun doWork(params: P): T

    override fun observe(): Flow<T> = channel.asFlow().distinctUntilChanged()
}

abstract class SubjectInteractor<P : Any, T> : ObservableInteractor<P, T> {
    private val channel = ConflatedBroadcastChannel<P>()

    override suspend operator fun invoke(params: P) = channel.send(params)

    protected abstract fun createObservable(params: P): Flow<T>

    override fun observe(): Flow<T> = channel.asFlow()
            .distinctUntilChanged()
            .flatMapLatest { createObservable(it) }
}

fun <P> CoroutineScope.launchInteractor(
    interactor: Interactor<P>,
    param: P,
    loadingCounter: ObservableLoadingCounter? = null
): Job {
    val loadingCounterWeakRef = loadingCounter?.let { WeakReference(it) }
    return launch(context = interactor.dispatcher) {
        loadingCounterWeakRef?.get()?.addLoader()
        interactor(param)
        loadingCounterWeakRef?.get()?.removeLoader()
    }
}

suspend fun <P> Interactor<P>.execute(
    param: P,
    loadingCounter: ObservableLoadingCounter? = null
) {
    val loadingCounterWeakRef = loadingCounter?.let { WeakReference(it) }
    withContext(context = dispatcher) {
        loadingCounterWeakRef?.get()?.addLoader()
        invoke(param)
        loadingCounterWeakRef?.get()?.removeLoader()
    }
}

fun CoroutineScope.launchInteractor(
    interactor: Interactor<Unit>,
    loadingCounter: ObservableLoadingCounter? = null
) = launchInteractor(interactor, Unit, loadingCounter)

fun <I : ObservableInteractor<*, T>, T> CoroutineScope.launchObserve(
    interactor: I,
    f: suspend (Flow<T>) -> Unit
) {
    launch(interactor.dispatcher) {
        f(interactor.observe())
    }
}