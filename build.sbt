name := "tagless"

version := "0.1"

scalaVersion := "2.11.7"

scalacOptions ++= Seq (
  "-Xlint",
  "-deprecation",
  "-feature",
//  "-Xlog-implicits",
  "-Xfatal-warnings"
)

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies += "org.spire-math" %% "cats" % "0.4.0-SNAPSHOT"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")

tutSettings

tutTargetDirectory := baseDirectory.value