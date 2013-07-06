package edu.knowitall.tac2013.findSlotFillersApp

import scala.io._
import java.io._
import edu.knowitall.tac2013.openie.KbpExtraction
import edu.knowitall.tac2013.findSlotFillersApp.KBPQueryEntityType._
import edu.knowitall.collection.immutable.Interval
import SlotFillReranker.findAnswers

object KbpQueryOutput {

  val runID = "UWashington-1"

  def printUnformattedOutput(mapOfResults: Map[String, Seq[Candidate]], kbpQuery: KBPQuery): String = {

    val slotOutputs = for (kbpSlot <- kbpQuery.slotsToFill) yield {
      printUnformattedSlotOutput(kbpSlot, mapOfResults(kbpSlot))
    }
    slotOutputs.mkString
  }

  def printUnformattedSlotOutput(slot: String, slotCandidates: Seq[Candidate]): String = {

    require(slotCandidates.forall(_.pattern.slotName.equals(slot)))

    if (slotCandidates.isEmpty) {
      s"KBP SLOT NAME: $slot\n\tNil\n\n"
    } else {

      val sb = new StringBuilder
      sb.append(s"KBP SLOT NAME: $slot\n")

      val patternCandidates = slotCandidates.groupBy(_.pattern)
      for (pattern <- SlotPattern.patternsForSlot(slot);
          candidates = patternCandidates.getOrElse(pattern, Nil)) {
        val topCandidates = candidates.take(20)

        sb.append("\tQuery pattern:\t" + pattern.debugString)

        sb.append("\tResults:\n")
        if (topCandidates.length == 0) {
          sb.append("\t\tNil" + "\n")
        } else {
          for (candidate <- topCandidates) {
            sb.append("\t\targ1: " + candidate.extr.arg1.originalText + "\t rel: " + candidate.extr.rel.originalText +
              "\t arg2: " + candidate.extr.arg2.originalText + "\t docID: " + candidate.extr.sentence.docId +
              "\t confidence: " + candidate.extr.confidence + "\t sentence: " + candidate.extr.sentence.dgraph.text  + 
               "\t trimFill: " + candidate.trimmedFill.trimmedFillString + "\n" )
          }
          if (candidates.size > 20) sb.append("\t\t(%d more)\n".format(candidates.size - 20))
          sb.append("\n")
        }
      }
      sb.toString
    }
  }

  /**
   * Overloaded to return a string for server usage
   */
  def printFormattedOutput(
    bestAnswers: Map[String, Seq[Candidate]],
    kbpQuery: KBPQuery): String = {

    //iterate over every slot type
    val slotOutputs = for (kbpSlot <- kbpQuery.slotsToFill) yield {
      printFormattedSlotOutput(kbpSlot, kbpQuery, bestAnswers.getOrElse(kbpSlot, Nil))
    }
    slotOutputs.mkString
  }
  
    def printFormattedOutputWithExtraInfo(
    slotCandidateSets: Map[String, Seq[Candidate]],
    bestAnswers: Map[String, Seq[Candidate]],
    kbpQueryEntityType: KBPQueryEntityType): String = {

    //iterate over every slot type
    val slotOutputs = for (kbpSlot <- Slot.getSlotTypesList(kbpQueryEntityType)) yield {
      if (slotCandidateSets.contains(kbpSlot)) {

        // for each slot print one response for single-valued slot
        // print k-slots for multi-valued slot
        // or print NIL
        printFormattedSlotOutputWithExtraInfo(kbpSlot, bestAnswers(kbpSlot))
      } else {
        // else if the results Map does not contain the slot
        // print nothing since this slot is ignored
        ""
      }
    }
    slotOutputs.mkString
  }

  def printFormattedSlotOutput(kbpSlot: String, kbpQuery: KBPQuery, bestAnswers: Seq[Candidate]): String = {

    val sb = new StringBuilder

    if (bestAnswers.isEmpty) {
      sb.append(Iterator(kbpQuery.id, kbpSlot, runID, "NIL").mkString("\t") + "\n")
    } else {
      for (bestAnswer <- bestAnswers) {
        val queryData = bestAnswer.pattern
        val bestExtr = bestAnswer.extr
        val slotFillIn = queryData.slotFillIn.get.toLowerCase()
        
        require(slotFillIn == "arg1" || slotFillIn == "arg2" || slotFillIn =="relation")
        
        val fields = Iterator(
          kbpQuery.id,
          kbpSlot,
          runID,
          bestAnswer.extr.sentence.docId,
          bestAnswer.trimmedFill.trimmedFillString,
          bestAnswer.fillOffsetString,
          bestAnswer.entityOffsetString,
          bestAnswer.relOffsetString,
          bestAnswer.extr.confidence)
        
        sb.append(fields.mkString("\t") + "\n")
      }
    }
    sb.toString()
  }
  
  def printFormattedSlotOutputWithExtraInfo(kbpSlot: String, bestAnswers: Seq[Candidate]): String = {

    val sb = new StringBuilder

    if (bestAnswers.isEmpty) {
      sb.append(Iterator("queryID", kbpSlot, "runID", "NIL").mkString("\t") + "\n")
    } else {
      for (bestAnswer <- bestAnswers) {
        val queryData = bestAnswer.pattern
        val bestExtr = bestAnswer.extr
        val slotFillIn = queryData.slotFillIn.get.toLowerCase()
        
        require(slotFillIn == "arg1" || slotFillIn == "arg2" || slotFillIn =="relation")
        
        val fields = Iterator(
          "queryID",
          kbpSlot,
          "runID",
          bestAnswer.extr.sentence.docId,
          bestAnswer.trimmedFill.trimmedFillString,
          "SlotFill: " + bestAnswer.fillField.originalText + " " +bestAnswer.fillOffsetString,
          "Entity: " + bestAnswer.entityField.originalText + " " + bestAnswer.entityOffsetString,
          "Justification: " + bestAnswer.extr.sentence.dgraph.text + " " +bestAnswer.justificationOffsetString,
          bestAnswer.extr.confidence)
        
        sb.append(fields.mkString("\t") + "\n")
      }
    }
    sb.toString()
  }
}