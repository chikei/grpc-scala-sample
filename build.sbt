import com.trueaccord.scalapb.{ScalaPbPlugin => PB}

PB.protobufSettings

PB.flatPackage in PB.protobufConfig := true

scalaSource in PB.protobufConfig := sourceManaged.value

PB.runProtoc in PB.protobufConfig := { args =>
  com.github.os72.protocjar.Protoc.runProtoc("-v300" +: args.toArray)
}

libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime-grpc" % (PB.scalapbVersion in PB.protobufConfig).value

libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" % (PB.scalapbVersion in PB.protobufConfig).value % PB.protobufConfig

libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-json4s" % "0.1.1"

libraryDependencies += "io.grpc" % "grpc-netty" % "1.0.0-pre1"

scalaVersion := "2.11.8"

scalacOptions ++= (
  "-deprecation" ::
  "-unchecked" ::
  "-Xlint" ::
  "-language:existentials" ::
  "-language:higherKinds" ::
  "-language:implicitConversions" ::
  "-Yno-adapted-args" ::
  "-Ywarn-unused" ::
  "-Ywarn-unused-import" ::
  Nil
)
