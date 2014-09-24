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

  def apply[K, V](id: String, geometry: Geometry): Feature[String, Any] = {
    val crs = crsFactory.createFromName("EPSG:4326")
    Feature(id, crs, geometry, Map.empty[String, Any])
  }

  def apply[K, V](id: String, srid: Int, geometry: Geometry): Feature[String, Any] = {
    val crs = crsFactory.createFromName(s"EPSG:$srid")
    Feature(id, crs, geometry, Map.empty[String, Any])
  }

  def apply[K, V](id: String, geometry: Geometry, values: Map[K, V]): Feature[K, V] = {
    val crs = crsFactory.createFromName("EPSG:4326")
    Feature(id, crs, geometry, values)
  }

  def apply[K, V](id: String, srid: Int, geometry: Geometry, values: Map[K, V]): Feature[K, V] = {
    val crs = crsFactory.createFromName(s"EPSG:$srid")
    Feature(id, crs, geometry, values)
  }

}

/**
 * The last parameter `values` is a map of (fieldName --> value)
 */
case class Feature[K, V](id: String, crs: CoordinateReferenceSystem, geometry: Geometry, values: Map[K, V]) {

  lazy val ctf = new CoordinateTransformFactory

  lazy val crsFactory = new CRSFactory

  def countFields: Int = values.size

  def addOrUpdate(k: K, v: V): Feature[K, V] = Feature(id, crs, geometry, values.updated(k, v))

  def updateGeometry(geom: Geometry) = Feature(id, crs, geom, values)

  def get(k: K): Option[V] = values.get(k)

  def project(epsg: Int): Feature[K, V] = {
    val outCRS = crsFactory.createFromName(s"EPSG:$epsg")
    val transform = ctf.createTransform(crs, outCRS)
    val projGeom = geometry.geometryType match {
      case "Point" =>
        projectPoint(transform, geometry.asInstanceOf[Point])
      case "LineString" =>
        projectLine(transform, geometry.asInstanceOf[Line])
      case "Polygon" =>
        projectPolygon(transform, geometry.asInstanceOf[Polygon])

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

  private def projectLine(transform: CoordinateTransform, line: Line): Line = {
    val points = projectPoints(transform, line.points)
    Line(points, srid(transform.getTargetCRS))
  }

  private def projectPolygon(transform: CoordinateTransform, polygon: Polygon): Polygon = {
    val gf = polygon.jtsGeometry.getFactory
    val exterior = gf.createLinearRing(
      projectCoordinates(transform, polygon.jtsGeometry.getExteriorRing.getCoordinates))
    val numHoles = polygon.jtsGeometry.getNumInteriorRing

    def acc(list: List[jts.LineString], n: Int): List[jts.LineString] = n match {
      case 0 => list
      case _ =>
        val hole = polygon.jtsGeometry.getInteriorRingN(n - 1)
        acc(hole :: list, n - 1)
    }

    val rings = acc(Nil, numHoles)
    val holes = rings.map { h =>
      gf.createLinearRing(projectCoordinates(transform, h.getCoordinates))
    }.toArray
    Polygon(gf.createPolygon(exterior, holes))
  }

  private def srid(crs: CoordinateReferenceSystem): Int = {
    crs.getName.substring(5, crs.getName.length).toInt
  }
}
