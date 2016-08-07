package io.grpc.examples.helloworld

import java.util.concurrent.TimeUnit
import java.util.logging.{Level, Logger}

import io.grpc.{ManagedChannel, ManagedChannelBuilder, StatusRuntimeException}

object HelloWorldClient{
  private val logger = Logger.getLogger(classOf[HelloWorldClient].getName)

  def main(args: Array[String]): Unit ={
    val client = new HelloWorldClient("localhost", 50051)
    try{
      val user = if(args.length > 0) args(0) else "world"
      client.greet(user)
    } finally client.shutdown()
  }
}

class HelloWorldClient(host: String, port: Int) {
  import HelloWorldClient._

  val channel: ManagedChannel = ManagedChannelBuilder.forAddress(host, port)
      .usePlaintext(true).build()
  val blockingStub: GreeterGrpc.GreeterBlockingStub = GreeterGrpc.blockingStub(channel)

  def greet(name: String): Unit = {
    logger.info("Will try to greet " + name + " ...")
    val req = HelloRequest(name)
    var response: HelloReply = null
    try{
      response = blockingStub.sayHello(req)
    } catch {
      case e: StatusRuntimeException =>
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus)
        return
    }
    logger.info("Greeting: " + response.message)
  }

  def shutdown(): Unit ={
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
  }
}
