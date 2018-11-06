package controllers

import java.sql.Timestamp

import javax.inject._
import model.{Favicon, Favicons}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.Logger
import play.api.libs.ws._
import play.api.mvc._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try

/**
  * This controller holds methods for determining favicon
  */
@Singleton
class FaviconController @Inject()(cc: ControllerComponents,
                                  ws: WSClient) extends AbstractController(cc) {

  /**
    * Check for url in DB
    *
    * @param url target url
    * @return
    */
  private def getURLFromDB(url: String): Future[Option[String]] = {

    Logger.info(s"Checking db for $url")
    Favicons.get(url)
      .map {
        _.map(_.favUrl)
      }
      .recover {
        case t: Throwable =>
          Logger.error(t.getMessage)
          None
      }
  }

  /**
    * General method for querying url through WSClient. Currently kept simple since we should only be hitting
    * each url twice at the most
    *
    * @param url Target url
    * @return
    */
  private def queryUrl(url: String): Future[WSResponse] = {
    ws.url(url)
      .addHttpHeaders("Content-Type" -> "application/xml")
      .withFollowRedirects(true)
      .get()
  }

  /**
    * Check fo favicon.ico at root level
    *
    * @param url target url
    * @return
    */
  private def checkFaviconIco(url: String): Future[Option[String]] = {

    Logger.info(s"Checking favion ico url for $url")

    val favUrl = s"$url/favicon.ico"
    queryUrl(favUrl).map {
      response =>
        response.status match {
          case 200 => Some(favUrl)
          case _ => None
        }
    }.recover {
      case t =>
        Logger.error(t.getMessage)
        None
    }
  }

  /**
    * When response is successful then we check header for link tag where there's a href link and
    * rel is "icon" or "shortcut icon"
    *
    * @param url target url
    * @return
    */
  private def checkHeadForLink(url: String): Future[Option[String]] = {

    Logger.info(s"Checking page for $url")

    queryUrl(url).map { response =>
      Logger.info(s"$url received ${response.status}")
      response.status match {
        case 200 =>
          val doc: Document = Jsoup.parse(response.body)
          val head = doc.head
          val links = head.select("link[href]")
          links.listIterator.asScala.toList.find {
            element =>
              element.attr("rel") == "icon" || element.attr("rel") == "shortcut icon"
          } match {
            case Some(element) =>
              val attr = element.attr("href")
              val favUrl = if (attr.startsWith("/")) s"$url$attr" else attr
              Some(favUrl)
            case None =>
              None
          }
        case _ => None
      }
    }.recover {
      case t =>
        Logger.error(t.getMessage)
        None
    }

  }

  /**
    * Helper method for inserting into DB
    *
    * @param url    target url
    * @param favUrl resulting favorite url
    * @return
    */
  private def persistToDb(url: String, favUrl: String) = {
    val now = new Timestamp(System.currentTimeMillis)
    val f = Favicon(
      id = 0L,
      url = url,
      favUrl = favUrl,
      createdAt = now,
      updatedAt = now
    )
    Favicons.add(f)
  }

  /**
    * Current strategy of grabbing favicon for a specific url
    * @param url Target url
    * @param getFresh flag to determine if we should reevaluate value in DB
    * @return
    */
  def getFavicon(url: String, getFresh: Boolean): Option[String] = {
    Logger.info(s"Checking $url")
    Try(
      {
        Await.result(
          {
            //If get fresh then we will skip db check and directly check value
            for{
              dbRes <- if(!getFresh) getURLFromDB(url) else Future(None)
              rootRes <- if(dbRes.isDefined) Future(dbRes) else checkFaviconIco(url)
              headRes <- if(rootRes.isDefined) Future(rootRes) else checkHeadForLink(url)
            }yield(headRes)
          }, 2500 millis
        )
      }
    ).getOrElse(None)
  }

  /**
    * Entry method to check for favicon
    *
    * @return
    */
  def getFaviconForUrl = Action.async {

    request =>

      //Inner function to parse POST data and check for url and getFresh flag
      def parseRequestData: Option[(String, Boolean)] = {
        request.body.asFormUrlEncoded match {
          case Some(data) =>
            data.get("url").map { urls =>

              val getFresh = data.get("getFresh") match {
                case Some(values) =>
                  (values.headOption match {
                    case Some(getFresh) if getFresh == "true" => Some(true)
                    case _ => None
                  }).isDefined
                case _ => false
              }
              (urls.head, getFresh)
            }
          case None => None
        }
      }

      //Inner function to get urls to check
      def getUrls(baseUrl: String): List[String] = {
        val hasProtocol = (s: String) => s.startsWith("http")
        val hasSubdomain = (s: String) => s.startsWith("www")
        if (hasProtocol(baseUrl)) List(baseUrl)
        else if (hasSubdomain(baseUrl)) {
          List(
            s"http://$baseUrl",
            s"https://$baseUrl")
        } else {
          List(
            s"http://$baseUrl",
            s"https://$baseUrl",
            s"http://www.$baseUrl",
            s"https://www.$baseUrl")
        }
      }

      parseRequestData match {
        case Some((url, getFresh)) =>
          val urls = getUrls(url)
          Logger.info(s"Checking for $url -> ${urls.mkString(", ")}")
          var favIcon: Option[String] = None
          urls.view.find { u =>
            favIcon = getFavicon(u, getFresh)
            favIcon.map{ favUrl =>
              persistToDb(u, favUrl)
              if(u != url) persistToDb(url, favUrl)
            }
            favIcon.isDefined
          }

          favIcon match {
            case Some(favUrl) => Future(Ok(favUrl))
            case None => Future(NotFound(s"No favicon found for $url"))
          }

        case None => Future(BadRequest("Invalid data in request"))
      }

  }

}
