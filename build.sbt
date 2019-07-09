import com.typesafe.sbt.SbtPgp.autoImportImpl.pgpSecretRing
import sbt.file

name := "cats-saga"

val mainScala = "2.12.8"
val allScala  = Seq("2.11.12", mainScala, "2.13.0")

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
    ),
    pgpPublicRing := file("./travis/local.pubring.asc"),
    pgpSecretRing := file("./travis/local.secring.asc"),
    releaseEarlyWith := SonatypePublisher
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
    case Some((2, 11)) =>
      Seq(
        "-Yno-adapted-args",
        "-Ypartial-unification",
        "-Ywarn-inaccessible",
        "-Ywarn-infer-any",
        "-Ywarn-nullary-override",
        "-Ywarn-nullary-unit"
      )
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
        "-opt:l:inline",
        "-Ypartial-unification"
      )
    case _ => Nil
  }),
  resolvers ++= Seq(Resolver.sonatypeRepo("snapshots"), Resolver.sonatypeRepo("releases"))
)

lazy val root = project
  .in(file("."))
  .aggregate(core)

val catsRetryVersion = "0.2.7"

lazy val core = project
    .in(file("core"))
    .settings(
      commonSettings,
      name := "cats-saga-core",
      coverageEnabled := true,
      crossScalaVersions := allScala,
      libraryDependencies ++= Seq(
        "org.typelevel"    %% "cats-effect"            % "2.0.0-M4",
        "org.scalatest"    %% "scalatest"              % "3.0.8"          % Test,
        "com.github.cb372" %% "cats-retry-core"        % catsRetryVersion % Optional,
        "com.github.cb372" %% "cats-retry-cats-effect" % catsRetryVersion % Optional,
        compilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
      )
    )
