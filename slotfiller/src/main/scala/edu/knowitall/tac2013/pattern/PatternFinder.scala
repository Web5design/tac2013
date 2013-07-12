package edu.knowitall.tac2013.pattern

import jp.sf.amateras.solr.scala._
import edu.knowitall.tac2013.openie.KbpExtraction

case class Pattern private (
    val freq: Int, 
    val relStemmed: String, 
    val entityType: String, 
    val slotName: String,
    val entityInArg1: Boolean,
    val sampleEntities: StringCounter, 
    val sampleFills: StringCounter) {
  
  def entityInArg2 = !entityInArg1
  
  def arg1Type = if (entityInArg1) entityType else slotName
  def arg2Type = if (entityInArg2) entityType else slotName
  
  def sampleArg1s = if (entityInArg1) sampleEntities else sampleFills
  def sampleArg2s = if (entityInArg2) sampleEntities else sampleFills
  
  
  def groupFields = Seq(arg1Type, relStemmed, arg2Type)
  def groupKey = groupFields.mkString(",")
  
  def combineWith(other: Pattern): Pattern = {
    require(this.groupKey == other.groupKey) 
    Pattern(this.freq + other.freq, relStemmed, entityType, slotName, entityInArg1, this.sampleEntities.addAll(other.sampleEntities).trim(200), this.sampleFills.addAll(other.sampleFills).trim(200))
  }
  
  override def toString: String = {
    
    val a1String = "ARG1s: " + sampleArg1s.top(4).map(p => "%s(%d)".format(p._1, p._2)).mkString(", ")
    val a2String = "ARG2s: " + sampleArg2s.top(4).map(p => "%s(%d)".format(p._1, p._2)).mkString(", ")
    
    val fields = Seq(freq.toString) ++ groupFields ++ Seq(a1String, a2String)
    fields.mkString("\t")
  }
}
object Pattern {
  
  import edu.knowitall.tool.stem.MorphaStemmer
  import edu.knowitall.tool.chunk.ChunkedToken
  
  val morphaLocal = new ThreadLocal[MorphaStemmer]() {
    override def initialValue = new MorphaStemmer
  }
  def morpha = morphaLocal.get
  
  def from(freq: Int, relStemmed: String, query: KbQuery, samples: Seq[KbpExtraction]): Seq[Pattern] = {
    
    
    def cleanSample(str: String): String = str.toLowerCase.replaceAll("\\s+", " ")
    val sampleArg1s = samples.map(_.arg1.originalText) map cleanSample
    val sampleArg2s = samples.map(_.arg2.originalText) map cleanSample
    
    val sampleEntities = if (query.entityArg1) sampleArg1s else sampleArg2s
    val sampleFills =    if (query.entityArg2) sampleArg1s else sampleArg2s
    
    query.element.slotNames.map { slotName =>
      Pattern(freq, relStemmed, query.element.entityType, slotName, query.entityArg1, StringCounter.fromStrings(sampleEntities), StringCounter.fromStrings(sampleFills))
    }
  }
  
  def stemRel(tokens: Seq[ChunkedToken]): String = tokens.map({ t => morpha.lemmatizeToken(t) }).map(_.lemma).mkString(" ")
}

class PatternFinder(val solrClient: SolrClient, elements: Iterable[KbElement]) {
  
  import org.apache.solr.client.solrj.util.ClientUtils
  
  def this(url: String, elements: Iterable[KbElement]) = this(new SolrClient(url), elements)

  def filterLongArgs(query: KbQuery)(extr: KbpExtraction): Boolean = {
    val arg1TooLong = extr.arg1.originalText.length > query.arg1.entity.length + 30
    val arg2TooLong = extr.arg2.originalText.length > query.arg2.entity.length + 30
    !arg1TooLong && !arg2TooLong
  }
  
  def sendQuery(query: KbQuery) = {

    try {
      val solrQuery = solrClient.query(query.queryString)
      val result = solrQuery.sortBy("confidence", Order.desc).rows(10000).getResultAsMap()
      val kbpExtrs = result.documents.flatMap { doc =>
        val fieldMap = doc.asInstanceOf[Map[String, Any]]
        KbpExtraction.fromFieldMap(fieldMap)
      }
      (query, kbpExtrs filter filterLongArgs(query))
    } catch {
      case e: Exception => {
        e.printStackTrace()
        (query, Nil)
      }
    }
  }
  
  def sendQueries(kbElement: KbElement): Iterable[(KbQuery, Seq[KbpExtraction])] = {
    
    val q1 = KbQuery(kbElement, true)
    val q2 = KbQuery(kbElement, false)
    Seq(sendQuery(q1), sendQuery(q2))
  }
  
  /**
   * Return patterns grouped by fill type
   */
  def getPatterns: Map[String, Seq[Pattern]] = {
    
    System.err.println("Issuing Queries...")

    val groupSize = 10000
    
    val results = elements.iterator.grouped(groupSize) flatMap { group =>
      group.par flatMap sendQueries filter(_._2.nonEmpty)
    }
    
    // flatMap results into patterns, then group patterns and combine.
    val rawPatterns = results.flatMap { case (query, extrs) =>
      val relGroups = extrs.groupBy(e => Pattern.stemRel(e.rel.tokens))
      relGroups.iterator.flatMap { case (relStemmed, extrs) =>
        Pattern.from(extrs.size, relStemmed, query, extrs)  
      }
    }
    
    System.err.println("Combining patterns...")
    
    def combine(p1: Pattern, p2: Pattern) = p1.combineWith(p2)

    // combine intermediate results to reduce memory footprint...
    val intermediate = rawPatterns.grouped(groupSize).zipWithIndex.flatMap { case (group, index) =>
      System.err.println("Patterns processed: " + (index + 1) * groupSize)
      group.groupBy(_.groupKey).values.map { patterns => patterns.reduce(combine) }
    } toSeq
    
    // group patterns by key and combine
    val combinedPatterns = intermediate.groupBy(_.groupKey).values.map { patterns => patterns.reduce(combine) }
    
    System.err.println("Grouping patterns...")
    val groupedPatterns = combinedPatterns.groupBy(_.slotName)
    
    // sort patterns in descending order by frequency
    val sortedPatterns = groupedPatterns.map { case (slotname, patterns) => (slotname, patterns.toSeq.sortBy(-_.freq)) }
    
    sortedPatterns
  }
}

object PatternFinder extends App {
  
  import scopt.OptionParser
  import java.io.File
  import java.io.PrintStream
  
  var solrUrl = "http://knowitall:knowit!@rv-n16.cs.washington.edu:9321/solr"
  var queriesFile: File = new File(".") 
  var answerFile: File = new File(".")
  var output: PrintStream = System.out
  var elementLimit = Int.MaxValue
  
  val parser = new OptionParser() {
    arg("queriesFile", "Path to queries xml file", { s => queriesFile = new File(s) })
    arg("answerFile", "Path to annotations tab file", { s => answerFile = new File(s) })
    opt("output", "Output file (default stdout)", { s => output = new PrintStream(new File(s)) })
    opt("limit", "debug limit elements, default no limit", { s => elementLimit = s.toInt })
  }
  
  if (parser.parse(args)) {
    
    val elements = new AnswerKeyReader(answerFile, queriesFile)
    
    val patternFinder = new PatternFinder(solrUrl, elements.take(elementLimit))
    
    patternFinder.getPatterns.iterator.toSeq.sortBy(-_._2.size) foreach { case (slotname, patterns) =>
      output.println("PATTERNS FOR: " + slotname)
      patterns foreach output.println
      output.println
    }
  }

  if (output != System.out) output.close()
}


object KbPatternFinder extends App {
  
  import scopt.OptionParser
  import java.io.File
  import java.io.PrintStream
  
  var solrUrl = "http://knowitall:knowit!@rv-n16.cs.washington.edu:8123/solr"
  var kbPath: File = new File(".") 
  var output: PrintStream = System.out
  var limit: Option[Int] = None
  
  val parser = new OptionParser() {
    arg("kbPath", "Path to knowledge base", { s => kbPath = new File(s) })
    opt("output", "Output file (default stdout)", { s => output = new PrintStream(new File(s)) })
    opt("limit", "Limit amount of KB to read (debug)", { s => limit = Some(s.toInt) })
  }
  
  if (parser.parse(args)) {
    
    val elements = limit match {
      case Some(l) => new KnowledgeBaseReader(kbPath).take(l)
      case None => new KnowledgeBaseReader(kbPath)
    }
    
    val patternFinder = new PatternFinder(solrUrl, elements)
    
    patternFinder.getPatterns.iterator.toSeq.sortBy(-_._2.size) foreach { case (slotname, patterns) =>
      output.println("PATTERNS FOR: " + slotname)
      patterns foreach output.println
      output.println
    }
  }

  if (output != System.out) output.close()
}
