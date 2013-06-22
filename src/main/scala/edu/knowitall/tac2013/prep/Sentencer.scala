package edu.knowitall.tac2013.prep

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Converts from KbpParsedDoc to KbpSentences
 */
class Sentencer {
  
  private val errorCounter = new AtomicInteger(0)
  
  /**
   * Returns an empty collection on error.
   */
  def convertToSentences(parsedDoc: KbpParsedDoc): Seq[KbpSentence] = {
    
    val docId = extractDocId(parsedDoc.docIdLine)
    val author = parsedDoc.authorLine flatMap extractAuthor
    val date = parsedDoc.datetimeLine flatMap extractDate
    
    if (docId.isEmpty) {
      val msg = "Sentencer error #%d: Doc skipped; Unable to extract docId from line: %s".format(parsedDoc.docIdLine.line)
      System.err.println(msg)
      Seq.empty
    } else {
      buildKbpSentences(docId.get, author, date, parsedDoc.textLines)
    }
  }
  
  private val docIdPattern = Pattern.compile("^<DOC id=.*", Pattern.CASE_INSENSITIVE)
  
  /*
   * Extract docId string, assuming kbpLine contains it.
   */
  private def extractDocId(kbpLine: KbpDocLine): Option[String] = {
    // Format is either:
    // <DOCID>id here...</DOCID>		(web)
    // <DOC id="AFP_ENG_20090531.0001" type="story" >	(news)
    // <doc id="bolt-eng-DF-183-195681-7948494">	(forum)
    val str = kbpLine.line
    
    if (str.startsWith("<DOCID>")) {
      // drop the tag and go until the closing tag.
      Some(str.drop(7).takeWhile(_ != '<'))
    }
    else if (docIdPattern.matcher(str).matches()) {
      // drop the <DOC ID=" part, and take until the closing quote.
      Some(str.drop(9).takeWhile(_ != '\"'))
    } else {
      // convertToSentences reports the error for us...
      None
    }
  }
  
  private def extractAuthor(kbpLine: KbpDocLine): Option[String] = {
    Some("AUTHOR EXTRACT NOT IMPLEMENTED")
  }
  
  private def extractDate(kbpLine: KbpDocLine): Option[String] = {
    Some("DATE EXTRACT NOT IMPLEMENTED")
  }
  
  private def buildKbpSentences(docId: String, author: Option[String], date: Option[String], textLines: Seq[KbpDocLine]): Seq[KbpSentence] = {
    throw new Exception("NOT IMPLEMENTED")
  }
}