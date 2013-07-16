package edu.knowitall.tac2013.benchmark

import edu.knowitall.tac2013.solr.query.SolrQueryExecutor
import edu.knowitall.tac2013.app.Slot
import java.io.PrintStream

/**
 * okFills contains the set of acceptable answers (e.g. Slotfill and alternates). The primary (Slotfill) will always
 * be the first element...
 */
case class BenchmarkAnswer(val okFills: Seq[String], val docId: String) {
  
}

case class BenchmarkItem(val entityName: String, val entityType: String, val nodeId: String, val slot: Slot, val answers: Set[BenchmarkAnswer]) {
  
  import edu.knowitall.tac2013.app.KBPQuery
  import edu.knowitall.tac2013.app.KBPQueryEntityType
  
  def printString(answer: BenchmarkAnswer) = (Seq(entityName, entityType, nodeId, answer.docId, slot.name) ++ answer.okFills).mkString("\t")
  def printStrings = (answers.toSeq map printString)
  
  
  def kbpQuery = {
    val nodeIdOpt = if (nodeId.startsWith("NIL")) None else Some(nodeId)
    val eType = KBPQueryEntityType.fromString(entityType)
    KBPQuery.forEntityName(entityName, eType, nodeIdOpt).withOverrideSlots(Set(slot))
  }
}

class Benchmarker(val solrExec: SolrQueryExecutor, val benchmarkItems: Iterable[BenchmarkItem]) {

  import edu.knowitall.tac2013.app.Candidate
  import edu.knowitall.tac2013.app.FilterSolrResults
  import edu.knowitall.tac2013.app.OutputFormatter
  import edu.knowitall.tac2013.app.SlotFillReranker
  import edu.knowitall.tac2013.app.SlotFillConsistency
  import java.io.PrintStream
  import java.util.concurrent.atomic.AtomicInteger
  import edu.knowitall.tac2013.app.KBPQueryEntityType
  
  private val nullOutput = new OutputFormatter(new PrintStream("/dev/null"), printFiltered = false, printUnfiltered = false, printGroups = false, printAnswers = false)
  
  private def getResponse(item: BenchmarkItem): Seq[Candidate] = {
    
    val unfiltered = solrExec.executeUnfilteredQuery(item.kbpQuery, item.slot)

    val filteredCandidates = FilterSolrResults.filterResults(unfiltered, item.kbpQuery)

    val bestAnswers = new SlotFillReranker(nullOutput).findSlotAnswers(item.slot, item.kbpQuery, filteredCandidates)

    val smoothedSlotBestAnswers = SlotFillConsistency.makeConsistent(Map(item.slot -> bestAnswers))
    
    smoothedSlotBestAnswers(item.slot)
  }
  
  private def judgeResponses(responses: Seq[Candidate], item: BenchmarkItem): List[String] = {
    
    var outputLines = List.empty[String]
    var unusedAnswers = item.answers
    var usedAnswers: Set[BenchmarkAnswer] = Set.empty
    for (response <- responses) {
      // find an answer that matches
      val matchingAnswer = unusedAnswers.find(_.okFills.contains(response.trimmedFill.string))
      matchingAnswer match {
        case Some(answer) => {
          unusedAnswers -= answer
          usedAnswers += answer
          outputLines ::= correct(item, response)
        }
        case None => {
          outputLines ::= notInBenchmark(item, response)
        }
      }
    }
    
    for (answer <- unusedAnswers) {
      outputLines ::= notFound(answer, item)
    }
    
    outputLines
  }
  
  private val numCorrect = new AtomicInteger(0)
  private val numNotInBenchmark = new AtomicInteger(0)
  private val numNotFound = new AtomicInteger(0)
  
  private val correctPrefix        = "(     CORRECT    )"
  private val notInBenchmarkPrefix = "(Not In Benchmark)"
  private val notFoundPrefix       = "(    Not Found   )"
  
  private def correct(item: BenchmarkItem, candidate: Candidate): String = {
    numCorrect.incrementAndGet()
    s"$correctPrefix ${answerLike(item, candidate)}"
  }
  
  private def notInBenchmark(item: BenchmarkItem, candidate: Candidate): String = {
    numNotInBenchmark.incrementAndGet()
    s"$notInBenchmarkPrefix ${answerLike(item, candidate)}"
  }
  
  private def notFound(answer: BenchmarkAnswer, item: BenchmarkItem): String = {
    numNotFound.incrementAndGet()
    s"$notFoundPrefix ${item.printString(answer)}"
  }
  
  // print a response for a candidate that looks kinda like a benchmark entry for consistency
  private def answerLike(item: BenchmarkItem, candidate: Candidate): String = {
    Seq(item.entityName,
        KBPQueryEntityType.toString(candidate.pattern.entityType),
        item.nodeId,
        candidate.extr.sentence.docId,
        item.slot.name,
        candidate.trimmedFill.string,
        s"TUPLE: ${candidate.debugString}").mkString("\t")
        
  }
  
  private def finalStats: Seq[String] = {
    
    val pessFrac = numCorrect.get.toDouble / (numNotInBenchmark.get.toDouble + numNotFound.get.toDouble + numCorrect.get.toDouble)
    val pessString = "%.02f%%".format(pessFrac)
    
    val optFrac = (numCorrect.get.toDouble+numNotInBenchmark.get.toDouble) / (numNotInBenchmark.get.toDouble + numNotFound.get.toDouble + numCorrect.get.toDouble)
    val optString = "%.02f%%".format(optFrac)
    
    Seq("", 
        "OVERALL STATS",
        "",
        s"Num correct:${numCorrect.get}",
        s"Num not in benchmark:${numNotInBenchmark.get}",
        s"Num not found:${numNotFound.get}",
        s"Optimistic  Fraction Correct = $optString",
        s"Pessimistic Fraction Correct = $pessString")
  }
  
  def go: Iterable[String] = {
    benchmarkItems.flatMap(item => judgeResponses(getResponse(item), item)) ++ finalStats
  }
}

object Benchmarker {

  import io.Source
  import java.net.URL
  import edu.knowitall.common.Resource.using
  import scopt.OptionParser
  
  val benchmark2012 = "Benchmark_2012.tsv"
  val benchmark2013 = "Benchmark_2013.tsv"
    
  private def loadRsrc(path: String) = Option(getClass.getResource(path)).getOrElse(throw new RuntimeException(path + " not found"))
  
  private def b2012src = loadRsrc(benchmark2012)
  private def b2013src = loadRsrc(benchmark2013)
 
  private val tabRegex = "\t".r
  
  private def loadBenchmark(url: URL) = using(Source.fromURL(url, "UTF8")) { source =>
    
    // drop header line and filter empty lines.
    val filteredLines = source.getLines.drop(1).filter(_.trim.nonEmpty)
    // split on tabs
    val splitLines = filteredLines.map(tabRegex.split).map(_.toList)
    // group by (name, type, nodeId, slotname)
    val groupNames = splitLines.toSeq.groupBy { fields =>
      fields match {
        case name :: typ :: nodeId :: docId :: slotname :: slotfills => (name :: typ :: nodeId :: slotname :: Nil)
        case _ => throw new RuntimeException("(#1) Malformed benchmark item fields:\n" + fields.mkString("\t"))
      }
    }
    // combine everything to get BenchmarkItems.
    val benchmarkItems = groupNames.map {
      case (name :: typ :: nodeId :: slotname :: Nil, splits) => {
        val answers = splits.map { fields =>
          fields match {
            case (_ :: _ :: _ :: docId :: _ :: slotfill :: alternates) =>
              BenchmarkAnswer(slotfill :: alternates, docId)
            case _ => throw new RuntimeException("(#2) Malformed benchmark item fields:\n" + fields.mkString("\t"))
          }
        }
        BenchmarkItem(name, typ, nodeId, Slot.fromName(slotname.trim), answers.toSet)
      }
      case _ => throw new RuntimeException("(#3) Malformed benchmark item fields.")
    }
    benchmarkItems.toList
  }
  
  private def load2012Benchmark = loadBenchmark(b2012src)
  private def load2013Benchmark = loadBenchmark(b2013src)
  
  def main(args: Array[String]): Unit = {
    
    var corpus = "2013"
    var output = System.out
      
    val parser = new OptionParser() {
      arg("corpus", "2012 or 2013", { corpus = _ })
      opt("outFile", "File for output, default stdout", { s => output = new PrintStream(s)})
    }
    
    if (!parser.parse(args)) return
    require(corpus == "2012" || corpus == "2013", "Corpus must be 2012 or 2013")
    
    val outputStrings = corpus match {
      case "2013" => new Benchmarker(SolrQueryExecutor.newCorpus, load2013Benchmark).go
      case "2012" => new Benchmarker(SolrQueryExecutor.oldCorpus, load2012Benchmark).go
      case _ => throw new RuntimeException("Corpus must be 2012 or 2013")
    }
    
    outputStrings foreach output.println
  }
}