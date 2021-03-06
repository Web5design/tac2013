package edu.knowitall.tac2013.prep

import org.scalatest._
import edu.knowitall.tool.sentence.BreezeSentencer
import java.io.File
import util.LineReader

class SentencerSpec extends FlatSpec {
  
  val sentencer = Sentencer.defaultInstance
  // tuple of (Parser for corpus, corpus sample/test file)
  // can add to this list to add new test sample files. 
  val corpora = Seq(
    (KbpDocProcessor.getProcessor("web"),  "/samples/docs-split/web"),
    (KbpDocProcessor.getProcessor("news"), "/samples/docs-split/news"),
    (KbpDocProcessor.getProcessor("forum"),"/samples/docs-split/forum")
  )

  // Remember to convert newlines to spaces, and run asciifier.
  "The Sentencer" should "produce meaningful byte offsets with filtering" in {

    corpora foreach {
      case (docProcessor, sampleDir) => {
        for (
            url <- new File(getClass.getResource(sampleDir).getFile()).listFiles.map(_.toURL);
            rawDoc <- new DocSplitter(LineReader.fromURL(url, "UTF8"));
            parsedDoc <- docProcessor.process(rawDoc).toList;
            rawSentence <- sentencer.convertToSentences(parsedDoc);
            s <- SentenceFilter.apply(rawSentence)
         ) {
          val fileString = DocSplitterSpec.fileString(url)
          val byteString = fileString.drop(s.offset).take(s.length)
          // skip the fabricated sentences. Offsets only line up for the entity in them.
          if (!s.text.startsWith("This post was written")) {
            
            val str = util.Asciifier(byteString.replaceAll("\n", " "))
            val exp = s.text
            
            def bytes(str: String) = str
            if (!str.equals(exp)) {
             System.err.println("\"%s\"".format(str))
             System.err.println("\"%s\"".format(exp))
             fail()
            } 
          }
        }
      }
    }
  }
  
    "The Sentencer" should "produce meaningful byte offsets without filtering" in {

    corpora foreach {
      case (docProcessor, sampleDir) => {
        for (
            url <- new File(getClass.getResource(sampleDir).getFile()).listFiles.map(_.toURL);
            rawDoc <- new DocSplitter(LineReader.fromURL(url, "UTF8"));
            parsedDoc <- docProcessor.process(rawDoc).toList;
            s <- sentencer.convertToSentences(parsedDoc)
         ) {
          val fileString = DocSplitterSpec.fileString(url)
          val byteString = fileString.drop(s.offset).take(s.length)
          // skip the fabricated sentences. Offsets only line up for the entity in them.
          if (!s.text.startsWith("This post was written")) {
            
            val str =  byteString.replaceAll("\n", " ")
            val exp = s.text
            
            def bytes(str: String) = str
            if (!str.equals(exp)) {
             System.err.println("\"%s\"".format(str))
             System.err.println("\"%s\"".format(exp))
             fail()
            } 
          }
        }
      }
    }
  }
}