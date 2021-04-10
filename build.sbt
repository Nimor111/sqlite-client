val scalaVersion_2_13 = "2.13.3"

version := "1.0"

inThisBuild(
  List(
    organization := "com.github.nimor111",
    homepage := Some(url("https://github.com/Nimor111/sqlite-client")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "Nimor111",
        "Georgi Bozhinov",
        "georgi.bojinov@hotmail.com",
        url("https://blog.gbojinov.xyz")
      )
    )
  )
)

val commonSettings = Seq(
  scalaVersion := scalaVersion_2_13,
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    "org.xerial" % "sqlite-jdbc" % "3.34.0",
    "org.scalatest" %% "scalatest" % "3.2.2" % "test"
  ),
  name := "sqlite-client"
)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(moduleName := "sqlite-client")
  .settings(name += "-core")

lazy val example = project
  .in(file("example"))
  .settings(commonSettings)
  .settings(moduleName := "sqlite-client-example")
  .settings(name += "-example")
  .dependsOn(core)

lazy val microsite = project
  .in(file("microsite"))
  .settings(
    scalaVersion := scalaVersion_2_13,
    micrositeName := "sqlite-client",
    micrositeDescription := "A functional wrapper around the JDBC API for sqlite",
    micrositeAuthor := "Georgi Bozhinov",
    micrositeGithubOwner := "Nimor111",
    micrositeGithubRepo := "sqlite-client",
    skip in publish := true
  )
  .enablePlugins(MicrositesPlugin)
  .dependsOn(core)

lazy val sqliteClient = project
  .in(file("."))
  .settings(commonSettings)
  .aggregate(core, example, microsite)
