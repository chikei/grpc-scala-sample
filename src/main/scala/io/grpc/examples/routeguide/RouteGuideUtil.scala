package io.grpc.examples.routeguide

import java.net.URL

import com.trueaccord.scalapb.json.JsonFormat

import scala.io.Source

object RouteGuideUtil {
  private val COORD_FACTOR = 1e7

  def getLatitude(location: Point): Double = location.latitude / COORD_FACTOR

  def getLongitude(location: Point): Double = location.longitude / COORD_FACTOR

  def getDefaultFeaturesFile: URL =
    classOf[RouteGuideServer].getResource("route_guide_db.json")

  def parseFeatures(file: URL): Seq[Feature] = {
    val input = file.openStream
    try {
      val jsonString = Source.fromInputStream(input).mkString
      val database = JsonFormat.fromJsonString[FeatureDatabase](jsonString)
      database.feature
    }
    finally input.close()
  }

  def exists(feature: Feature): Boolean = feature != null && !feature.name.isEmpty
}
