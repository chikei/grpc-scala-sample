package io.grpc.examples.routeguide

import java.util
import java.util.Collections
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.logging.{Level, Logger}
import java.lang.Math.{atan2, cos, sin, sqrt, toRadians}

import io.grpc.stub.StreamObserver
import io.grpc.{Server, ServerBuilder, Status}
import RouteGuideServer._

import scala.concurrent.{ExecutionContext, Future}

object RouteGuideServer{
  private val logger = Logger.getLogger(classOf[RouteGuideServer].getName)

  def main(args: Array[String]): Unit = {
    val server = RouteGuideServer(8980, ExecutionContext.global)
    server.start()
    server.blockUntilShutdown()
  }

  def apply(port: Int, ec: ExecutionContext): RouteGuideServer = {
    new RouteGuideServer(port, RouteGuideUtil.parseFeatures(RouteGuideUtil.getDefaultFeaturesFile), ec)
  }

  private def calcDistance(start: Point, end: Point): Double = {
    val lat1 = RouteGuideUtil.getLatitude(start)
    val lat2 = RouteGuideUtil.getLatitude(end)
    val lon1 = RouteGuideUtil.getLongitude(start)
    val lon2 = RouteGuideUtil.getLongitude(end)
    val r = 6371000 // metres
    val φ1 = toRadians(lat1)
    val φ2 = toRadians(lat2)
    val Δφ = toRadians(lat2 - lat1)
    val Δλ = toRadians(lon2 - lon1)

    val a = sin(Δφ / 2) * sin(Δφ / 2) + cos(φ1) * cos(φ2) * sin(Δλ / 2) * sin(Δλ / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    r * c
  }

  private class RouteGuideService(val features: Seq[Feature]) extends RouteGuideGrpc.RouteGuide{
    private val routeNotes = new ConcurrentHashMap[Point, util.List[RouteNote]]()

    def getFeature(request: Point) = Future.successful(checkFeature(request))

    def listFeatures(request: Rectangle, responseObserver: StreamObserver[Feature]) = {
      val hi = request.getHi
      val lo = request.getLo

      val left = lo.longitude min hi.longitude
      val right = lo.longitude max hi.longitude
      val top = lo.latitude max hi.latitude
      val bottom = lo.latitude min hi.latitude

      features.iterator.filter(RouteGuideUtil.exists).foreach{ f =>
        val lat = f.getLocation.latitude
        val lon = f.getLocation.longitude
        if(lon >= left && lon <= right && lat >= bottom && lat <= top)
          responseObserver.onNext(f)
      }

      responseObserver.onCompleted()
    }

    def recordRoute(responseObserver: StreamObserver[RouteSummary]): StreamObserver[Point] = {
      new StreamObserver[Point] {
        var pointCount = 0
        var featureCount = 0
        var distance = 0
        var previous: Point = _
        val startTime = System.nanoTime()

        def onError(t: Throwable) = logger.log(Level.WARNING, "recordRoute cancelled")

        def onCompleted() = {
          val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime)
          logger.log(Level.WARNING, "test recordRoute cancelled") // test
          responseObserver.onNext(
            RouteSummary(pointCount, featureCount, distance, seconds.toInt))
          responseObserver.onCompleted()
        }

        def onNext(point: Point) = {
          pointCount += 1
          if (RouteGuideUtil.exists(checkFeature(point))) featureCount += 1

          if (previous != null) distance += calcDistance(previous, point).toInt

          previous = point

          logger.log(Level.WARNING, "test recordRoute before cancelled") // test
          responseObserver.onError(Status.ABORTED.asRuntimeException())
        }
      }
    }

    def routeChat(responseObserver: StreamObserver[RouteNote]): StreamObserver[RouteNote] =
      new StreamObserver[RouteNote] {
        def onError(t: Throwable) = logger.log(Level.WARNING, "recordChat cancelled")

        def onCompleted() = responseObserver.onCompleted()

        def onNext(note: RouteNote) = {
          import collection.JavaConverters._
          val notes = getOrCreateNote(note.getLocation)

          notes.asScala.foreach(responseObserver.onNext)

          notes.add(note)
        }
      }

    private def checkFeature(loc: Point): Feature = {
      features.find(f => f.getLocation.longitude == loc.longitude && f.getLocation.latitude == loc.latitude)
          .getOrElse(Feature(location = None))
    }

    private def getOrCreateNote(loc: Point): util.List[RouteNote] = {
      val notes = Collections.synchronizedList(new util.ArrayList[RouteNote])
      val prevNotes = routeNotes.putIfAbsent(loc, notes)
      if(prevNotes != null) prevNotes else notes
    }
  }
}

class RouteGuideServer(port: Int, features: Seq[Feature], ec: ExecutionContext) {
  self =>

  val server: Server = ServerBuilder.forPort(port)
    .addService(RouteGuideGrpc.bindService(new RouteGuideService(features), ec)).build()

  def start(): Unit ={
    server.start()
    logger.info("Server started, listening on " + port)
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        System.err.println("*** shutting down gRPC server since JVM is shutting down")
        self.stop()
        System.err.println("*** server shut down")
      }
    })
  }

  def stop(): Unit ={
    if(server != null) server.shutdown()
  }

  private def blockUntilShutdown(): Unit ={
    if(server != null) server.awaitTermination()
  }
}
