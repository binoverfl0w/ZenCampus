package io.github.binoverfl0w.zencampus.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val storageDispatcher: CoroutineDispatcher = Dispatchers.IO
