object AkkaHttpExamples {

  import scala.concurrent.Future
  import scala.util.Try
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
            HttpResponse(entity = HttpEntity(MediaTypes.`text/html`,
              "<html><body>Hello world!</body></html>"))

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
            mapInnerRoute { route =>
              ctx =>
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

        }

      }

    }

  }
}

