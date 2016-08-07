package io.grpc.examples.helloworld

import java.util.logging.Logger

import io.grpc.{Server, ServerBuilder}

import scala.concurrent.{ExecutionContext, Future}

object HelloWorldServer{
  private val logger = Logger.getLogger(classOf[HelloWorldServer].getName)

  def main(args: Array[String]): Unit = {
    val server = new HelloWorldServer(ExecutionContext.global)
    server.start()
    server.blockUntilShutdown()
  }

  private class GreeterImpl extends GreeterGrpc.Greeter{
    def sayHello(request: HelloRequest) = {
      val reply = HelloReply("hello " + request.name)
      Future.successful(reply)
    }
  }
}

class HelloWorldServer(ec: ExecutionContext) {
  self =>

  import HelloWorldServer._

  private var server: Server = _
  private val port = 50051

  private def start(): Unit ={
    server = ServerBuilder.forPort(port).addService(GreeterGrpc.bindService(new GreeterImpl, ec)).build.start
    logger.info("Server started, listening on " + port)
    Runtime.getRuntime.addShutdownHook(new Thread(){
      override def run() = {
        System.err.println("*** shutting down gRPC server since JVM is shutting down")
        self.stop()
        System.err.println("*** server shut down")
      }
    })
  }

  private def stop(): Unit ={
    if(server != null) server.shutdown()
  }

  private def blockUntilShutdown(): Unit ={
    if(server != null) server.awaitTermination()
  }
}
