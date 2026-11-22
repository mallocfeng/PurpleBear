package com.mallocgfw.app.ui

import com.mallocgfw.app.core.AppController
import com.mallocgfw.app.model.PersistedAppState
import kotlin.reflect.KProperty

/**
 * A piece of the saved state, read from [AppController] (so composables
 * recompose when it changes, whoever changed it) and written back through it.
 */
internal class AppStateField<T>(
    private val read: (PersistedAppState) -> T,
    private val write: (PersistedAppState, T) -> PersistedAppState,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read(AppController.observed())

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        AppController.edit { write(it, value) }
    }
}

/** A read-only piece of the saved state; change it with [AppController.edit]. */
internal class AppStateView<T>(private val read: (PersistedAppState) -> T) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = read(AppController.observed())
}
