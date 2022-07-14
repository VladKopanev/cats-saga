import sbt.file

name := "cats-saga"

val mainScala = "2.13.8"
val allScala  = Seq(mainScala, "2.12.15", "3.0.2")

inThisBuild(
  List(
    organization := "com.vladkopanev",
    homepage := Some(url("https://github.com/VladKopanev/cats-saga")),
    licenses := List("MIT License" -> url("https://opensource.org/licenses/MIT")),
    developers := List(
      Developer(
        "VladKopanev",
        "Vladislav Kopanev",
        "ivengo53@gmail.com",
        url("http://vladkopanev.com")
      )
    ),
    scmInfo := Some(
      ScmInfo(url("https://github.com/VladKopanev/cats-saga"), "scm:git:git@github.com/VladKopanev/cats-saga.git")
    )
  )
)

lazy val commonSettings = Seq(
  scalaVersion := mainScala,
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-explaintypes",
    "-Yrangepos",
    "-feature",
    "-Xfuture",
    "-language:higherKinds",
    "-language:existentials",
    "-language:implicitConversions",
    "-unchecked",
    "-Xlint:_,-type-parameter-shadow",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
    "-Ywarn-value-discard"
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) =>
      Seq("-Ykind-projector", "-unchecked")
    case Some((2, 12)) =>
      Seq(
        "-Xsource:2.13",
        "-Yno-adapted-args",
        "-Ypartial-unification",
        "-Ywarn-extra-implicit",
        "-Ywarn-inaccessible",
        "-Ywarn-infer-any",
        "-Ywarn-nullary-override",
        "-Ywarn-nullary-unit",
        "-opt-inline-from:<source>",
        "-opt-warnings",
        "-opt:l:inline"
      )
    case _ => Nil
  }),
  resolvers ++= Seq(Resolver.sonatypeRepo("snapshots"), Resolver.sonatypeRepo("releases"))
)

lazy val root = project
  .in(file("."))
  .aggregate(core)

val catsVersion                = "3.3.14"
val catsRetryVersion           = "3.1.0"
val scalaTestVersion           = "3.2.12"
val kindProjectorVersion       = "0.13.2"
val disciplineCoreVersion      = "1.5.1"
val disciplineScalatestVersion = "2.1.5"

lazy val core = project
  .in(file("core"))
  .settings(
    commonSettings,
    name := "cats-saga",
    crossScalaVersions := allScala,
    libraryDependencies ++= Seq(
      "org.typelevel"    %% "cats-effect"          % catsVersion,
      "org.typelevel"    %% "cats-laws"            % "2.7.0" % Test,
      "org.typelevel"    %% "cats-effect-laws"     % catsVersion % Test,
      "org.typelevel"    %% "cats-effect-testkit"  % catsVersion % Test,
      "org.scalatest"    %% "scalatest"            % scalaTestVersion % Test,
      "org.typelevel"    %% "discipline-core"      % disciplineCoreVersion % Test,
      "org.typelevel"    %% "discipline-scalatest" % disciplineScalatestVersion % Test,
      "com.github.cb372" %% "cats-retry"           % catsRetryVersion % Optional
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((3, _)) =>
          Nil
        case _ =>
          List(compilerPlugin("org.typelevel" % "kind-projector" % kindProjectorVersion cross CrossVersion.full))
      }
    }
  )

val http4sVersion   = "0.23.0-RC1"
val log4CatsVersion = "2.1.1"
val doobieVersion   = "1.0.0-M5"
val circeVersion    = "0.14.1"

lazy val examples = project
  .in(file("examples"))
  .settings(
    commonSettings,
    coverageEnabled := false,
    libraryDependencies ++= Seq(
      "ch.qos.logback"   % "logback-classic"      % "1.2.3",
      "com.github.cb372" %% "cats-retry"          % catsRetryVersion,
      "org.typelevel"    %% "log4cats-slf4j"      % log4CatsVersion,
      "io.circe"         %% "circe-generic"       % circeVersion,
      "io.circe"         %% "circe-parser"        % circeVersion,
      "org.http4s"       %% "http4s-circe"        % http4sVersion,
      "org.http4s"       %% "http4s-dsl"          % http4sVersion,
      "org.http4s"       %% "http4s-blaze-server" % http4sVersion,
      "org.tpolecat"     %% "doobie-core"         % doobieVersion,
      "org.tpolecat"     %% "doobie-hikari"       % doobieVersion,
      "org.tpolecat"     %% "doobie-postgres"     % doobieVersion,
      compilerPlugin("org.typelevel" %% "kind-projector"     % kindProjectorVersion cross CrossVersion.full),
      compilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
    )
  )
  .dependsOn(core % "compile->compile")
