import com.typesafe.sbt.SbtPgp.autoImportImpl.pgpSecretRing
import sbt.file

name := "cats-saga"

val mainScala = "2.12.8"
val allScala  = Seq("2.11.12", mainScala)

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
        "-opt:l:inline"
      )
    case _ => Nil
  }),
  scalacOptions ++= Seq("-Ypartial-unification"),
  resolvers ++= Seq(Resolver.sonatypeRepo("snapshots"), Resolver.sonatypeRepo("releases"))
)

lazy val root = project
  .in(file("."))
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(
    commonSettings,
    name := "cats-saga-core",
    coverageEnabled := true,
    crossScalaVersions := allScala,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "1.3.1",
      "org.scalatest" %% "scalatest"  % "3.0.5" % "test",
      compilerPlugin("org.spire-math" %% "kind-projector" % "0.9.9")
    )
  )
