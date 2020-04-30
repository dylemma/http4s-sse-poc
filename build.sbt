import java.util.Locale
import scala.sys.process.{ BasicIO, Process, ProcessLogger }
val Http4sVersion = "0.21.0-M5"
val LogbackVersion = "1.2.3"
val MonixVersion = "3.0.0"
val SpecsVersion = "4.1.0"
val NettyVersion = "4.1.42.Final"
val AhcVersion = "2.10.4"

val MainClassName = "com.codedx.mltriageserver.WebApp"

organization := "com.codedx"
name := "http4s-sse"
version := "0.1"
scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
	"org.http4s" %% "http4s-blaze-server" % Http4sVersion,
	"org.http4s" %% "http4s-blaze-client" % Http4sVersion,
	"org.http4s" %% "http4s-play-json" % Http4sVersion,
	"org.http4s" %% "http4s-dsl" % Http4sVersion,
	"io.monix" %% "monix" % MonixVersion,
	"ch.qos.logback" % "logback-classic" % LogbackVersion,
	"org.scodec" %% "scodec-core" % "1.11.4",
	"org.scodec" %% "scodec-stream" % "2.0.0",

	// AHC dependencies for http client logic
	"org.asynchttpclient" % "async-http-client" % "2.10.4"
		exclude("io.netty", "netty-handler"),
	"io.netty" % "netty-all" % "4.1.42.Final",
	"io.netty" % "netty-handler" % "4.1.42.Final",
	"commons-io" % "commons-io" % "2.4"
)

// used in the http4s demo project; adds conveniences for higher-kinds and for-comprehensions
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.0")

scalacOptions ++= Seq(
	"-deprecation",
	"-encoding", "UTF-8",
	"-language:higherKinds",
	"-feature",
	"-Ypartial-unification",
	"-Xfatal-warnings"
)

val buildFrontend = TaskKey[Unit]("build-frontend")
buildFrontend := {
	val baseCmd = List("npm", "run", "build")
	val cmd =
		if (System.getProperty("os.name").toLowerCase(Locale.US) startsWith "windows") "cmd" :: "/c" :: baseCmd
		else baseCmd

	val exit = Process(cmd, file("./frontend"))
		.run(BasicIO.standard(false))
		.exitValue
	if (exit != 0) {
		throw new Exception(s"`npm run build` failed with exit code $exit")
	} else {
		println("Frontend build OK")
	}
}

(compile in Compile) := { (compile in Compile) dependsOn buildFrontend }.value