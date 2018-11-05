package model

import java.sql.Timestamp

import play.api.Play
import play.api.db.slick.DatabaseConfigProvider
import slick.driver.MySQLDriver.api._
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Favicon(id: Long, url: String, favUrl: String, createdAt: Timestamp, updatedAt: Timestamp)

class FaviconTableDef(tag: Tag) extends Table[Favicon](tag, "favicon") {

  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def url = column[String]("url")

  def favUrl = column[String]("fav_url")

  def createdAt = column[Timestamp]("created_at")

  def updatedAt = column[Timestamp]("updated_at")

  override def * =
    (id, url, favUrl, createdAt, updatedAt) <> (Favicon.tupled, Favicon.unapply)
}

object Favicons {

  val dbConfig = DatabaseConfigProvider.get[JdbcProfile](Play.current)

  var favicons = TableQuery[FaviconTableDef]

  def add(favicon: Favicon): Future[String] = {
    dbConfig.db.run(favicons.insertOrUpdate(favicon)).map(res => s"Favicon added: ${favicon.url}").recover {
      case ex: Exception => ex.getCause.getMessage
    }
  }

  def get(url: String): Future[Option[Favicon]] = {
    dbConfig.db.run(favicons.filter(_.url === url).result.headOption)
  }

  def delete(url: String): Future[Int] = {
    dbConfig.db.run(favicons.filter(_.url === url).delete)
  }

}