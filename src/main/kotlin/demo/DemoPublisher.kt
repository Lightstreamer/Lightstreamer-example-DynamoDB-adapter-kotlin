package demo

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.random.nextLong
import kotlin.time.Duration.Companion.seconds

private val timeFormatter = DateTimeFormatterBuilder()
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .toFormatter(Locale.US)

private object DestinationGenerator {
    private val destinations = listOf(
        "Seoul (ICN)",
        "Atlanta (ATL)",
        "Boston (BOS)",
        "Phoenix (PHX)",
        "Detroit (DTW)",
        "San Francisco (SFO)",
        "Salt Lake City (SLC)",
        "Fort Lauderdale (FLL)",
        "Los Angeles (LAX)",
        "Seattle (SEA)",
        "Miami (MIA)",
        "Orlando (MCO)",
        "Charleston (CHS)",
        "West Palm Beach (PBI)",
        "Fort Myers (RSW)",
        "San Salvador (SAL)",
        "Tampa (TPA)",
        "Portland (PWM)",
        "London (LHR)",
        "Malpensa (MXP)"
    )

    private val atomicIndex = AtomicInteger(Random.nextInt(destinations.size))

    fun next(): String {
        val index = atomicIndex.updateAndGet { i ->
            if (i == destinations.lastIndex) 0 else i + 1
        }
        return destinations[index]
    }
}

/**
 * Only one publisher can update dynamoDB
 */
private val dynamoDbDemoMutex = Mutex()

fun launchDemoDynamoDbPublisher(): Job = GlobalScope.launch {
    dynamoDbDemoMutex.withLock {
        // check for spurious demo start caused by cancelling subscriptions
        delay(1.seconds)
        ensureActive()

        try {
            while (isActive) {
                val rowRange = 1..9
                val startTime = LocalTime.of(7, 0)

                val timeFlow = flow {
                    var now = startTime
                    while (true) {
                        emit(now)
                        delay(1.seconds)
                        now += Duration.ofMinutes(1)
                    }
                }.stateIn(this, SharingStarted.Eagerly, startTime)

                // update DemoDeltaData
                launch {
                    timeFlow.collect { time ->
                        try {
                            dynamoDbClient.putItem { builder ->
                                builder
                                    .tableName(DATA_TABLE_NAME)
                                    .item(
                                        mapOf(
                                            "key" to AttributeValue.fromS("time"),
                                            "value" to AttributeValue.fromS(timeFormatter.format(time))
                                        )
                                    )
                            }.await()

                            println("Caricato Valore: " + time)

                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                val actors: MutableMap<Int, Job> =
                    rowRange.associateWithTo(mutableMapOf()) { CompletableDeferred(Unit) }
                while (isActive) {
                    select<Unit> {
                        for ((row, actor) in actors) {
                            actor.onJoin {
                                actors[row] = launchRowActor(row, timeFlow)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // clear tables
            withContext(NonCancellable) {
                readTable(DATA_TABLE_NAME, listOf("key")).collect { keys ->
                    dynamoDbClient.deleteItem { builder ->
                        builder
                            .tableName(DATA_TABLE_NAME)
                            .key(keys)
                    }.join()
                }
                readTable(DEPARTURE_TABLE_NAME, listOf("row")).collect { keys ->
                    dynamoDbClient.deleteItem { builder ->
                        builder
                            .tableName(DEPARTURE_TABLE_NAME)
                            .key(keys)
                    }.join()
                }
            }
        }
    }
}

private enum class FlightStatus(val icon: String, val description: String) {
    SCHEDULED_ON_TIME("\uD83C\uDFAB", "Scheduled - On-time"),
    SCHEDULED_DELAYED("⌛", "Scheduled - Delayed"),
    EN_ROUTE_ON_TIME("\uD83D\uDEEB", " En Route - On-time"),
    EN_ROUTE_DELAYED("\uD83D\uDEEC️", "En Route - Delayed"),
    LANDED_ON_TIME("✅", "Landed - On-time"),
    LANDED_DELAYED("\uD83D\uDFE9", "Landed - Delayed"),
    CANCELLED("\uD83D\uDED1", "Cancelled")
}

private fun CoroutineScope.launchRowActor(
    row: Int,
    timeFlow: StateFlow<LocalTime>
) = launch {
    try {
        val destination = DestinationGenerator.next()
        var departure = timeFlow.value + Duration.ofMinutes(Random.nextLong(5L..10L))
        val flight = "DL$row${Random.nextInt(10, 999)}"
        val terminal = if (Random.nextBoolean()) "2" else "4"
        val airline = "Delta Air Lines"

        var status: FlightStatus = FlightStatus.SCHEDULED_ON_TIME

        val dynamoDataMap = mutableMapOf(
            "row" to AttributeValue.fromN(row.toString()),
            "destination" to AttributeValue.fromS(status.icon + ' ' + destination),
            "departure" to AttributeValue.fromS(timeFormatter.format(departure)),
            "flight" to AttributeValue.fromS(flight),
            "terminal" to AttributeValue.fromN(terminal),
            "status" to AttributeValue.fromS(status.description),
            "airline" to AttributeValue.fromS(airline)
        )

        println("Start row $row(${timeFormatter.format(departure)})")
        dynamoDbClient.putItem { builder ->
            builder
                .tableName(DEPARTURE_TABLE_NAME)
                .item(dynamoDataMap)
        }.await()

        var nextStep = departure
        timeFlow
            .filter { it >= nextStep }
            .onEach { nextStep += Duration.ofMinutes(5) }
            .collect {
                status = when (status) {
                    FlightStatus.SCHEDULED_ON_TIME ->
                        if (Random.nextInt(5) == 0) {
                            departure += Duration.ofMinutes(Random.nextLong(3L..10L))
                            FlightStatus.SCHEDULED_DELAYED
                        } else {
                            FlightStatus.EN_ROUTE_ON_TIME
                        }
                    FlightStatus.SCHEDULED_DELAYED ->
                        if (Random.nextInt(10) == 0) FlightStatus.CANCELLED
                        else FlightStatus.EN_ROUTE_DELAYED

                    FlightStatus.EN_ROUTE_ON_TIME ->
                        if (Random.nextInt(5) == 0) FlightStatus.EN_ROUTE_DELAYED
                        else FlightStatus.LANDED_ON_TIME
                    FlightStatus.EN_ROUTE_DELAYED ->
                        FlightStatus.LANDED_DELAYED

                    FlightStatus.LANDED_ON_TIME, FlightStatus.LANDED_DELAYED, FlightStatus.CANCELLED ->
                        throw CancellationException()
                }

                dynamoDataMap["departure"] = AttributeValue.fromS(timeFormatter.format(departure))
                dynamoDataMap["destination"] = AttributeValue.fromS(status.icon + ' ' + destination)
                dynamoDataMap["status"] = AttributeValue.fromS(status.description)
                dynamoDbClient.putItem { builder ->
                    builder
                        .tableName(DEPARTURE_TABLE_NAME)
                        .item(dynamoDataMap)
                }.await()
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun main() {
    runBlocking {
        launchDemoDynamoDbPublisher().join()
    }
}
