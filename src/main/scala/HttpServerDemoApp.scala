import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl._
import scala.concurrent.{ Future }
import scala.util.{ Try, Success, Failure }
import akka.http.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

object HttpServerDemoApp extends App {

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  object persons {

    import spray.json._
    import akka.http.scaladsl.marshallers.sprayjson._

    case class Person(name: String, favoriteNumber: Int)

    object JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
      implicit val PersonFormat = jsonFormat2(Person)
    }

    def listPersons(): List[Person] = List(Person("Adam", 1), Person("Magda", 2), Person("Ula", 3), Person("Lech", 4))
  }

  import persons._
  import persons.JsonSupport._

  val route = logRequest("http") {
    pathPrefix("api") {
      pathPrefix("persons") {
        get {
          pathEnd {
            complete(listPersons())
          } ~
            path(IntNumber) { id =>
              complete(listPersons().find(p => p.favoriteNumber == id))
            }
        }
      }
    }
  }

  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()

  import system.dispatcher // for the future transformations
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.shutdown()) // and shutdown when done

}