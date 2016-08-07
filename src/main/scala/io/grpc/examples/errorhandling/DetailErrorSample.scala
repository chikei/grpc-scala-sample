package io.grpc.examples.errorhandling

import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.google.common.base.Verify
import com.google.protobuf.InvalidProtocolBufferException
import com.google.rpc.DebugInfo
import com.trueaccord.scalapb.GeneratedMessage
import io.grpc._
import io.grpc.examples.errorhandling.DetailErrorSample._
import io.grpc.examples.helloworld.GreeterGrpc.Greeter
import io.grpc.examples.helloworld.{GreeterGrpc, HelloReply, HelloRequest}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object DetailErrorSample {
  private val DEBUG_INFO_TRAILER_KEY = keyForProto(DebugInfo.defaultInstance)
  private val DEBUG_INFO =
    DebugInfo(Seq("stack_entry_1", "stack_entry_2", "stack_entry_3"),
      "detailed error info.")
  private val DEBUG_DESC = "detailed error description"

  def main(args: Array[String]): Unit = {
    new DetailErrorSample().run()
  }

  private def verifyErrorReply(t: Throwable): Unit = {
    val status = Status.fromThrowable(t)
    val trailers = Status.trailersFromThrowable(t)
    Verify.verify(status.getCode eq Status.Code.INTERNAL)
    Verify.verify(trailers.containsKey(DEBUG_INFO_TRAILER_KEY))
    Verify.verify(status.getDescription.equals(DEBUG_DESC))
    Verify.verify(trailers.get(DEBUG_INFO_TRAILER_KEY).equals(DEBUG_INFO))
  }

  def metadataMarshaller[T <: GeneratedMessage](instance: T): Metadata.BinaryMarshaller[T] =
    new Metadata.BinaryMarshaller[T] {
      override def toBytes(value: T) = value.toByteArray

      override def parseBytes(serialized: Array[Byte]) = {
        try {
          instance.companion.parseFrom(serialized).asInstanceOf[T]
        } catch {
          case ipbe: InvalidProtocolBufferException =>
            throw new IllegalArgumentException(ipbe)
        }
      }
    }

  def keyForProto[T <: GeneratedMessage](instance: T): Metadata.Key[T] =
    Metadata.Key.of(instance.companion.descriptor.getFullName + Metadata.BINARY_HEADER_SUFFIX,
      metadataMarshaller(instance))
}

class DetailErrorSample {
  private var channel: ManagedChannel = _

  def run(): Unit = {
    val server: Server = ServerBuilder.forPort(0).addService(
      GreeterGrpc.bindService(new Greeter {
        override def sayHello(request: HelloRequest) = {
          val trailers = new Metadata()
          trailers.put(DEBUG_INFO_TRAILER_KEY, DEBUG_INFO)
          Future.failed(Status.INTERNAL.withDescription(DEBUG_DESC)
            .asRuntimeException(trailers))
        }
      }, ExecutionContext.global))
      .build()
      .start()

    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort)
      .usePlaintext(true)
      .build()

    blockingCall()
    futureCallCallback()
    advancedAsyncCall()

    channel.shutdown()
    server.shutdown()
    channel.awaitTermination(1, TimeUnit.SECONDS)
    server.awaitTermination()
  }

  def blockingCall(): Unit = {
    val stub = GreeterGrpc.blockingStub(channel)
    try {
      stub.sayHello(HelloRequest())
    } catch {
      case e: Exception => verifyErrorReply(e)
    }
  }

  def futureCallCallback(): Unit = {
    val stub = GreeterGrpc.stub(channel)
    val response = stub.sayHello(HelloRequest())
    val latch = new CountDownLatch(1)
    response.onComplete {
      case Failure(t) =>
        verifyErrorReply(t)
        latch.countDown()
      case Success(_) =>
    }(ExecutionContext.global)

    Await.ready(response, 1 second)
  }

  def advancedAsyncCall(): Unit = {
    val call = channel.newCall(GreeterGrpc.METHOD_SAY_HELLO, CallOptions.DEFAULT)

    val latch = new CountDownLatch(1)
    call.start(new ClientCall.Listener[HelloReply] {
      override def onClose(status: Status, trailers: Metadata) = {
        Verify.verify(status.getCode eq Status.Code.INTERNAL)
        Verify.verify(trailers.containsKey(DEBUG_INFO_TRAILER_KEY))
        Verify.verify(status.getDescription.equals(DEBUG_DESC))
        Verify.verify(trailers.get(DEBUG_INFO_TRAILER_KEY).equals(DEBUG_INFO))
      }
    }, new Metadata())

    call.sendMessage(HelloRequest())
    call.halfClose()

    latch.await(1, TimeUnit.SECONDS)
  }
}
