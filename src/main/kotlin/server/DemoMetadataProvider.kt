package server

import com.lightstreamer.interfaces.metadata.MetadataProviderAdapter
import demo.dataStateFlow
import demo.departureStateFlow
import java.io.File

class DemoMetadataProvider : MetadataProviderAdapter() {

    override fun init(params: MutableMap<Any?, Any?>, configDir: File) {
        // preload
        departureStateFlow
        dataStateFlow
    }

    override fun getSchema(user: String?, sessionID: String, group: String, schema: String): Array<String> =
        schema.split(' ').toTypedArray()

    override fun getItems(user: String?, sessionID: String, group: String): Array<String> =
        group.split(' ').toTypedArray()
}
