package org.novelfs.streaming.kafka

import org.novelfs.streaming.kafka.ops._
import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords, Consumer => ApacheKafkaConsumer, KafkaConsumer => ConcreteApacheKafkaConsumer}
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, Deserializer}
import fs2._
import cats.effect._
import cats.implicits._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._

object KafkaConsumer {

  private val log = LoggerFactory.getLogger(KafkaConsumer.getClass)

  private def identityPipe[F[_], O] : Pipe[F, O, O] = s => s

  /**
    * An effect to commit supplied map of offset metadata for each topic/partition pair
    */
  def commitOffsetMap[F[_] : Effect, K, V](consumer : ApacheKafkaConsumer[K, V])(offsetMap : Map[TopicPartition, OffsetMetadata]): F[Unit] =
    Async[F].async { (cb: Either[Throwable, Unit] => Unit) =>
      consumer.commitAsync(KafkaSdkConversions.toKafkaOffsetMap(offsetMap), (_: java.util.Map[_, _], exception: Exception) => Option(exception) match {
        case None =>
          log.debug(s"Offset committed: $offsetMap")
          cb(Right(()))
        case Some(ex) =>
          log.error("Error committing offset", ex)
          cb(Left(ex))
      })
    }

  /**
    * A pipe that accumulates the offset metadata for each topic/partition pair for the supplied input stream of Consumer Records
    */
  def accumulateOffsetMetadata[F[_], K, V]: Pipe[F, ConsumerRecord[K, V], (ConsumerRecord[K, V], Map[TopicPartition, OffsetMetadata])] =
    _.zipWithScan(Map.empty[TopicPartition, OffsetMetadata])((map, record) => map + (TopicPartition(record.topic(), record.partition()) -> OffsetMetadata(record.offset())))

  /**
    * A convenience pipe that accumulates offset metadata based on the supplied commitSettings and commits them to Kafka at some defined frequency
    */
  def commitOffsets[F[_] : Effect, K, V]
    (consumer : ApacheKafkaConsumer[Array[Byte], Array[Byte]])(autoCommitSettings: KafkaOffsetCommitSettings.AutoCommit)
    (implicit ex : ExecutionContext): Pipe[F, ConsumerRecord[K,V], ConsumerRecord[K,V]] = (s : Stream[F, ConsumerRecord[K, V]]) =>
      s.through(accumulateOffsetMetadata)
        .observeAsync(autoCommitSettings.maxAsyncCommits)(s =>
          s.takeElementsEvery(autoCommitSettings.timeBetweenCommits)
            .evalMap{case (_, offsetMap) => commitOffsetMap(consumer)(offsetMap)})
        .map(_._1)


  /**
    * An effect that generates a subscription to some Kafka topics/paritions using the supplied kafka config
    */
  def subscribeToConsumer[F[_] : Async, K, V](config : KafkaConsumerConfig[K, V]): F[ApacheKafkaConsumer[Array[Byte], Array[Byte]]] = {
    val consumer = new ConcreteApacheKafkaConsumer(KafkaConsumerConfig.generateProperties(config), new ByteArrayDeserializer(), new ByteArrayDeserializer())
    Async[F].delay (consumer.subscribe(config.topics.asJava)) *> Async[F].point(consumer)
  }

  /**
    * An effect that disposes of some supplied kafka consumer
    */
  def cleanupConsumer[F[_] : Async, K, V](consumer : ApacheKafkaConsumer[K,V]): F[Unit] = Async[F].delay(consumer.close())

  /**
    * An effect that polls kafka (once) with a supplied timeout
    */
  def pollKafka[F[_] : Async, K, V](consumer : ApacheKafkaConsumer[K, V])(pollTimeout : FiniteDuration): F[ConsumerRecords[K, V]] =
    Async[F].delay(consumer.poll(pollTimeout.toMillis))

  /**
    * A pipe that deserialises an array of bytes using supplied key and value deserialisers
    */
  def deserializer[F[_] : Async, K, V](keyDeserializer: Deserializer[K], valueDeserializer : Deserializer[V]) : Pipe[F, ConsumerRecord[Array[Byte], Array[Byte]], (K, V)] =
    _.evalMap(record =>
      Async[F].delay((keyDeserializer.deserialize(record.topic(), record.key()), valueDeserializer.deserialize(record.topic(), record.value())))
    )

  /**
    * Creates a streaming subscription using the supplied kafka configuration
    */
  def apply[F[_] : Effect, K, V](config : KafkaConsumerConfig[K, V])(implicit ex : ExecutionContext): Stream[F, (K, V)] =
    Stream.bracket(subscribeToConsumer(config))(consumer =>
      for {
        records <- Stream.repeatEval(pollKafka(consumer)(config.pollTimeout))
        process <- Stream.emits(records.asScala.toVector)
          .covary[F]
          .through(config.commitOffsetSettings match {
            case autoCommitSettings : KafkaOffsetCommitSettings.AutoCommit => commitOffsets(consumer)(autoCommitSettings)
            case _ => identityPipe
          })
          .through(deserializer(config.keyDeserializer, config.valueDeserializer))
      } yield process, cleanupConsumer[F, Array[Byte], Array[Byte]])
}
