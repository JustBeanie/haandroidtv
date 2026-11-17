package dev.haquickaccess.tv.platform

import kotlinx.coroutines.sync.Mutex

/** Serializes TV provider changes and their persisted channel identity across UI and ADB. */
internal object HomeChannelTransactions {
    val mutex = Mutex()
}
