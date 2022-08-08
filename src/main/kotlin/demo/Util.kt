package demo

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient

val awsRegion = Region.EU_WEST_1

val dynamoDbClient = DynamoDbAsyncClient.builder().region(awsRegion).build()
val dynamoDbStreamClient = DynamoDbStreamsAsyncClient.builder().region(awsRegion).build()

fun readTable(tableName: String, attributes: List<String>) = flow {
    var lastEvaluatedKey: Map<String, AttributeValue>? = null
    do {
        val scanResult = dynamoDbClient.scan { builder ->
            builder
                .tableName(tableName)
                .attributesToGet(attributes)
                .exclusiveStartKey(lastEvaluatedKey)
        }.await()
        emitAll(scanResult.items().asFlow())

        lastEvaluatedKey = scanResult.lastEvaluatedKey()
    } while (!lastEvaluatedKey.isNullOrEmpty())
}
