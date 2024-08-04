val dottyVersion = "3.4.2"
//val dottyVersion = "3.5.1-RC1-bin-SNAPSHOT"


ThisBuild/version := "0.0.1-SNAPSHOT"
ThisBuild/versionScheme := Some("semver-spec")
ThisBuild/resolvers ++= Opts.resolver.sonatypeOssSnapshots



val sharedSettings = Seq(
    organization := "dotty-cps-async",
    scalaVersion := dottyVersion,
    name := "static-injection",
    libraryDependencies += "com.github.rssh" %%% "dotty-cps-async" % "0.9.21" 
)



lazy val root = project
  .in(file("."))
  .aggregate(
    staticInjection.jvm, staticInjection.js,
    staticInjectionExamples.jvm
  )
  .settings(
    Sphinx / sourceDirectory := baseDirectory.value / "docs",
    siteDirectory :=  baseDirectory.value / "target" / "site",
    git.remoteRepo := "git@github.com:dotty-cps-async/static-injection.git",
    publishArtifact := false,
  )
  .enablePlugins(SphinxPlugin,
                 SiteScaladocPlugin,
                 GhpagesPlugin
  ).disablePlugins(MimaPlugin)


lazy val staticInjection = crossProject(JSPlatform, JVMPlatform)
  .in(file("static-injection"))
  .settings(sharedSettings)
  .disablePlugins(SitePreviewPlugin)
  .settings(
    scalaVersion := "3.5.1-RC1-bin-SNAPSHOT",
    name := "static-injection",
    libraryDependencies += "com.github.sbt" % "junit-interface" % "0.13.3" % Test
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies += ("org.scala-js" %% "scalajs-junit-test-runtime" % "1.8.0" % Test).cross(CrossVersion.for3Use2_13),
  )

lazy val staticInjectionExamples = crossProject(JSPlatform, JVMPlatform)
  .in(file("static-injection-examples"))
  .settings(sharedSettings)
  .disablePlugins(SitePreviewPlugin)
  .dependsOn(staticInjection)
  .settings(
    scalaVersion := "3.5.1-RC1-bin-SNAPSHOT",
    name := "static-injection",
    Compile / run / fork := true,
    libraryDependencies += "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
    libraryDependencies ++= Seq(
      "com.github.rssh" %%% "cps-async-connect-cats-effect" % "0.9.21",
      "net.ruippeixotog" %% "scala-scraper" % "3.1.1",
      "org.augustjune" %% "canoe" % "0.6.0",
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.23.1"
    )
  ).jsSettings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies += ("org.scala-js" %% "scalajs-junit-test-runtime" % "1.8.0" % Test).cross(CrossVersion.for3Use2_13),
  )


