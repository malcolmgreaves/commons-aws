package com.mfglabs.commons.aws

import akka.actor.ActorSystem
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{FoldSink, Flow, Source}
import com.mfglabs.commons.stream.{MFGSource, MFGSink}

import collection.mutable.Stack
import org.scalatest._
import concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Millis, Seconds, Span}
import scala.concurrent.Future
import scala.concurrent.duration._

class S3Spec extends FlatSpec with Matchers with ScalaFutures {
  import s3._
  import scala.concurrent.ExecutionContext.Implicits.global

  val bucket = "mfg-commons-aws"

  val keyPrefix = "test/core"

  val resDir = "core/src/test/resources"

  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(2, Minutes), interval = Span(5, Millis))

  implicit val as = ActorSystem("test")
  implicit val fm = FlowMaterializer()
  // val cred = new com.amazonaws.auth.BasicAWSCredentials("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
  val S3 = new s3.AmazonS3Client()

  "S3 client" should "accept default constructor" in {
    whenReady(S3.getBucketLocation(bucket)) { s => s should equal ("eu-west-1") }
  }

  it should "upload/list/delete files" in {
    whenReady(
      for {
        _   <- S3.uploadFile(bucket, s"$keyPrefix/small.txt", new java.io.File(s"$resDir/small.txt"))
        l   <- S3.listFiles(bucket, Some(keyPrefix))
        _   <- S3.deleteFile(bucket, s"$keyPrefix/small.txt")
        l2  <- S3.listFiles(bucket, Some(keyPrefix))
      } yield (l, l2)
    ) { case (l, l2) =>
      (l map (_._1)) should equal (List(s"$keyPrefix/small.txt"))
      l2 should be ('empty)
    }
  }

  it should "upstream files" in {
    whenReady(
      for {
        _   <- S3.uploadStream(bucket, s"$keyPrefix/big.txt", MFGSource.fromFile(new java.io.File(s"$resDir/big.txt")))
        l   <- S3.listFiles(bucket, Some(keyPrefix))
        _   <- S3.deleteFile(bucket, s"$keyPrefix/big.txt")
        l2  <- S3.listFiles(bucket, Some(keyPrefix))
      } yield (l, l2)
    ) { case (l, l2) =>
      (l map (_._1)) should equal (List(s"$keyPrefix/big.txt"))
      l2 should be ('empty)
    }
  }

  it should "download a file as a stream" in {
    whenReady(
      for {
        initContent <- MFGSource.fromFile(new java.io.File(s"$resDir/big.txt")).runWith(MFGSink.collect)
        _ <- S3.uploadStream(bucket, s"$keyPrefix/big.txt", MFGSource.fromFile(new java.io.File(s"$resDir/big.txt")))
        downloadContent <- S3.getStream(bucket, s"$keyPrefix/big.txt").runWith(MFGSink.collect)
        _ <- S3.deleteFile(bucket, s"$keyPrefix/big.txt")
      } yield (initContent, downloadContent)
    ) { case (initContent, downloadContent) =>
      initContent should equal (downloadContent)
    }
  }

  it should "download a multipart file as a stream" in {
    whenReady(
      for {
        _ <- S3.uploadStream(bucket, s"$keyPrefix/part.1.txt", MFGSource.fromFile(new java.io.File(s"$resDir/part.1.txt")))
        _ <- S3.uploadStream(bucket, s"$keyPrefix/part.2.txt", MFGSource.fromFile(new java.io.File(s"$resDir/part.2.txt")))
        downloadContent <- S3.getStreamMultipartFile(bucket, s"$keyPrefix/part") |>>> Iteratee.consume()
        _ <- S3.deleteFile(bucket, s"$keyPrefix/part.1.txt")
        _ <- S3.deleteFile(bucket, s"$keyPrefix/part.2.txt")
      } yield downloadContent
    ) { downloadContent =>
      new String(downloadContent) should equal ("part1\npart2\n")
    }
  }


  it should "upload a stream as a multipart file" in {
    // test window
    // test nbrecords
    // test stop entre deux

    val tickSource =
      Source(initialDelay = 0 second, interval = 2 second, () => "tick".toCharArray.map(_.toByte))
        .takeWithin(11 seconds)


    whenReady(
    for {
      _ <- S3.deleteObject(bucket, keyPrefix + "/multipart-upload")
      nbSent <-
        S3.uploadStreamMultipartFile(bucket, keyPrefix + "/multipart-upload", tickSource , 10, 5 seconds)
          .via(FoldSink[Int,Int](0){(z,c) => z + 1})
      nbFiles <- S3.listObjects(bucket, keyPrefix + "/multipart-upload")
    } yield (nbSent, nbFiles)
    ){
      case (nbSent, nbFiles) =>
        nbSent shouldEqual(2)
        nbFiles shouldEqual(2)
    }

  }
}
