package edu.knowitall.tac2013.prep

import org.scalatest._
import java.io.File
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;


class KbpDocParserSpec extends FlatSpec {
  
  val docSplitter = new DocSplitter()
  val splitDocsDir = "src/main/resources/samples/docs-split/"
  val splitWebDocs = (splitDocsDir, "web")
  val splitNewsDocs= (splitDocsDir, "news")
  val splitForumDocs=(splitDocsDir, "forum")
  
  val allDocsDirs = Seq(splitWebDocs, splitNewsDocs, splitForumDocs)
  
  "DocSplitter" should "Tag lines with correct byte offsets" in {
    
    val files = allDocsDirs.flatMap { case (dir, corpus) => 
      new File(dir+corpus).listFiles().map(f => (f, KbpDocParser.getParser(corpus)))
    }
      
    for ((file, parser) <- files) {
      
      testFile(file, parser)
    }
  }
  
  /*
   * Assumes that a file contains a single kbp doc
   */
  def testFile(file: File, docParser: KbpDocParser): Unit = {
    
    val source = io.Source.fromFile(file)
    
    val spliterator = docSplitter.splitDocs(source)
    require(spliterator.hasNext)
    
    val kbpDoc = spliterator.next()
    
    require(!spliterator.hasNext)
    
    val parsedDoc = docParser.parseDoc(kbpDoc)
    
    val bytes = DocSplitterSpec.fileBytes(file)
    
    for (doc <- parsedDoc.toList; kbpline <- doc.textLines) {
      val targetString = new String(bytes.drop(kbpline.startByte).take(kbpline.length), "UTF8")
      if (!kbpline.line.trim().isEmpty) {
        if (!targetString.equals(kbpline.line)) {
          System.err.println("ParsedDoc: error on docId=%s expected\\nactual\n%s\n%s".format(doc.docIdLine.line, targetString, kbpline.line))
          fail()
        }
      }
    }
    source.close()
  }
}