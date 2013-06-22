package edu.knowitall.tac2013.prep

import org.scalatest._
import edu.knowitall.tac2013.prep.KbpSentence

class KbpSentenceSpec extends FlatSpec {

  "KbpSentences" should "deserialize then serialize to their original string" in {
    
    val testSrc = scala.io.Source.fromFile("src/main/resources/samples/sentences.prep.1k")
    val lines = testSrc.getLines
    lines.foreach { line =>
      val sent = KbpSentence.read(line).get
      val reserialized = KbpSentence.write(sent)
      require(reserialized.equals(line))
    } 
  }
}