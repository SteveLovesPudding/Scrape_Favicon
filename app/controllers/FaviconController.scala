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
  private def getURLFromDB(url: String): Option[String] = {

    Logger.info(s"Checking db for $url")
    Await.result(
      Favicons.get(url)
        .map {
          _.map(_.favUrl)
        }
        .recover {
          case t: Throwable =>
            Logger.error(t.getMessage)
            None
        },
      2500 millis
    )
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
  private def checkFaviconIco(url: String): Option[String] = {

    Logger.info(s"Checking favion ico url for $url")

    val favUrl = s"$url/favicon.ico"
    Await.result(
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
      },
      2500 millis
    )
  }

  /**
    * When response is successful then we check header for link tag where there's a href link and
    * rel is "icon" or "shortcut icon"
    *
    * @param url target url
    * @return
    */
  private def checkHeadForLink(url: String): Option[String] = {

    Logger.info(s"Checking page for $url")

    Await.result(
      queryUrl(url).map { response =>

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
            Logger.info(s"$url received ${response.status}")
          case _ => None
        }
      }.recover {
        case t =>
          Logger.error(t.getMessage)
          None
      },
      2500 millis
    )

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
    * Query URL for favicon. Current strategy
    * 1. check root for favicon.ico
    * 2. check html head for link tag
    *
    * @param url : Url to verify
    * @return
    */
  private def querySource(url: String): Option[String] = {
    checkFaviconIco(url) match {
      case Some(favUrl) =>
        persistToDb(url, favUrl)
        Some(favUrl)
      case None =>
        checkHeadForLink(url) match {
          case Some(favUrl) =>
            persistToDb(url, favUrl)
            Some(favUrl)
          case None => None
        }
    }
  }

  /**
    * Entry method to check for favicon
    *
    * @return
    */
  def getFaviconForUrl = Action.async {

    request =>

      //Inner function to parse POST data and check for url and getFresh flag
      def parseRequestData(request: Request[AnyContent]): Option[(String, Boolean)] = {
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

      //Inner function to check for urls
      def getFavicon(url: String, getFresh: Boolean): Option[String] = {
        Try(
          {
            //If get fresh then we will skip db check and directly check value
            if (getFresh) querySource(url)
            else getURLFromDB(url) match {
              case Some(favUrl) => Some(favUrl)
              case _ => querySource(url)
            }
          }
        ).getOrElse(None)
      }

      parseRequestData(request) match {
        case Some((url, getFresh)) =>
          val urls = getUrls(url)
          Logger.info(s"Checking for $url -> ${urls.mkString(", ")}")
          var favUrl: Option[String] = None
          urls.view.find { u =>
            favUrl = getFavicon(u, getFresh)
            favUrl.isDefined
          }
          favUrl match {
            case Some(fUrl) =>
              persistToDb(url, fUrl)
              Future(Ok(fUrl))
            case None => Future(NotFound(s"No favicon found for $url"))
          }
        case None => Future(BadRequest("Invalid data in request"))
      }

  }

}
