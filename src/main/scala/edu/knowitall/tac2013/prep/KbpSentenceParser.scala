package edu.knowitall.tac2013.prep
import edu.knowitall.tool.chunk.OpenNlpChunker
import edu.knowitall.tool.postag.OpenNlpPostagger
import edu.knowitall.tool.tokenize.ClearTokenizer
import edu.knowitall.tool.parse.ClearParser
import edu.knowitall.tool.postag.ClearPostagger
import scopt.OptionParser
import scala.io.Source
import java.io.PrintStream
import edu.knowitall.common.Resource.using
import edu.knowitall.common.Timing
import java.util.concurrent.atomic.AtomicInteger
import scala.Option.option2Iterable

class KbpSentenceParser() {

  val chunkerModel = OpenNlpChunker.loadDefaultModel
  val postagModel = OpenNlpPostagger.loadDefaultModel

  val chunkerLocal = new ThreadLocal[OpenNlpChunker] {
    override def initialValue = {
      val postagger = new OpenNlpPostagger(postagModel, tokenizer)
      new OpenNlpChunker(chunkerModel, postagger)
    }
  }
  
  lazy val tokenizer = new ClearTokenizer()
  
  lazy val parser = new ClearParser(new ClearPostagger(tokenizer))
  
  def parseKbpSentence(kbpSentence: KbpSentence): Option[ParsedKbpSentence] = {
    
    try {
    // chunks, then parse
    val chunker = chunkerLocal.get
    val chunked = chunker.chunk(kbpSentence.text) 
    // Synchronize because the OpenNlpTokenizer isn't threadsafe
    val dgraph = parser.dependencyGraph(kbpSentence.text)
    val postags = chunked.map(_.postag).mkString(" ")
    val chunks = chunked.map(_.chunk)
    
    Some(
        new ParsedKbpSentence(
            kbpSentence.docId, 
            kbpSentence.sentNum, 
            kbpSentence.offset,  
            chunks, 
            dgraph))
    } catch {
      case e: Throwable =>
        System.err.println("Error parsing sentence: %s".format(kbpSentence.text))
        e.printStackTrace()
        None
    }
  }
}


object KbpSentenceParser {
  
  import java.util.concurrent.atomic.AtomicInteger
  
  private val sentencesProcessed = new AtomicInteger(0)
  
  def processXml(lines: Iterator[String], corpus: String): Iterator[ParsedKbpSentence] = {
    val parser = new KbpSentenceParser
    Sentencer.processXml(lines, corpus) flatMap parser.parseKbpSentence
  }
  
  def main(args: Array[String]): Unit = {
    
    var inputFile = ""
    var corpus = ""
    var limit = Int.MaxValue
    var outputFile = "stdout"
    
    val cliParser = new OptionParser() {
      arg("inputFile", "inputFile", { str => inputFile = str })
      arg("corpus", "news, forum, or web", { str => corpus = str })
      opt("outputFile", "File: ParsedKbpSentences, default stdout", { str => outputFile = str })
      opt("limit", "Limit number of sentences output?", { str => limit = str.toInt })
    }

    if (!cliParser.parse(args)) return 
    
    val input = io.Source.fromFile(inputFile, "UTF8")
    val output = if (outputFile.equals("stdout")) System.out else new PrintStream(outputFile, "UTF8")
    
    val nsTime = Timing.time {
      
      val parser = new KbpSentenceParser
      
      val sentencesGrouped = Sentencer.processXml(input.getLines, corpus).take(limit).grouped(1000)
      
      sentencesGrouped foreach { sentenceGroup =>
        sentenceGroup.par foreach { sentence =>
          sentencesProcessed.incrementAndGet()
          parser.parseKbpSentence(sentence) map ParsedKbpSentence.write foreach output.println
        }
      }
      
    }
    val seconds = Timing.Seconds.format(nsTime)
    System.err.println("Processed %d sentences in %s.".format(sentencesProcessed.get, seconds))
  }
}