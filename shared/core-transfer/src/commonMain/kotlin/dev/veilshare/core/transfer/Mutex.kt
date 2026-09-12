package dev.veilshare.core.transfer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Mutex as MutexImpl

typealias Mutex = kotlinx.coroutines.sync.Mutex

fun newMutex(): Mutex = Mutex()