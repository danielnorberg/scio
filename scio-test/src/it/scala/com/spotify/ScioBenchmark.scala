/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.spotify

import java.util.UUID

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.dataflow.Dataflow
import com.spotify.scio._
import com.spotify.scio.values.SCollection
import org.apache.beam.runners.dataflow.DataflowPipelineJob
import org.apache.beam.sdk.transforms.DoFn.ProcessElement
import org.apache.beam.sdk.transforms.{DoFn, ParDo}
import org.joda.time.format.{DateTimeFormat, ISODateTimeFormat, PeriodFormat}
import org.joda.time.{DateTimeZone, Seconds}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Random

// This file is symlinked to scio-test/src/it/scala/com/spotify/ScioBenchmark.scala so that it can
// run on HEAD. Keep all changes contained in the same file.

object ScioBenchmarkSettings {
  val projectId: String = "scio-playground"

  val commonArgs = Array(
    s"--project=$projectId",
    "--runner=DataflowRunner",
    "--numWorkers=4",
    "--workerMachineType=n1-standard-4",
    "--autoscalingAlgorithm=NONE")

  val shuffleConf = Map("ShuffleService" -> Array("--experiments=shuffle_mode=service"))
}

object ScioBenchmark {

  import ScioBenchmarkSettings._

  private val dataflow = {
    val transport = GoogleNetHttpTransport.newTrustedTransport()
    val jackson = JacksonFactory.getDefaultInstance
    val credential = GoogleCredential.getApplicationDefault
    new Dataflow.Builder(transport, jackson, credential).build()
  }

  def main(args: Array[String]): Unit = {
    val timestamp = DateTimeFormat.forPattern("MMddHHmmss")
      .withZone(DateTimeZone.UTC)
      .print(System.currentTimeMillis())
    val prefix = s"ScioBenchmark-$timestamp"

    val argz = Args(args)
    val name = argz.getOrElse("name", ".*")
    val results = benchmarks
      .filter(_.name.matches(name))
      .flatMap(_.run(prefix))

    import scala.concurrent.ExecutionContext.Implicits.global
    val future = Future.sequence(results.map(_.result.finalState))
    Await.result(future, Duration.Inf)

    // scalastyle:off regex
    results.foreach { r =>
      println("=" * 80)
      prettyPrint("Benchmark", r.name)
      prettyPrint("Extra arguments", r.extraArgs.mkString(" "))
      prettyPrint("State", r.result.state.toString)

      val jobId = r.result.internal.asInstanceOf[DataflowPipelineJob].getJobId
      val job = dataflow.projects().jobs().get(projectId, jobId).setView("JOB_VIEW_ALL").execute()
      val parser = ISODateTimeFormat.dateTimeParser()
      prettyPrint("Create time", job.getCreateTime)
      prettyPrint("Finish time", job.getCurrentStateTime)
      val start = parser.parseLocalDateTime(job.getCreateTime)
      val finish = parser.parseLocalDateTime(job.getCurrentStateTime)
      val elapsed = PeriodFormat.getDefault.print(Seconds.secondsBetween(start, finish))
      prettyPrint("Elapsed", elapsed)

      r.result.getMetrics.cloudMetrics
        .filter(m => m.name.name.startsWith("Total") && !m.name.context.contains("tentative"))
        .map(m => (m.name.name, m.scalar.toString))
        .toSeq.sortBy(_._1)
        .foreach(kv => prettyPrint(kv._1, kv._2))
    }
    // scalastyle:on regex
  }

  private def prettyPrint(k: String, v: String): Unit = {
    // scalastyle:off regex
    println("%-20s: %s".format(k, v))
    // scalastyle:on regex
  }

  // =======================================================================
  // Benchmarks
  // =======================================================================

  private val benchmarks = Seq(
    GroupByKey,
    GroupAll,
    Join,
    HashJoin,
    SingletonSideInput,
    IterableSideInput,
    ListSideInput,
    MapSideInput,
    MultiMapSideInput)

  case class BenchmarkResult(name: String, extraArgs: Array[String], result: ScioResult)

  abstract class Benchmark(val extraConfs: Map[String, Array[String]] = null) {

    val name: String = this.getClass.getSimpleName.replaceAll("\\$$", "")

    private val configurations: Map[String, Array[String]] = {
      val base = Map(name -> Array.empty[String])
      val extra = if (extraConfs == null) {
        Map.empty
      } else {
        extraConfs.map(kv => (s"$name${kv._1}", kv._2))
      }
      base ++ extra
    }

    def run(prefix: String): Iterable[BenchmarkResult] = {
      val username = sys.props("user.name")
      configurations
        .map { case (confName, extraArgs) =>
          val (sc, _) = ContextAndArgs(commonArgs ++ extraArgs)
          sc.setAppName(confName)
          sc.setJobName(s"$prefix-$confName-$username".toLowerCase())
          run(sc)
          BenchmarkResult(confName, extraArgs, sc.close())
        }
    }

    def run(sc: ScioContext): Unit
  }

  // ===== GroupByKey =====

  // 100M items, 10K keys, average 10K values per key
  object GroupByKey extends Benchmark(shuffleConf) {
    override def run(sc: ScioContext): Unit =
      randomUUIDs(sc, 100 * M).groupBy(_ => Random.nextInt(10 * K)).mapValues(_.size)
  }

  // 10M items, 1 key
  object GroupAll extends Benchmark(shuffleConf) {
    override def run(sc: ScioContext): Unit =
      randomUUIDs(sc, 10 * M).groupBy(_ => 0).mapValues(_.size)
  }

  // ===== Join =====

  // LHS: 100M items, 10M keys, average 10 values per key
  // RHS: 50M items, 5M keys, average 10 values per key
  object Join extends Benchmark(shuffleConf) {
    override def run(sc: ScioContext): Unit =
      randomKVs(sc, 100 * M, 10 * M) join randomKVs(sc, 50 * M, 5 * M)
  }

  // LHS: 100M items, 10M keys, average 10 values per key
  // RHS: 1M items, 100K keys, average 10 values per key
  object HashJoin extends Benchmark {
    override def run(sc: ScioContext): Unit =
      randomKVs(sc, 100 * M, 10 * M) hashJoin randomKVs(sc, M, 100 * K)
  }

  // ===== SideInput =====

  // Main: 100M, side: 1M

  object SingletonSideInput extends Benchmark {
    override def run(sc: ScioContext): Unit = {
      val main = randomUUIDs(sc, 100 * M)
      val side = randomUUIDs(sc, 1 * M).map(Set(_)).sum.asSingletonSideInput
      main.withSideInputs(side).map { case (x, s) => (x, s(side).size) }
    }
  }

  object IterableSideInput extends Benchmark {
    override def run(sc: ScioContext): Unit = {
      val main = randomUUIDs(sc, 100 * M)
      val side = randomUUIDs(sc, 1 * M).asIterableSideInput
      main.withSideInputs(side).map { case (x, s) => (x, s(side).head) }
    }
  }

  object ListSideInput extends Benchmark {
    override def run(sc: ScioContext): Unit = {
      val main = randomUUIDs(sc, 100 * M)
      val side = randomUUIDs(sc, 1 * M).asListSideInput
      main.withSideInputs(side)
        .map { case (x, s) => (x, s(side).head) }
    }
  }

  // Main: 1M, side: 100K

  object MapSideInput extends Benchmark {
    override def run(sc: ScioContext): Unit = {
      val main = randomUUIDs(sc, 1 * M)
      val side = main
        .sample(withReplacement = false, 0.1)
        .map((_, UUID.randomUUID().toString))
        .asMapSideInput
      main.withSideInputs(side).map { case (x, s) => s(side).get(x) }
    }
  }

  object MultiMapSideInput extends Benchmark {
    override def run(sc: ScioContext): Unit = {
      val main = randomUUIDs(sc, 1 * M)
      val side = main
        .sample(withReplacement = false, 0.1)
        .map((_, UUID.randomUUID().toString))
        .asMultiMapSideInput
      main.withSideInputs(side).map { case (x, s) => s(side).get(x) }
    }
  }

  // =======================================================================
  // Utilities
  // =======================================================================

  private val M = 1000000
  private val K = 1000
  private val numPartitions = 100

  private def randomUUIDs(sc: ScioContext, n: Long): SCollection[String] =
    sc.parallelize(Seq.fill(numPartitions)(n / numPartitions))
      .applyTransform(ParDo.of(new FillDoFn(() => UUID.randomUUID().toString)))

  private def randomKVs(sc: ScioContext,
                        n: Long, numUniqueKeys: Int): SCollection[(String, String)] =
    sc.parallelize(Seq.fill(numPartitions)(n / numPartitions))
      .applyTransform(ParDo.of(new FillDoFn(() =>
        ("key" + Random.nextInt(numUniqueKeys), UUID.randomUUID().toString)
      )))

  private class FillDoFn[T](val f: () => T) extends DoFn[Long, T] {
    @ProcessElement
    def processElement(c: DoFn[Long, T]#ProcessContext): Unit = {
      var i = 0L
      val n = c.element()
      while (i < n) {
        c.output(f())
        i += 1
      }
    }
  }

}
