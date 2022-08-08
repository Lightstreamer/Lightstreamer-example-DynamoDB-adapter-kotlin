package server

import com.lightstreamer.interfaces.data.DataProvider
import com.lightstreamer.interfaces.data.ItemEventListener
import demo.departureStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import kotlin.coroutines.CoroutineContext

class DemoDepartureProvider : DataProvider, CoroutineScope {

    override val coroutineContext: CoroutineContext = Job()

    private lateinit var listener: ItemEventListener

    private var subscriptionJobs: MutableMap<String, Job> = Collections.synchronizedMap(HashMap())

    override fun init(params: MutableMap<Any?, Any?>, configDir: File) {
    }

    override fun setListener(listener: ItemEventListener) {
        this.listener = listener
    }

    override fun isSnapshotAvailable(itemName: String): Boolean = true

    override fun subscribe(itemName: String, needsIterator: Boolean) {
        subscriptionJobs[itemName] = launch {
            var oldData: List<Map<String, String>>? = null
            departureStateFlow.collect { newData ->
                sendUpdates(itemName, newData, oldData)
                oldData = newData
            }
        }
    }

    override fun unsubscribe(itemName: String) {
        subscriptionJobs.remove(itemName)?.cancel()
    }

    private fun sendUpdates(itemName: String, newData: List<Map<String, String>>, oldData: List<Map<String, String>>?) {
        val oldKeys: Set<String> = oldData?.map { it.getValue("flight") }?.toSet().orEmpty()
        val newKeys: Set<String> = newData.map { it.getValue("flight") }.toSet()
        val keysToRemove = oldKeys - newKeys
        if (keysToRemove.size == oldKeys.size) {
            if (oldData != null) listener.clearSnapshot(itemName)
        } else {
            // remove old rows
            for (key in keysToRemove) {
                listener.update(itemName, mapOf("key" to key, "command" to "DELETE"), oldData == null)
            }
        }

        for (newLine in newData) {
            val flight = newLine.getValue("flight")
            val oldLine = oldData?.find { it["flight"] == flight }
            if (newLine != oldLine) {
                val command = if (oldLine == null) "ADD" else "UPDATE"
                val update = newLine + mapOf("key" to flight, "command" to command)
                listener.update(itemName, update, oldData == null)
            }
        }
        if (oldData == null) listener.endOfSnapshot(itemName)
    }
}
