package edu.knowitall.tac2013.openie

import edu.knowitall.tool.chunk.Chunker
import edu.knowitall.tool.chunk.OpenNlpChunker
import edu.knowitall.tool.parse.DependencyParser
import edu.knowitall.tool.parse.ClearParser
import scopt.OptionParser
import scala.io.Source
import java.io.PrintStream
import edu.knowitall.common.Resource.using

import edu.knowitall.tool.chunk.ChunkedToken

class KbpSentenceParser(val chunker: Chunker, val parser: DependencyParser) {
  
  def parseKbpSentence(kbpSentence: KbpSentence): ParsedKbpSentence = {
    
    // chunks, then parse
    val chunked = chunker.chunk(kbpSentence.text)
    val dgraph = parser.dependencyGraph(kbpSentence.text).serialize
    
    val tokens = chunked.map(_.string).mkString(" ")
    val postags = chunked.map(_.postag).mkString(" ")
    val chunks = chunked.map(_.chunk).mkString(" ")
    
    ParsedKbpSentence(kbpSentence.docId, kbpSentence.sentId, kbpSentence.text, tokens, postags, chunks, dgraph)
  }
}


object KbpSentenceParser {
  
  def main(args: Array[String]): Unit = {
    
    var inputfile = Option.empty[String]
    var outputfile = Option.empty[String]
    
    val cliParser = new OptionParser() {
      opt("inputfile", "File: KbpSentences one per line -- default stdin", { str => inputfile = Some(str) })
      opt("outputfile", "File: ParsedKbpSentences, default stdout", { str => outputfile = Some(str) })
    }
    
    if (cliParser.parse(args)) {
      val inputSource = inputfile map Source.fromFile getOrElse Source.stdin
      val outputStream = outputfile map { file => new PrintStream(file) } getOrElse System.out
      using(inputSource) { input =>
        using(outputStream) { output =>
          run(input, output)
        }
      }
    } else { 
      return
    }
  }
  
  def run(input: Source, output: PrintStream): Unit = {
   
    val chunker = new OpenNlpChunker()
    val parser = new ClearParser()
    
    val kbpProcessor = new KbpSentenceParser(chunker, parser);
    
    val kbpSentences = input.getLines.flatMap { line => KbpSentence.read(line) }
    
    kbpSentences.foreach { kbpSentence =>
      val parsedKbpSentence = kbpProcessor.parseKbpSentence(kbpSentence)
      output.println(ParsedKbpSentence.write(parsedKbpSentence))
    }
  }
}