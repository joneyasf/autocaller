package com.example.autocaller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autocaller.data.AppDatabase
import com.example.autocaller.data.ScheduledCall
import com.example.autocaller.scheduler.AlarmScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).callDao()

    /** Sorted by next trigger time; calls with no upcoming time go last. */
    val calls: StateFlow<List<ScheduledCall>> = dao.observeAll()
        .map { list ->
            list.sortedWith(
                compareBy<ScheduledCall> { AlarmScheduler.nextTriggerMillis(it) ?: Long.MAX_VALUE }
                    .thenBy { it.id }
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(call: ScheduledCall) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val id = if (call.id == 0L) {
            dao.insert(call)
        } else {
            dao.update(call)
            call.id
        }
        AlarmScheduler.schedule(ctx, call.copy(id = id))
    }

    fun setEnabled(call: ScheduledCall, enabled: Boolean) = viewModelScope.launch {
        // Toggling re-arms the call as a fresh cycle: reset the retry counter.
        val updated = call.copy(enabled = enabled, attemptCount = 0)
        dao.update(updated)
        AlarmScheduler.schedule(getApplication(), updated)
    }

    fun delete(call: ScheduledCall) = viewModelScope.launch {
        AlarmScheduler.cancel(getApplication(), call.id)
        dao.delete(call)
    }

    fun rescheduleAll() = viewModelScope.launch {
        AlarmScheduler.rescheduleAll(getApplication())
    }
}
