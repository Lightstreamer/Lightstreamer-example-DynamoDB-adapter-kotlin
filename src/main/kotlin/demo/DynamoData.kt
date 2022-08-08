package demo

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType
import software.amazon.awssdk.services.dynamodb.model.StreamRecord
import software.amazon.awssdk.services.dynamodb.model.StreamStatus
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

const val DATA_TABLE_NAME = "DemoDeltaData"
const val DEPARTURE_TABLE_NAME = "DemoDeltaDeparture"
private val departureAttributesToGet =
    listOf("row", "destination", "departure", "flight", "terminal", "status", "airline")

val departureStateFlow: SharedFlow<List<Map<String, String>>> =
    tableDataFlow(DEPARTURE_TABLE_NAME, "row", departureAttributesToGet)

val dataStateFlow: SharedFlow<List<Map<String, String>>> =
    tableDataFlow(DATA_TABLE_NAME, "key", listOf("key", "value"))

private fun tableDataFlow(
    tableName: String,
    key: String,
    attributes: List<String>
): SharedFlow<List<Map<String, String>>> =
    channelFlow {
        // start DynamoDB demo updater
        val demoDynamoDbPublisher = launchDemoDynamoDbPublisher()
        invokeOnClose { demoDynamoDbPublisher.cancel() }

        val streamArn = dynamoDbClient.describeTable { it.tableName(tableName) }.await().table().latestStreamArn()
        println("DynamoDB $tableName Stream $streamArn")

        // read snapshot
        val map = readTable(tableName, attributes).toList()
            .associateTo(LinkedHashMap()) { it.getValue(key).unwrapToString() to it.unwrapValuesToString() }
        val mapMutex = Mutex()
        suspend fun sendMapUpdate() = send(map.values.toList())

        sendMapUpdate()

        // read updates
        activeShardFlow(streamArn = streamArn, tableName = tableName).collect { shardId ->
            launch {
                shardUpdateFlow(streamArn = streamArn, shardId = shardId, tableName, key, attributes)
                    .collect { (row, value) ->
                        mapMutex.withLock {
                            if (value == null) map.remove(row)
                            else map[row] = value
                            sendMapUpdate()
                        }
                    }
            }
        }
        error("$tableName $streamArn completed")
    }
        .retry { e ->
            // on error
            e.printStackTrace()
            delay(1.seconds)
            true
        }
        .conflate()
        .shareIn(GlobalScope, SharingStarted.WhileSubscribed(stopTimeout = 3.minutes), replay = 1)

/**
 * Active shards
 */
fun activeShardFlow(streamArn: String, tableName: String): Flow<String> = flow {
    println("$tableName Stream $streamArn started")
    var emittedIds = emptySet<String>()

    do {
        val streamDescription = dynamoDbStreamClient.describeStream { builder ->
            builder.streamArn(streamArn)
        }.await().streamDescription()

        val activeIds = streamDescription.shards()
            .filter { it.sequenceNumberRange().endingSequenceNumber() == null }
            .map { it.shardId() }
            .toSet()

        for (id in (activeIds - emittedIds)) emit(id)
        emittedIds = activeIds
        delay(10.seconds)
    } while (streamDescription.streamStatus() != StreamStatus.DISABLED)
    println("$tableName Stream $streamArn has been disabled")
}

/**
 * Events received from the [shardId]
 */
private fun shardUpdateFlow(
    streamArn: String,
    shardId: String,
    tableName: String,
    key: String,
    attributes: List<String>
): Flow<Pair<String, Map<String, String>?>> =
    channelFlow {
        println("shardUpdateFlow $tableName $shardId")
        var currentShardIterator = dynamoDbStreamClient.getShardIterator { builder ->
            builder
                .streamArn(streamArn)
                .shardId(shardId)
                .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
        }.await().shardIterator()

        var delay = Duration.ZERO // adaptive delay
        var updateJob: Job? = null
        var streamInSync = false
        while (currentShardIterator != null) {
            val getRecordsResponse =
                dynamoDbStreamClient.getRecords { it.shardIterator(currentShardIterator) }.await()
            val records = getRecordsResponse.records()
            if (records.isEmpty()) {
                delay = (delay + 1.milliseconds).coerceIn(1.milliseconds, 1.seconds)
            } else {
                streamInSync =
                    records.maxOf { it.dynamodb().approximateCreationDateTime() } >= Instant.now().minusSeconds(3)

                val previousStreamInSync = streamInSync
                val previousUpdateJob = updateJob
                updateJob = launch {
                    records
                        .map { it.dynamodb() }
                        .fold(emptyMap<String, StreamRecord>()) { map, streamRecord ->
                            // get the last event value for each key
                            map + mapOf(streamRecord.keys().getValue(key).unwrapToString() to streamRecord)
                        }.forEach { (row, streamRecord) ->
                            launch {
                                val value = if (previousStreamInSync && streamRecord.hasNewImage()) {
                                    // send the event value
                                    streamRecord.newImage()?.unwrapValuesToString()
                                } else {
                                    // send the actual value
                                    dynamoDbClient.getItem { builder ->
                                        builder
                                            .tableName(tableName)
                                            .key(mapOf(key to streamRecord.keys().getValue(key)))
                                            .attributesToGet(attributes)
                                    }.await()
                                        .takeIf { it.hasItem() }
                                        ?.item()?.unwrapValuesToString()
                                }

                                previousUpdateJob?.join()
                                send(row to value?.takeIf { it.isNotEmpty() })
                            }
                        }
                }

                if (streamInSync) {
                    delay /= 2
                } else {
                    delay = Duration.ZERO
                    println("Receiving old data from $tableName stream " +
                            records.maxOf { it.dynamodb().approximateCreationDateTime() }
                    )
                }
            }

            currentShardIterator = getRecordsResponse.nextShardIterator()
            if (streamInSync) delay(delay)
        }
        println("Shard $tableName $shardId finished")
    }

private fun AttributeValue.unwrapToString(): String = s() ?: n() ?: ""
private fun Map<String, AttributeValue>.unwrapValuesToString(): Map<String, String> =
    mapValues { (_, v) -> v.unwrapToString() }

fun main() {
    runBlocking {

        launch {
            dataStateFlow.collect { data ->
                println("Time ${data.find { it["key"] == "time" }?.get("value")}")
            }
        }

        launch {
            departureStateFlow.collect {
                println("Departure")
                println(it.joinToString(separator = "\n"))
            }
        }
    }
}
