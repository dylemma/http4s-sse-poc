package com.codedx.demo

import cats.effect.{ Blocker, ExitCode, Resource }
import monix.eval.{ Task, TaskApp }
import org.http4s.{ HttpRoutes, Request, ServerSentEvent }
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.implicits._
import org.http4s.server.staticcontent._
import fs2.{ Chunk, Stream }
import cats.implicits._
import org.http4s.server.Router
import scala.concurrent.duration._
import scala.util.control.NonFatal

import cats.Inject
import cats.effect.concurrent.Ref
import fs2.concurrent.{ SignallingRef, Topic }
import org.http4s.headers.`Last-Event-Id`
import org.http4s.server.middleware.{ Logger => LoggerMiddleware }

object Main extends TaskApp {
	val Http4sTaskDsl = new Http4sDsl[Task] {}
	import Http4sTaskDsl._

	def run(args: List[String]): Task[ExitCode] = {
		val serverAsStream = for {
			staticFileRoutes <- Stream.resource(staticFilesR)
			exitRequested <- Stream.eval(SignallingRef[Task, Boolean](false))
			exitRef <- Stream.eval(Ref[Task].of(ExitCode.Success))
			_ <- Stream.eval(getExitFromStdin(exitRequested).start)
			liveEvents <- Stream.resource(makeLiveEventsSource)
			exitCode <- BlazeServerBuilder[Task]
				.bindLocal(9090)
				.withHttpApp(LoggerMiddleware.httpApp(logHeaders = false, logBody = false) {
					// the actual REST server logic
					Router(
						"/api" -> routes(liveEvents),
						"/" -> staticFileRoutes,
					).orNotFound
				})
				.serveWhile(exitRequested, exitRef)
		} yield exitCode

		serverAsStream.compile.lastOrError
	}

	def getExitFromStdin(exitRef: SignallingRef[Task, Boolean]): Task[Unit] = {
		var didSayPrompt = false
		var requestedExit = false

		def awaitExitLine: Unit = {
			while (!requestedExit) {
				Console.in.readLine match {
					case "exit" | "quit" =>
						requestedExit = true
						println("Goodbye!")
					case line =>
						if (!didSayPrompt) {
							println("Type 'exit' or 'quit' on its own line to stop the server")
							didSayPrompt = true
						}
						awaitExitLine
				}
			}
		}

		Task { awaitExitLine }.executeAsync >> exitRef.set(true) >> Task { println("Really goodbye") }
	}

	type SseTopic = Topic[Task, SseBuffer[Int]]

	def makeLiveEventsSource: Resource[Task, SseTopic] = {
		val init = new SseBuffer[Int](1024)
		val counterStream: Stream[Task, SseBuffer[Int]] = Stream.unfoldEval(init -> 0) { case (buf, nextId) =>
			val nextBuf = buf.push(nextId)
			val nextState = (nextBuf, nextId + 1)
			Task.pure(Option(nextBuf -> nextState)).delayResult(1.second)
		}

		Resource {
			for {
				topic <- Topic[Task, SseBuffer[Int]](init)
				counterFiber <- topic.publish(counterStream).compile.drain.start
			} yield {
				val close = counterFiber.cancel
				topic -> close
			}
		}
	}

	def subscribeLiveEvents(eventSource: SseTopic, lastEventId: Option[Int]): Stream[Task, Int] = {
		eventSource.subscribe(maxQueued = 512) // publishers will block if a subscriber has more than `maxQueued` events queued
			.drop(1) // because Topic publishes its "current" state and we only want updates
			.filter(!_.isEmpty) // avoid initial states before there is any buffer of events
			.zipWithPrevious.map { case (prevBuf, buf) =>
				// since the Topic is represented as a rolling buffer of events,
				// we need to find the *new* events between the current `buf` and the `prevBuf`
				val diffIterable: Iterable[Int] = prevBuf match {
					case None => new Iterable[Int] {def iterator = buf.iterator}
					case Some(prev) => buf.diffSince(prev)
				}
				val latest = buf.lastOption.get // the filter(!_.isEmpty) should guarantee that `buf.last` exists
				(diffIterable, latest)
			}
			.zipWithIndex.flatMap { case ((diffIterable, latestItem), index) =>
				if (index == 0 && lastEventId.isEmpty) {
					// for the first event, if lastEventId is not given, we ignore the buffer and just emit the last item from the buffer.
					Stream[Task, Int](latestItem)
				} else {
					// otherwise, take the diff and discard whatever events come from before the `lastEventId`
					val filteredIterable: Iterable[Int] = lastEventId match {
						case None => diffIterable
						case Some(threshold) => new Iterable[Int] {
							def iterator = diffIterable.iterator.dropWhile(_ <= threshold)
						}
					}
					Stream.chunk[Task, Int](Chunk.iterable(filteredIterable))
				}
			}
	}

	object LastEventIdParam extends OptionalQueryParamDecoderMatcher[String]("last-event-id")

	def routes(liveEventsSource: SseTopic): HttpRoutes[Task] = HttpRoutes.of[Task] {
		case GET -> Root => Ok("Hello world")
		case GET -> Root / "hello" => Ok("Hello there")
		case req @ PUT -> Root / "cool-data" => ???
		case req @ GET -> Root / "sse" :? LastEventIdParam(idOpt) => {
			println(s"Got request for SSE:\n[\n$req\n]\n")
			handleSse(req, idOpt, liveEventsSource)
		}
		case x if {println(s"\n\nunmatched route: \n[[$x]]\n\n"); false } => ???
	}

	def staticFilesR = Blocker[Task].map { blocker =>
		fileService[Task](FileService.Config("./frontend/build", blocker))
	}

	object MyEventId extends Inject[Int, ServerSentEvent.EventId] {
		def unapply(id: String) = try Some(id.toInt) catch {case e: NumberFormatException => None}
		def inj = id => ServerSentEvent.EventId(s"$id")
		def prj = sseId => unapply(sseId.value)
	}

	def handleSse(req: Request[Task], lastEventIdFromQuery: Option[String], liveEvents: SseTopic) = {
		// frontend script may add `?last-event-id=X` to the url query parameter in case the tab leadership changes.
		// browser will use the `Last-Event-Id` HTTP header if it needs to resume the stream.
		// Priority should be the header over the query param, but we could generalize as "use the largest id"
		val lastEventIdOpt = {
			req.headers.get(`Last-Event-Id`).map(_.id).flatMap(MyEventId.unapply)
				.orElse { lastEventIdFromQuery.flatMap(MyEventId.unapply) }
		}

		val stream = subscribeLiveEvents(liveEvents, lastEventIdOpt).map { id =>
			ServerSentEvent(s"data-$id", Some("counter"), Some(MyEventId(id)), None)
		}
//		val stream = Stream.fromIterator[Task](Iterator.from(0)).evalMap(Task.pure(_).delayResult(1.second)).map { i =>
//			ServerSentEvent(s"data-$i", Some("counter"), Some(ServerSentEvent.EventId(s"$i")), None)
//		}
		Ok(stream)
	}
}
