package server

import com.lightstreamer.interfaces.data.DataProvider
import com.lightstreamer.interfaces.data.ItemEventListener
import demo.dataStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import kotlin.coroutines.CoroutineContext

class DemoDataProvider : DataProvider, CoroutineScope {

    override val coroutineContext: CoroutineContext = Job()

    private lateinit var listener: ItemEventListener

    private var subscriptionJobs: MutableMap<String, Job> = Collections.synchronizedMap(HashMap())

    override fun init(params: MutableMap<Any?, Any?>, configDir: File) {
    }

    override fun setListener(listener: ItemEventListener) {
        this.listener = listener
    }

    override fun isSnapshotAvailable(itemName: String): Boolean = false

    override fun subscribe(itemName: String, needsIterator: Boolean) {
        subscriptionJobs[itemName] = launch {
            dataStateFlow
                .map { l -> l.associate { it.getValue("key") to it.getValue("value") } }
                .collect { data ->
                    listener.update(itemName, data, false)
                }
        }
    }

    override fun unsubscribe(itemName: String) {
        subscriptionJobs.remove(itemName)?.cancel()
    }
}
