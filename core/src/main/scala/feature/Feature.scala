package feature

import com.vividsolutions.jts.{ geom => jts }
import org.osgeo.proj4j.{
  CRSFactory,
  CoordinateReferenceSystem,
  CoordinateTransformFactory,
  CoordinateTransform,
  ProjCoordinate
}
import geometry._

object Feature {

  lazy val crsFactory = new CRSFactory

  def apply(id: String, geometry: Geometry): Feature = {
    val crs = crsFactory.createFromName("EPSG:4326")
    Feature(id, crs, geometry, Map.empty[String, Any])
  }

  def apply(id: String, srid: Int, geometry: Geometry): Feature = {
    val crs = crsFactory.createFromName(s"EPSG:$srid")
    Feature(id, crs, geometry, Map.empty[String, Any])
  }

  def apply(id: String, geometry: Geometry, values: Map[String, Any]): Feature = {
    val crs = crsFactory.createFromName("EPSG:4326")
    Feature(id, crs, geometry, values)
  }

  def apply(id: String, srid: Int, geometry: Geometry, values: Map[String, Any]): Feature = {
    val crs = crsFactory.createFromName(s"EPSG:$srid")
    Feature(id, crs, geometry, values)
  }

}

/**
 * The last parameter `values` is a map of (fieldName --> value)
 */
case class Feature(id: String, crs: CoordinateReferenceSystem, geometry: Geometry, values: Map[String, Any]) {

  lazy val ctf = new CoordinateTransformFactory

  lazy val crsFactory = new CRSFactory

  def countFields: Int = values.size

  def addOrUpdate(k: String, v: Any): Feature = Feature(id, crs, geometry, values.updated(k, v))

  def updateGeometry(geom: Geometry) = Feature(id, crs, geom, values)

  def get(k: String): Option[Any] = values.get(k)

  def envelope: Envelope = geometry.envelope

  def project(srid: Int): Feature = {
    val outCRS = crsFactory.createFromName(s"EPSG:$srid")
    val transform = ctf.createTransform(crs, outCRS)
    val projGeom = geometry.geometryType match {
      case "Point" =>
        projectPoint(transform, geometry.asInstanceOf[Point])
      case "MultiPoint" =>
        projectMultiPoint(transform, geometry.asInstanceOf[MultiPoint])
      case "LineString" =>
        projectLine(transform, geometry.asInstanceOf[Line])
      case "MultiLineString" =>
        projectMultiLine(transform, geometry.asInstanceOf[MultiLine])
      case "Polygon" =>
        projectPolygon(transform, geometry.asInstanceOf[Polygon])
      case "MultiPolygon" =>
        projectMultiPolygon(transform, geometry.asInstanceOf[MultiPolygon])

    }
    Feature(id, outCRS, projGeom, values)
  }

  private def projectCoordinate(transform: CoordinateTransform, coord: jts.Coordinate): jts.Coordinate = {
    val s = new ProjCoordinate(coord.x, coord.y)
    val t = new ProjCoordinate
    transform.transform(s, t)
    new jts.Coordinate(t.x, t.y)
  }

  private def projectCoordinates(transform: CoordinateTransform, coords: Array[jts.Coordinate]): Array[jts.Coordinate] = {
    coords.map(c => projectCoordinate(transform, c))
  }

  private def projectPoint(transform: CoordinateTransform, point: Point): Point = {
    val c = point.jtsGeometry.getCoordinate
    val pc = projectCoordinate(transform, c)
    Point(pc.x, pc.y, srid(transform.getTargetCRS).toInt)
  }

  private def projectPoints(transform: CoordinateTransform, points: Array[Point]): Array[Point] = {
    points.map(p => projectPoint(transform, p))
  }

  def projectMultiPoint(transform: CoordinateTransform, multiPoint: MultiPoint): MultiPoint = {
    val pts = multiPoint.geometries.map(p => p.asInstanceOf[jts.Point])
    val points = pts.map(p => projectPoint(transform, p)).toArray
    MultiPoint(points)
  }

  private def projectLine(transform: CoordinateTransform, line: Line): Line = {
    val points = projectPoints(transform, line.points)
    Line(points, srid(transform.getTargetCRS))
  }

  private def projectMultiLine(transform: CoordinateTransform, multiLine: MultiLine): MultiLine = {
    val lns = multiLine.geometries.map(l => l.asInstanceOf[jts.LineString])
    val lines = lns.map(l => projectLine(transform, Line(l))).toArray
    MultiLine(lines)
  }

  private def projectPolygon(transform: CoordinateTransform, polygon: Polygon): Polygon = {
    val gf = polygon.jtsGeometry.getFactory
    val exterior = gf.createLinearRing(
      projectCoordinates(transform, polygon.jtsGeometry.getExteriorRing.getCoordinates))
    val holes = polygon.holes.map { h =>
      h.jtsGeometry.asInstanceOf[jts.LinearRing]
    }
    Polygon(gf.createPolygon(exterior, holes))
  }

  def projectMultiPolygon(transform: CoordinateTransform, multiPolygon: MultiPolygon): MultiPolygon = {
    val pls = multiPolygon.geometries.map(p => p.asInstanceOf[jts.Polygon])
    val polys = pls.map(p => projectPolygon(transform, Polygon(p))).toArray
    MultiPolygon(polys)
  }

  private def srid(crs: CoordinateReferenceSystem): Int = {
    crs.getName.substring(5, crs.getName.length).toInt
  }
}
