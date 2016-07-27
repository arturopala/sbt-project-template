object AkkaHttpExamples {

  import scala.concurrent.{Future}
  import scala.util.{Try, Success, Failure}
  import scala.util.control.NonFatal
  import akka.actor.ActorSystem
  import akka.stream.scaladsl._

  implicit val system = akka.actor.ActorSystem()
  implicit val materializer = akka.stream.ActorMaterializer()

  object http {

    import akka.http.scaladsl._
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model.headers._

    object outgoing {

      object connectionlevel {

        val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = Http().outgoingConnection("akka.io")
        val responseFuture: Future[HttpResponse] = Source.single(HttpRequest(uri = "/"))
          .via(connectionFlow)
          .runWith(Sink.head)

      }

      object hostlevel {

        val poolClientFlow = Http().cachedHostConnectionPool[Int]("akka.io")
        val responseFuture: Future[(Try[HttpResponse], Int)] = Source.single(HttpRequest(uri = "/") -> 42)
          .via(poolClientFlow)
          .runWith(Sink.head)

      }

      object requestlevel {

        // flow-based
        val superPoolFlow = Http().superPool[Int]()
        val responseFuture1: Future[(Try[HttpResponse], Int)] = Source.single(HttpRequest(uri = "/") -> 42)
          .via(superPoolFlow)
          .runWith(Sink.head)

        // future-based
        val responseFuture2: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = "http://akka.io"))

      }
    }

    object incoming {

      object lowlevel {

        val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] = Http().bind(interface = "localhost", port = 8080)
        val bindingFuture1: Future[Http.ServerBinding] = serverSource.to(Sink.foreach { connection => // foreach materializes the source
          println("Accepted new connection from "+connection.remoteAddress)
          // ... and then actually handle the connection
        }).run()

        val requestHandler: HttpRequest => HttpResponse = {
          case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
            HttpResponse(entity = HttpEntity(
              MediaTypes.`text/html`,
              "<html><body>Hello world!</body></html>"
            ))

          case HttpRequest(GET, Uri.Path("/ping"), _, _, _) =>
            HttpResponse(entity = "PONG!")

          case HttpRequest(GET, Uri.Path("/crash"), _, _, _) =>
            sys.error("BOOM!")

          case _: HttpRequest =>
            HttpResponse(404, entity = "Unknown resource!")
        }

        val bindingFuture2: Future[Http.ServerBinding] = serverSource.to(Sink.foreach { connection =>
          println("Accepted new connection from "+connection.remoteAddress)
          connection handleWithSyncHandler requestHandler
        }).run()

        Http()
          .bind("localhost", 8081)
          .runForeach { connection =>
            println("Accepted new connection from "+connection.remoteAddress)
            connection.handleWith(Flow[HttpRequest].map(requestHandler))
          }

      }

      object highlevel {

        import akka.http.scaladsl.server.Directives._
        import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

        object example1 {

          val route =
            path("hello") {
              get {
                complete {
                  <h1>Say hello to akka-http</h1>
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

        object example2 {

          import akka.actor.ActorRef
          import akka.util.Timeout
          import akka.pattern.ask
          import akka.http.scaladsl.marshalling.ToResponseMarshaller
          import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
          import akka.http.scaladsl.model.StatusCodes.MovedPermanently
          import akka.http.scaladsl.coding.Deflate

          // types used by the API routes
          type Money = Double // only for demo purposes, don't try this at home!
          type TransactionResult = String
          case class User(name: String)
          case class Order(email: String, amount: Money)
          case class Update(order: Order)
          case class OrderItem(i: Int, os: Option[String], s: String)
          implicit val seqOrderUM: FromRequestUnmarshaller[Order] = ???
          implicit val orderM: ToResponseMarshaller[Order] = ???
          implicit val seqOrderM: ToResponseMarshaller[Seq[Order]] = ???
          implicit val timeout: Timeout = ??? // for actor asks

          val route = {
            path("orders") {
              authenticateBasic(realm = "admin area", myAuthenticator) { user =>
                get {
                  encodeResponseWith(Deflate) {
                    complete {
                      // marshal custom object with in-scope marshaller
                      retrieveOrdersFromDB
                    }
                  }
                } ~
                  post {
                    // decompress gzipped or deflated requests if required
                    decodeRequest {
                      // unmarshal with in-scope unmarshaller
                      entity(as[Order]) { order =>
                        complete {
                          // ... write order to DB
                          "Order received"
                        }
                      }
                    }
                  }
              }
            } ~
              // extract URI path element as Int
              pathPrefix("order" / IntNumber) { orderId =>
                pathEnd {
                  (put | parameter('method ! "put")) {
                    // form extraction from multipart or www-url-encoded forms
                    formFields('email, 'total.as[Money]).as(Order) { order =>
                      complete {
                        // complete with serialized Future result
                        (myDbActor ? Update(order)).mapTo[TransactionResult]
                      }
                    }
                  } ~
                    get {
                      // debugging helper
                      logRequest("GET-ORDER") {
                        // use in-scope marshaller to create completer function
                        completeWith(instanceOf[Order]) { completer =>
                          // custom
                          processOrderRequest(orderId, completer)
                        }
                      }
                    }
                } ~
                  path("items") {
                    get {
                      // parameters to case class extraction
                      parameters('size.as[Int], 'color ?, 'dangerous ? "no")
                        .as(OrderItem) { orderItem =>
                          // ... route using case class instance created from
                          // required and optional query parameters
                          complete {
                            // ... write order to DB
                            "Order item"
                          }
                        }
                    }
                  }
              } ~
              pathPrefix("documentation") {
                // optionally compresses the response with Gzip or Deflate
                // if the client accepts compressed responses
                encodeResponse {
                  // serve up static content from a JAR resource
                  getFromResourceDirectory("docs")
                }
              } ~
              path("oldApi" / Rest) { pathRest =>
                redirect("http://oldapi.example.com/"+pathRest, MovedPermanently)
              }
          }

          // backend entry points
          def myAuthenticator: Authenticator[User] = ???
          def retrieveOrdersFromDB: Seq[Order] = ???
          def myDbActor: ActorRef = ???
          def processOrderRequest(id: Int, complete: Order => Unit): Unit = ???

        }

        object example3 {

          val route =
            get {
              pathSingleSlash {
                complete {
                  <html>
                    <body>Hello world!</body>
                  </html>
                }
              } ~
                path("ping") {
                  complete("PONG!")
                } ~
                path("crash") {
                  sys.error("BOOM!")
                }
            }

          // `route` will be implicitly converted to `Flow` using `RouteResult.route2HandlerFlow`
          Http().bindAndHandle(route, "localhost", 8080)

        }

        object directives {

          import akka.http.scaladsl.server._

          //type Route = RequestContext ⇒ Future[RouteResult]
          // Route instance as a function literal:
          val route1: Route = { ctx => ctx.complete("yeah") }
          // or shorter
          val route2: Route = _.complete("yeah")
          // or with directive
          val route3 = complete("yeah")

          val route4 =
            get {
              complete("Received GET")
            } ~
              complete("Received something else")

          val route5 = path("order" / IntNumber) { id =>
            (get | put) { ctx =>
              ctx.complete(s"Received ${ctx.request.method.name} request for order $id")
            }
          }

          val route6 = path("order" / IntNumber) { id =>
            (get | put) {
              extractMethod { m =>
                complete(s"Received ${m.name} request for order $id")
              }
            }
          }

          val getOrPut = get | put
          val route7 = path("order" / IntNumber) { id =>
            getOrPut {
              extractMethod { m =>
                complete(s"Received ${m.name} request for order $id")
              }
            }
          }

          val orderGetOrPutWithMethod = path("order" / IntNumber) & (get | put) & extractMethod
          val route8 = orderGetOrPutWithMethod { (id, m) =>
            complete(s"Received ${m.name} request for order $id")
          }

          val route9 = cancelRejection(MethodRejection(HttpMethods.POST)) {
            post {
              complete("Result")
            }
          }

          def isMethodRejection: Rejection => Boolean = {
            case MethodRejection(_) => true
            case _                  => false
          }

          val route10 = cancelRejections(isMethodRejection) {
            post {
              complete("Result")
            }
          }

          // Calculates a value from the request context and provides the value to the inner route.
          // The extract directive is used as a building block for Custom Directives to extract data 
          // from the RequestContext and provide it to the inner route. It is a special case for extracting 
          // one value of the more general textract directive that can be used to extract more than one value.
          val uriLength = extract(_.request.uri.toString.length)
          val route11 = uriLength { len =>
            complete(s"The length of the request URI is $len")
          }

          //Extracts the complete HttpRequest instance.
          val route12 = extractRequest { request =>
            complete(s"Request method is ${request.method.name} and content-type is ${request.entity.contentType}")
          }

          //Extracts the unmatched path from the request context.
          //The extractUnmatchedPath directive extracts the remaining path that was not yet matched 
          //by any of the PathDirectives (or any custom ones that change the unmatched path field of the request context). 
          //You can use it for building directives that handle complete suffixes of paths 
          //(like the getFromDirectory directives and similar ones).
          //Use mapUnmatchedPath to change the value of the unmatched path.

          val route13 = pathPrefix("abc") {
            extractUnmatchedPath { remaining =>
              complete(s"Unmatched: '$remaining'")
            }
          }

          //Access the full URI of the request.
          //Use SchemeDirectives, HostDirectives, PathDirectives, and ParameterDirectives for more targeted access to parts of the URI.
          val route14 = extractUri { uri =>
            complete(s"Full URI: $uri")
          }

          //Changes the execution model of the inner route by wrapping it with arbitrary logic.
          //The mapInnerRoute directive is used as a building block for Custom Directives to replace the inner route with any other route. 
          //Usually, the returned route wraps the original one with custom execution logic.
          val completeWithInnerException =
            mapInnerRoute { route => ctx =>
              try {
                route(ctx)
              }
              catch {
                case NonFatal(e) => ctx.complete(s"Got ${e.getClass.getSimpleName} '${e.getMessage}'")
              }
            }

          val route15 = completeWithInnerException {
            complete(throw new IllegalArgumentException("BLIP! BLOP! Everything broke"))
          }

          //Transforms the list of rejections the inner route produced.
          //The mapRejections directive is used as a building block for Custom Directives to transform a list of rejections from the inner route to a new list of rejections.
          val replaceByAuthorizationFailed = mapRejections(_ => List(AuthorizationFailedRejection))
          val route16 = replaceByAuthorizationFailed {
            path("abc")(complete("abc"))
          }

          //Transforms the request before it is handled by the inner route.
          //before it is handled by the inner route. Changing the request.uri parameter has no effect on path 
          //matching in the inner route because the unmatched path is a separate field of the RequestContext 
          //value which is passed into routes. To change the unmatched path or other fields of the RequestContext 
          //use the mapRequestContext directive.
          def transformToPostRequest(req: HttpRequest): HttpRequest = req.copy(method = HttpMethods.POST)
          val route17 = mapRequest(transformToPostRequest) {
            extractRequest { req =>
              complete(s"The request method was ${req.method.name}")
            }
          }

          //Transforms the RequestContext before it is passed to the inner route.
          //The mapRequestContext directive is used as a building block for Custom Directives to transform the request context 
          //before it is passed to the inner route. To change only the request value itself the mapRequest directive can be 
          //used instead.
          val replaceRequest = mapRequestContext(_.withRequest(HttpRequest(HttpMethods.POST)))
          val route18 = replaceRequest {
            extractRequest { req =>
              complete(req.method.value)
            }
          }

          //Changes the response that was generated by the inner route.
          //The mapResponse directive is used as a building block for Custom Directives to transform a response that was generated 
          //by the inner route. This directive transforms complete responses.
          def overwriteResultStatus(response: HttpResponse): HttpResponse = response.copy(status = StatusCodes.BadGateway)
          val route19 = mapResponse(overwriteResultStatus)(complete("abc"))

          //Changes the response entity that was generated by the inner route.
          //The mapResponseEntity directive is used as a building block for Custom Directives to transform a response entity 
          //that was generated by the inner route.
          import akka.util.ByteString
          def prefixEntity(entity: ResponseEntity): ResponseEntity = entity match {
            case HttpEntity.Strict(contentType, data) =>
              HttpEntity.Strict(contentType, ByteString("test") ++ data)
            case _ => throw new IllegalStateException("Unexpected entity type")
          }
          val prefixWithTest: Directive0 = mapResponseEntity(prefixEntity)
          val route20 = prefixWithTest(complete("abc"))

          //Changes the list of response headers that was generated by the inner route.
          //The mapResponseHeaders directive is used as a building block for Custom Directives to transform the list of response 
          //headers that was generated by the inner route.
          val echoRequestHeaders = extract(_.request.headers).flatMap(respondWithHeaders)
          val removeIdHeader = mapResponseHeaders(_.filterNot(_.lowercaseName == "id"))
          val route21 = removeIdHeader {
            echoRequestHeaders {
              complete("test")
            }
          }

          //Changes the message the inner route sends to the responder.
          //The mapRouteResult directive is used as a building block for Custom Directives to transform 
          //the RouteResult coming back from the inner route. It's similar to the mapRouteResult directive 
          //but allows to specify a partial function that doesn't have to handle all potential RouteResult instances.
          case object MyCustomRejection extends Rejection
          import RouteResult._
          val rejectRejections = // not particularly useful directive
            mapRouteResultPF {
              case Rejected(_) => Rejected(List(AuthorizationFailedRejection))
            }
          val route22 =
            rejectRejections {
              reject(MyCustomRejection)
            }

          //Transforms the unmatchedPath field of the request context for inner routes.
          //The mapUnmatchedPath directive is used as a building block for writing Custom Directives. 
          //You can use it for implementing custom path matching directives.
          def ignore456(path: Uri.Path) = path match {
            case s @ Uri.Path.Segment(head, tail) if head.startsWith("456") =>
              val newHead = head.drop(3)
              if (newHead.isEmpty) tail
              else s.copy(head = head.drop(3))
            case _ => path
          }
          val ignoring456 = mapUnmatchedPath(ignore456)
          val route23 =
            pathPrefix("123") {
              ignoring456 {
                path("abc") {
                  complete(s"Content")
                }
              }
            }

          //A directive that passes the request unchanged to its inner route.
          //The directive is usually used as a "neutral element" when combining directives generically.
          /*Get("/") ~> pass(complete("abc")) ~> check {
            responseAs[String] shouldEqual "abc"
          }*/

          //Provides a constant value to the inner route.
          def providePrefixedString(value: String): Directive1[String] = provide("prefix:"+value)
          val route24 =
            providePrefixedString("test") { value =>
              complete(value)
            }

          //Calculates a tuple of values from the request context and provides them to the inner route.
          //The textract directive is used as a building block for Custom Directives to extract data from 
          //the RequestContext and provide it to the inner route. To extract just one value use the extract directive. 
          //To provide a constant value independent of the RequestContext use the tprovide directive instead.
          val pathAndQuery = textract { ctx =>
            val uri = ctx.request.uri
            (uri.path, uri.query)
          }
          val route25 = pathAndQuery { (p, query) =>
            complete(s"The path is $p and the query is $query")
          }

          //Provides a tuple of values to the inner route.
          //The tprovide directive is used as a building block for Custom Directives to provide data to the inner route. 
          //To provide just one value use the provide directive. If you want to provide values calculated from the 
          //RequestContext use the textract directive instead.
          def provideStringAndLength(value: String) = tprovide((value, value.length))
          val route26 =
            provideStringAndLength("test") { (value, len) =>
              complete(s"Value is $value and its length is $len")
            }

          //conditional
          //Wraps its inner route with support for Conditional Requests
          //Depending on the given eTag and lastModified values this directive immediately responds with 304 Not Modified 
          //or 412 Precondition Failed (without calling its inner route) if the request comes with the respective 
          //conditional headers. Otherwise the request is simply passed on to its inner route
          val route27 = conditional(new EntityTag("aaa")) {
            complete("abcde")
          }

          //Tries to decode the request with the specified Decoder or rejects the request with an UnacceptedRequestEncodingRejection(supportedEncoding).
          val route28 = decodeRequest {
            entity(as[String]) { content: String =>
              complete(s"Request content: '$content'")
            }
          }

          import akka.http.scaladsl.coding._

          val route28a = decodeRequestWith(Gzip) {
            entity(as[String]) { content: String =>
              complete(s"Request content: '$content'")
            }
          }

          //Tries to encode the response with the specified Encoder or 
          //rejects the request with an UnacceptedResponseEncodingRejection(supportedEncodings).
          val route29 = encodeResponse { complete("content") }
          val route29a = encodeResponseWith(Gzip) { complete("content") }

          //Extracts a cookie with a given name from a request or otherwise rejects the request with 
          //a MissingCookieRejection if the cookie is missing.
          val route30 =
            cookie("userName") { nameCookie =>
              complete(s"The logged in user is '${nameCookie.value}'")
            }

          //Adds a header to the response to request the removal of the cookie with the given name on the client.
          val route31 =
            deleteCookie("userName") {
              complete("The user was logged out")
            }

          //Extracts an optional cookie with a given name from a request.
          val route32 =
            optionalCookie("userName") {
              case Some(nameCookie) => complete(s"The logged in user is '${nameCookie.value}'")
              case None             => complete("No user logged in")
            }

          //Adds a header to the response to request the update of the cookie with the given name on the client.
          val route33 =
            setCookie(HttpCookie("userName", value = "paul")) {
              complete("The user was logged in")
            }

          import akka.http.scaladsl.server.directives._

          //Logs the request.
          import akka.event.Logging
          DebuggingDirectives.logRequest("get-user")
          DebuggingDirectives.logRequest("get-user", Logging.InfoLevel)

          def requestMethod(req: HttpRequest): String = req.method.toString
          DebuggingDirectives.logRequest(requestMethod _)

          def requestMethodAsInfo(req: HttpRequest): LogEntry = LogEntry(req.method.toString, Logging.InfoLevel)
          DebuggingDirectives.logRequest(requestMethodAsInfo _)

          def printRequestMethod(req: HttpRequest): Unit = println(req.method)
          val logRequestPrintln = DebuggingDirectives.logRequest(LoggingMagnet(_ => printRequestMethod))

          //Logs request and response.
          DebuggingDirectives.logRequestResult("get-user")
          DebuggingDirectives.logRequestResult("get-user", Logging.InfoLevel)

          def requestMethodAndResponseStatusAsInfo(req: HttpRequest): Any => Option[LogEntry] = {
            case res: HttpResponse => Some(LogEntry(req.method+":"+res.status, Logging.InfoLevel))
            case _                 => None // other kind of responses
          }
          DebuggingDirectives.logRequestResult(requestMethodAndResponseStatusAsInfo _)

          def printRequestMethodAndResponseStatus(req: HttpRequest)(res: Any): Unit =
            println(requestMethodAndResponseStatusAsInfo(req)(res).map(_.obj.toString).getOrElse(""))
          val logRequestResultPrintln = DebuggingDirectives.logRequestResult(LoggingMagnet(_ => printRequestMethodAndResponseStatus))

          //Logs the response.
          DebuggingDirectives.logResult("get-user")

          DebuggingDirectives.logResult("get-user", Logging.InfoLevel)

          def responseStatus(res: Any): String = res match {
            case x: HttpResponse => x.status.toString
            case _               => "unknown response part"
          }
          DebuggingDirectives.logResult(responseStatus _)

          def responseStatusAsInfo(res: Any): LogEntry = LogEntry(responseStatus(res), Logging.InfoLevel)
          DebuggingDirectives.logResult(responseStatusAsInfo _)

          def printResponseStatus(res: Any): Unit = println(responseStatus(res))
          val logResultPrintln = DebuggingDirectives.logResult(LoggingMagnet(_ => printResponseStatus))

          //Catches exceptions thrown by the inner route and handles them using the specified ExceptionHandler.
          val divByZeroHandler = ExceptionHandler {
            case _: ArithmeticException => complete(StatusCodes.BadRequest, "You've got your arithmetic wrong, fool!")
          }
          val route34 =
            path("divide" / IntNumber / IntNumber) { (a, b) =>
              handleExceptions(divByZeroHandler) {
                complete(s"The result is ${a / b}")
              }
            }

          //Handles rejections produced by the inner route and handles them using the specified RejectionHandler.
          val totallyMissingHandler = RejectionHandler.newBuilder()
            .handleNotFound { complete(StatusCodes.NotFound, "Oh man, what you are looking for is long gone.") }
            .result()
          val route35 = pathPrefix("handled") {
            handleRejections(totallyMissingHandler) {
              path("existing")(complete("This path exists"))
            }
          }

          //Extracts fields from POST requests generated by HTML forms.
          val route36 = formFields('color, 'age.as[Int]) { (color, age) =>
            complete(s"The color is '$color' and the age ten years ago was ${age - 10}")
          }

          case class Color(red: Int, green: Int, blue: Int)
          val route37 = path("color") {
            formFields('red.as[Int], 'green.as[Int], 'blue.as[Int]).as(Color) { color =>
              complete(s"RGB(${color.red},${color.green},${color.blue})")
            }
          }

          //Evaluates its parameter of type Future[T], and once the Future has been completed, 
          //extracts its result as a value of type Try[T] and passes it to the inner route.
          //The evaluation of the inner route passed to a onComplete directive is deferred 
          //until the given future has completed and provided with a extraction of type Try[T].
          import scala.concurrent.ExecutionContext.Implicits.global

          def divide(a: Int, b: Int): Future[Int] = Future {
            a / b
          }

          val route38 =
            path("divide" / IntNumber / IntNumber) { (a, b) =>
              onComplete(divide(a, b)) {
                case Success(value) => complete(s"The result was $value")
                case Failure(ex)    => complete(StatusCodes.InternalServerError, s"An error occurred: ${ex.getMessage}")
              }
            }

          //Evaluates its parameter of type Future[T], and once the Future has been completed successfully, 
          //extracts its result as a value of type T and passes it to the inner route.
          val route39 =
            path("success") {
              onSuccess(Future { "Ok" }) { extraction =>
                complete(extraction)
              }
            } ~
              path("failure") {
                onSuccess(Future.failed[String](new RuntimeException)) { extraction =>
                  complete(extraction)
                }
              }

          //Completes the request with the result of the computation given as argument of type Future[T] by marshalling it 
          //with the implicitly given ToResponseMarshaller[T]. Runs the inner route if the Future computation fails.
          val route40 =
            path("success") {
              completeOrRecoverWith(Future { "Ok" }) { extraction =>
                failWith(extraction) // not executed.
              }
            } ~
              path("failure") {
                completeOrRecoverWith(Future.failed[String](new IllegalStateException)) { extraction =>
                  failWith(extraction)
                }
              }

          //Traverses the list of request headers with the specified function and extracts the first value 
          //the function returns as Some(value).
          def extractHostPort: HttpHeader => Option[Int] = {
            case h: `Host` => Some(h.port)
            case x         => None
          }

          val route41 = headerValue(extractHostPort) { port =>
            complete(s"The port was $port")
          }

          //Extracts the value of the HTTP request header with the given name.
          val route42 = headerValueByName("X-User-Id") { userId =>
            complete(s"The user is $userId")
          }

          //Traverses the list of request headers and extracts the first header of the given type.
          val route43 = headerValueByType[Origin]() { origin ⇒
            complete(s"The first origin was ${origin.origins.head}")
          }

          //Calls the specified partial function with the first request header the function 
          //is isDefinedAt and extracts the result of calling the function.
          def extractHostPort2: PartialFunction[HttpHeader, Int] = {
            case h: `Host` => h.port
          }

          val route44 = headerValuePF(extractHostPort2) { port =>
            complete(s"The port was $port")
          }

          //Traverses the list of request headers with the specified function and extracts the 
          //first value the function returns as Some(value).
          val route45 = optionalHeaderValue(extractHostPort) { port =>
            complete(s"The port was $port")
          }

          //Extracts the hostname part of the Host header value in the request.
          val route46 = extractHost { hn =>
            complete(s"Hostname: $hn")
          }

          //Filter requests matching conditions against the hostname part of the Host header value in the request.
          val route47 = host("api.company.com", "rest.company.com") {
            complete("Ok")
          }

          //Uses the marshaller for a given type to produce a completion function that is passed to its inner route. 
          //You can use it to decouple marshaller resolution from request completion.
          import spray.json._
          import akka.http.scaladsl.marshallers.sprayjson._
          case class Person(name: String, favoriteNumber: Int)
          object PersonJsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
            implicit val PortofolioFormats = jsonFormat2(Person)
          }
          import PersonJsonSupport._
          val findPerson = (f: Person => Unit) => {
            //... some processing logic...
            //complete the request
            f(Person("Jane", 42))
          }
          val route48 = get {
            completeWith(instanceOf[Person]) { completionFunction => findPerson(completionFunction) }
          }

          //Unmarshalls the request entity to the given type and passes it to its inner Route. 
          //An unmarshaller returns an Either with Right(value) if successful or Left(exception) for a failure. 
          //The entity method will either pass the value to the inner route or map the exception 
          //to a akka.http.scaladsl.server.Rejection.
          val route49 = post {
            entity(as[Person]) { person =>
              complete(s"Person: ${person.name} - favorite number: ${person.favoriteNumber}")
            }
          }

          //Completes the request using the given function. 
          //The input to the function is produced with the in-scope entity unmarshaller and the result 
          //value of the function is marshalled with the in-scope marshaller. handleWith can be a convenient 
          //method combining entity with complete.
          val updatePerson = (person: Person) => {
            //... some processing logic...
            //return the person
            person
          }
          val route50 = post {
            handleWith(updatePerson)
          }

          //Matches requests with HTTP method DELETE.
          val route51 = delete { complete("This is a DELETE request.") }

          //Matches requests with HTTP method GET.
          val route52 = get { complete("This is a GET request.") }

          //Matches HTTP requests based on their method
          val route53 = method(HttpMethods.PUT) { complete("This is a PUT request.") }

          //Provides the value of X-Forwarded-For, Remote-Address, or X-Real-IP headers as an instance of HttpIp
          val route54 = extractClientIP { ip =>
            complete("Client's ip is "+ip.toOption.map(_.getHostAddress).getOrElse("unknown"))
          }

          //Replaces a response with no content with an empty rejection.
          val route55 = rejectEmptyResponse {
            path("even" / IntNumber) { i =>
              complete {
                // returns Some(evenNumberDescription) or None
                Option(i).filter(_ % 2 == 0).map { num =>
                  s"Number $num is even."
                }
              }
            }
          }

          //A filter that checks if the request entity is empty and only then passes processing to the inner route. 
          //Otherwise, the request is rejected. The opposite filter is available as requestEntityPresent.
          val route56 = requestEntityEmpty {
            complete("request entity empty")
          } ~
            requestEntityPresent {
              complete("request entity present")
            }

          //Checks an arbitrary condition and passes control to the inner route if it returns true. Otherwise, 
          //rejects the request with a ValidationRejection containing the given error message.
          val route57 = extractUri { uri =>
            validate(uri.path.toString.size < 5, s"Path too long: '${uri.path.toString}'") {
              complete(s"Full URI: $uri")
            }
          }

          //The parameters directive filters on the existence of several query parameters and extract their values.
          val route58 = parameters('color, 'backgroundColor) { (color, backgroundColor) =>
            complete(s"The color is '$color' and the background is '$backgroundColor'")
          }
          val route58a = parameters('color, 'backgroundColor.?) { (color, backgroundColor) =>
            val backgroundStr = backgroundColor.getOrElse("<undefined>")
            complete(s"The color is '$color' and the background is '$backgroundStr'")
          }
          val route58b = parameters('color, 'backgroundColor ? "white") { (color, backgroundColor) =>
            complete(s"The color is '$color' and the background is '$backgroundColor'")
          }
          val route58c = parameters('color, 'backgroundColor ? "white") { (color, backgroundColor) =>
            complete(s"The color is '$color' and the background is '$backgroundColor'")
          }
          val route58d = parameters('color, 'count.as[Int]) { (color, count) =>
            complete(s"The color is '$color' and you have $count of it.")
          }

          //Extracts all parameters at once as a Map[String, String] mapping parameter names to parameter values
          val route59 = parameterMap { params =>
            def paramString(param: (String, String)): String = s"""${param._1} = '${param._2}'"""
            complete(s"The parameters are ${params.map(paramString).mkString(", ")}")
          }

          //Extracts all parameters at once as a multi-map of type Map[String, List[String] mapping a parameter name to a list of all its values.
          val route60 = parameterMultiMap { params =>
            complete(s"There are parameters ${params.map(x => x._1+" -> "+x._2.size).mkString(", ")}")
          }

          //Extracts all parameters at once in the original order as (name, value) tuples of type (String, String).
          val route61 = parameterSeq { params =>
            def paramString(param: (String, String)): String = s"""${param._1} = '${param._2}'"""
            complete(s"The parameters are ${params.map(paramString).mkString(", ")}")
          }

          //Matches the complete unmatched path of the RequestContext against the given PathMatcher, 
          //potentially extracts one or more values (depending on the type of the argument).
          val route62 =
            path("foo") {
              complete("/foo")
            } ~
              path("foo" / "bar") {
                complete("/foo/bar")
              } ~
              pathPrefix("ball") {
                pathEnd {
                  complete("/ball")
                } ~
                  path(IntNumber) { int =>
                    complete(if (int % 2 == 0) "even ball" else "odd ball")
                  }
              }

          //Only passes the request to its inner route if the unmatched path of the RequestContext is empty, i.e. 
          //the request path has been fully matched by a higher-level path or pathPrefix directive.
          val route63 = pathPrefix("foo") {
            pathEnd {
              complete("/foo")
            } ~
              path("bar") {
                complete("/foo/bar")
              }
          }

          //Only passes the request to its inner route if the unmatched path of the RequestContext 
          //is either empty or contains only one single slash.
          val route64 = pathPrefix("foo") {
            pathEndOrSingleSlash {
              complete("/foo")
            } ~
              path("bar") {
                complete("/foo/bar")
              }
          }

          //Matches and consumes a prefix of the unmatched path of the RequestContext against the given PathMatcher, 
          //potentially extracts one or more values (depending on the type of the argument).
          val route65 = pathPrefix("ball") {
            pathEnd {
              complete("/ball")
            } ~
              path(IntNumber) { int =>
                complete(if (int % 2 == 0) "even ball" else "odd ball")
              }
          }

          //Checks whether the unmatched path of the RequestContext has a prefix matched by the given PathMatcher. 
          //Potentially extracts one or more values (depending on the type of the argument) but doesn't consume its 
          //match from the unmatched path.
          val completeWithUnmatchedPath =
            extractUnmatchedPath { p =>
              complete(p.toString)
            }

          val route66 =
            pathPrefixTest("foo" | "bar") {
              pathPrefix("foo") { completeWithUnmatchedPath } ~
                pathPrefix("bar") { completeWithUnmatchedPath }
            }

          //Only passes the request to its inner route if the unmatched path of 
          //the RequestContext contains exactly one single slash.
          val route67 =
            pathSingleSlash {
              complete("root")
            } ~
              pathPrefix("ball") {
                pathSingleSlash {
                  complete("/ball/")
                } ~
                  path(IntNumber) { int =>
                    complete(if (int % 2 == 0) "even ball" else "odd ball")
                  }
              }

          //Matches and consumes a suffix of the unmatched path of the RequestContext 
          //against the given PathMatcher, potentially extracts one or more values 
          //(depending on the type of the argument). 
          val route68 =
            pathPrefix("start") {
              pathSuffix("end") {
                completeWithUnmatchedPath
              } ~
                pathSuffix("foo" / "bar" ~ "baz") {
                  completeWithUnmatchedPath
                }
            }

          //Matches and consumes a prefix of the unmatched path of the RequestContext against the given PathMatcher, 
          //potentially extracts one or more values (depending on the type of the argument).
          val route69 = pathPrefix("foo") {
            rawPathPrefix("bar") { completeWithUnmatchedPath } ~
              rawPathPrefix("doo") { completeWithUnmatchedPath }
          }

          //Path Matcher API

          // matches /foo/
          path("foo" /)

          // matches e.g. /foo/123 and extracts "123" as a String
          path("foo" / """\d+""".r)

          // matches e.g. /foo/bar123 and extracts "123" as a String
          path("foo" / """bar(\d+)""".r)

          // similar to `path(Segments)`
          path(Segment.repeat(10, separator = Slash))

          // matches e.g. /i42 or /hCAFE and extracts an Int
          path("i" ~ IntNumber | "h" ~ HexIntNumber)

          // identical to path("foo" ~ (PathEnd | Slash))
          path("foo" ~ Slash.?)

          // matches /red or /green or /blue and extracts 1, 2 or 3 respectively
          path(Map("red" -> 1, "green" -> 2, "blue" -> 3))

          // matches anything starting with "/foo" except for /foobar
          pathPrefix("foo" ~ !"bar")

        }

        object websocket {

          import akka.util.ByteString
          import akka.http.scaladsl.model.ws._

          val greeterWebsocketService =
            Flow[Message]
              .mapConcat {
                // we match but don't actually consume the text message here,
                // rather we simply stream it back as the tail of the response
                // this means we might start sending the response even before the
                // end of the incoming message has been received
                case tm: TextMessage ⇒ TextMessage(Source.single("Hello ") ++ tm.textStream) :: Nil
                case bm: BinaryMessage =>
                  // ignore binary messages but drain content to avoid the stream being clogged
                  bm.dataStream.runWith(Sink.ignore)
                  Nil
              }

          val requestHandler: HttpRequest ⇒ HttpResponse = {
            case req @ HttpRequest(GET, Uri.Path("/greeter"), _, _, _) ⇒
              req.header[UpgradeToWebsocket] match {
                case Some(upgrade) ⇒ upgrade.handleMessages(greeterWebsocketService)
                case None          ⇒ HttpResponse(400, entity = "Not a valid websocket request!")
              }
            case _: HttpRequest ⇒ HttpResponse(404, entity = "Unknown resource!")
          }

          val route =
            path("greeter") {
              get {
                handleWebsocketMessages(greeterWebsocketService)
              }
            }

        }

      }

    }

  }
}

