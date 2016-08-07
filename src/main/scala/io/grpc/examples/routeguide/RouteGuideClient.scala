package io.grpc.examples.routeguide

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.logging.{Level, Logger}

import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannelBuilder, Status, StatusRuntimeException}

import scala.util.Random

object RouteGuideClient{
  private val logger = Logger.getLogger(classOf[RouteGuideClient].getName)

  def apply(host: String, port: Int): RouteGuideClient = {
    val builder: ManagedChannelBuilder[_] =
      ManagedChannelBuilder.forAddress(host, port).usePlaintext(true)
    new RouteGuideClient(builder)
  }

  private def info(msg: String, params: Any*): Unit ={
    logger.log(Level.INFO, msg, params)
  }

  def main(args: Array[String]): Unit = {
    val features = RouteGuideUtil.parseFeatures(RouteGuideUtil.getDefaultFeaturesFile)
    val client = RouteGuideClient("localhost", 8980)
    try{
      client.getFeature(409146138, -746188906)

      client.getFeature(0, 0)

      client.listFeatures(400000000, -750000000, 420000000, -730000000)

      client.recordRoute(features, 10)

      client.routeChat()
    } finally {
      client.shutdown()
    }
  }
}

class RouteGuideClient(channelBuilder: ManagedChannelBuilder[_]) {
  import RouteGuideClient._

  val channel = channelBuilder.build()
  val blockingStub = RouteGuideGrpc.blockingStub(channel)
  val asyncStub = RouteGuideGrpc.stub(channel)

  def shutdown(): Unit ={
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
  }

  def getFeature(lat: Int, lon: Int): Unit ={
    info("*** GetFeature: lat={0} lon={1}", lat, lon)
    val req = Point(latitude = lat, longitude = lon)
    val feature = try{
      blockingStub.getFeature(req)
    } catch {
      case e: StatusRuntimeException =>
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus)
        Feature.defaultInstance
    }
    if(RouteGuideUtil.exists(feature)) {
      info("Found feature called \"{0}\" at {1}, {2}",
        feature.name,
        RouteGuideUtil.getLatitude(feature.getLocation),
        RouteGuideUtil.getLongitude(feature.getLocation))
    }
    else {
      info("Found no feature at {0}, {1}",
        RouteGuideUtil.getLatitude(feature.getLocation),
        RouteGuideUtil.getLongitude(feature.getLocation));
    }
  }

  def listFeatures(lowLat: Int, lowLon: Int, hiLat: Int, hiLon: Int): Unit = {
    info("*** ListFeatures: lowLat={0} lowLon={1} hiLat={2} hiLon={3}", lowLat, lowLon, hiLat, hiLon)

    val req = Rectangle(Some(Point(lowLat, lowLon)), Some(Point(hiLat, hiLon)))

    val features = try{
      blockingStub.listFeatures(req)
    }
    catch{
      case e: StatusRuntimeException =>
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus)
        Iterator.empty
    }

    info("Result: " + features.mkString(""))
  }

  def recordRoute(features: Seq[Feature], numPoints: Int): Unit = {
    info("*** RecordRoute")

    val finishLatch = new CountDownLatch(1)
    val responseObserver = new StreamObserver[RouteSummary] {
      override def onError(t: Throwable) = {
        val status = Status.fromThrowable(t)
        logger.log(Level.WARNING, "RecordRoute Failed: {0}", status)
        finishLatch.countDown()
      }

      override def onCompleted() = {
        info("Finished RecordRoute")
        finishLatch.countDown()
      }

      override def onNext(summary: RouteSummary) = {
        info("Finished trip with {0} points. Passed {1} features. "
          + "Travelled {2} meters. It took {3} seconds.", summary.pointCount,
          summary.featureCount, summary.distance, summary.elapsedTime)
      }
    }

    val requestObserver = asyncStub.recordRoute(responseObserver)
    try {
      val rand = new Random()
      (0 until numPoints).foreach { _ =>
        val index = rand.nextInt(features.size)
        val point = features(index).getLocation
        info("Visiting point {0}, {1}", RouteGuideUtil.getLatitude(point),
          RouteGuideUtil.getLongitude(point))
        requestObserver.onNext(point)
        Thread.sleep(rand.nextInt(1000) + 500)
        if (finishLatch.getCount == 0) {
          // something wrong or completed
          return
        }
      }
    } catch {
      case e: RuntimeException =>
        // Cancel RPC
        requestObserver.onError(e)
        throw e
    }
    // Mark the end of requests
    requestObserver.onCompleted()

    // Receiving happens asynchronously
    finishLatch.await(1, TimeUnit.MINUTES)
  }

  def routeChat(): Unit ={
    info("*** RoutChat")
    val finishLatch = new CountDownLatch(1)
    val requestObserver = asyncStub.routeChat(new StreamObserver[RouteNote] {
      override def onError(t: Throwable) = {
        val status = Status.fromThrowable(t)
        logger.log(Level.WARNING, "RouteChat Failed: {0}", status)
        finishLatch.countDown()
      }

      override def onCompleted() = {
        info("Finished RouteChat")
        finishLatch.countDown()
      }

      override def onNext(note: RouteNote) = {
        info("Got message \"{0}\" at {1}, {2}", note.message, note.getLocation.latitude,
          note.getLocation.longitude)
      }
    })

    try {
      val requests = Seq(
        RouteNote(Some(Point(0, 0)), "First message"), RouteNote(Some(Point(0, 1)), "Second message"),
        RouteNote(Some(Point(1, 0)), "Third message"), RouteNote(Some(Point(1, 1)), "Fourth message")
      )
      requests.foreach { request =>
        info("Sending message \"{0}\" at {1}, {2}", request.message,
          request.getLocation.latitude, request.getLocation.longitude)
        requestObserver.onNext(request)
      }
    } catch{
      case e: RuntimeException =>
        requestObserver.onError(e)
        throw e
    }

    // Mark the end of requests
    requestObserver.onCompleted

    // Receiving happens asynchronously
    finishLatch.await(1, TimeUnit.MINUTES)

  }
}
