package cloud.videos.factories

import com.mongodb.client.MongoClient
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.GridFSBuckets
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class MongoConfig {

    @Singleton
    fun gridFSBucket(mongoClient: MongoClient): GridFSBucket {
        val database = mongoClient.getDatabase("videos")
        return GridFSBuckets.create(database, "videos")
    }
}