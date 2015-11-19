package com.votors.ml

import java.util.concurrent.atomic.AtomicInteger

import com.votors.common.Conf
import com.votors.common.Utils._
import com.votors.umls.UmlsTagger2
import opennlp.tools.parser.Parse

import scala.StringBuilder
import scala.collection.mutable.ArrayBuffer
import java.io._
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern
import java.util.Properties

import opennlp.tools.sentdetect.{SentenceDetectorME, SentenceModel}

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.immutable.{List, Range}
import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.io.Source
import scala.io.Codec

import org.apache.commons.lang3.StringUtils
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.snowball.SnowballFilter
import org.apache.lucene.util.Version
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.impl.HttpSolrServer
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest
import org.apache.solr.common.SolrDocumentList
import org.apache.solr.common.params.CommonParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.util.ContentStreamBase
import com.votors.common.Utils._
import com.votors.common.Utils.Trace._

import opennlp.tools.cmdline.BasicCmdLineTool
import opennlp.tools.cmdline.CLI
import opennlp.tools.cmdline.PerformanceMonitor
import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSSample
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.stemmer.PorterStemmer
import opennlp.tools.stemmer.snowball.englishStemmer
import opennlp.tools.tokenize.WhitespaceTokenizer
import opennlp.tools.util.ObjectStream
import opennlp.tools.util.PlainTextByLineStream
import java.sql.{Statement, Connection, DriverManager, ResultSet}

import org.apache.commons.csv._

/**
 * Created by Jason on 2015/11/9 0009.
 */

/**
 * Ngram is the most basic format of a 'term', but a Ngram may be not a 'term'
 *
 * Ngram delimiter: ${Ngram.Delimiter}, Ngram searching should stop at the delimiter
 * the first and the last token can not be punctuation or blank
 *
 */
class Ngram (var text: String) extends java.io.Serializable{
  var id = -1                // identify of this gram  - looks like useless. forget it.
  //var text = ""         // final format of this gram. after stemmed/variant...
  var n: Int = 0            // number of word in this gram
  var hBlogId = new mutable.HashMap[Int, Stat]()       // save the blog id and one of its sentence id.
  var context = new Context()   // context of this gram
  var tfAll:Long = 0     // term frequency of all document
  var df = 0        // document frequency
  var tfdf = 0.0     // tfdf of this gram: tf-idf = log(1+ avg(tf)) * log(1+n(t)/N)
  var cvalue = -1.0    // c-value
  //var nestedCnt = 0   // the number of term that contains this gram.
  var nestedTf: Long =0     // the total frequency of terms that contains this gram
  var nestTerm = new mutable.HashSet[String]()         // nested term
  var umlsScore = (0.0,0.0,"","") // (score as CHV, score as "UMLS", CUI of the CHV term, CUI of the UMLS term)
  var posString = ""
  var isPosNN = false  // sytax pattern :Noun+Noun
  var isPosAN = false  // sytax pattern : (Adj|Noun) +Noun
  var isPosANAN = false  // sytax pattern : ((Adj|Noun) +|((Adj|Noun)?(NounP rep) ?)(Adj|Noun)?)Noun

  /* //XXX: ### !!!! add a file should also check it is processed in method merge !!!!####*/

  def + (other: Ngram) = merge(other)
  def merge (other: Ngram): Ngram = {
    val newNgram = new Ngram(this.text)
    newNgram.id = this.id
    if (this.n != other.n)  trace(ERROR, s"warn: Not the same n in merge Ngram ${this.text}, ${other.text}, ${this.n}, ${other.n}!")
    if (this.tfAll < other.tfAll)
      newNgram.n = other.n
    else
      newNgram.n = this.n

    other.hBlogId.foreach(kv => newNgram.hBlogId.put(kv._1,kv._2)) //safe, because blogId could not be the same
    this.hBlogId.foreach(kv => newNgram.hBlogId.put(kv._1,kv._2)) //safe, because blogId could not be the same
    //this.context = ?
    newNgram.tfAll = this.tfAll + other.tfAll
    newNgram.df = this.df + other.df  // this is safe
    //newNgram.nestedCnt = this.nestedCnt + other.nestedCnt   // not safe to calculate like this. use set operation
    // tfdf   this is calculated after merge
    // cvalue   this is calculated after merge
    newNgram.nestedTf = this.nestedTf + other.nestedTf
    newNgram.nestTerm ++= this.nestTerm
    newNgram.nestTerm ++= other.nestTerm
    newNgram.umlsScore = this.umlsScore  // this is calculated after merge
    newNgram.posString = this.posString
    newNgram.isPosAN = this.isPosAN
    newNgram.isPosNN = this.isPosNN
    newNgram.isPosANAN = this.isPosANAN

    traceFilter(INFO,this.text,s"Merge!! ${this.text} ${other.text} ${this.id} ${other.id} tfall ${this.tfAll}, ${other.tfAll} ${this.hBlogId.mkString(",")}, ${other.hBlogId.mkString(",")}")
    newNgram
  }
  /**
   * Update info on create or found the gram.
   * @param blogId
   * @param sentId
   */
  def updateOnHit(blogId: Int, sentId: Int): Unit = {
    val stat = hBlogId.getOrElseUpdate(blogId,{df += 1; new Stat(blogId)})
    stat.tf += 1
    tfAll += 1
  }
  /**
   * When the gram is create, we get some useful info from the sentence.
   * gram is sentence.tokens[start, end)
   * pos tag see: http://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
   * 6.	  IN	Preposition or subordinating conjunction
   * 7.	  JJ	Adjective
   * 8.	  JJR	Adjective, comparative
   * 9.	  JJS	Adjective, superlative
   * 12.	NN	Noun, singular or mass
   * 13.	NNS	Noun, plural
   * 14.	NNP	Proper noun, singular
   * 15.	NNPS	Proper noun, plural
   *
   * @param sentence
   * @param start [start, end)
   * @param end [start, end)
   * @return
   */

  def updateOnCreated(sentence: Sentence, start: Int, end: Int) = {

    // update the pos pattern syntax of the gram.
    val gramPos = Array() ++ sentence.Pos.slice(start, end)
    /**
    0. Noun, basic pattern, name N
    1. Noun+Noun, named NN
    2. (Adj|Noun)+Noun, named ANN
    3. ((Adj|Noun)+|((Adj|Noun)?(NounPrep)?)(Adj|Noun)?)Noun, named ANAN
      */
    this.posString = gramPos.map(pos => {
      if (pos == "NN" || pos == "NNS" || pos == "NNP" || pos == "NNPS")
        "N" // noun
      else if (pos == "JJ" || pos == "JJR" || pos == "JJS")
        "A" // adjective
      else if (pos == "IN")
        "P" // preposition
      else
        "O" // others
    }).mkString("")
    traceFilter(INFO, this.text, s"${this.text} 's Pos string for pattern match is ${posString}")
    if (posString.matches(".*N+N.*")) {
      this.isPosNN = true
      traceFilter(INFO, this.text, s"${this.text} 's Pos string for pattern match is NN")
    }
    if (posString.matches("A+N")) {
      this.isPosAN = true
      traceFilter(INFO, this.text, s"${this.text} 's Pos string for pattern match is AN")
    }
    if (posString.matches("((A|N)+|((A|N)*(NP)?)(A|N)*)N")) {
      this.isPosANAN = true
      traceFilter(INFO, this.text, s"${this.text} 's Pos string for pattern match is ANAN")
    }


  }


  def procTfdf(docNum: Long): Ngram = {
    this.tfdf = log2(1+(log2(1+1.0*tfAll/docNum) * df))  // supress the affect of tfAll
    // gram.tfidf = Math.log(1+gram.tfAll/gram.hBlogId.size) * Math.log(1+docNum/gram.hBlogId.size)
    //gram.tfdf = Math.sqrt(gram.tfdf)
    this
  }


  def getNestInfo(hNgram: Seq[Ngram]): Ngram = {
    val Ta = new ArrayBuffer[Ngram]()
    hNgram.foreach(ngram =>{
      // if it is nested
      if (this != ngram && ngram.text.matches(".*\\b" + this.text + "\\b.*")) {
        Ta.append(ngram)
      }
    })
    //this.nestedCnt = Ta.size
    this.nestedTf = if (Ta.size > 0) Ta.map(_.tfAll).reduce(_+_) else 0
    this.nestTerm ++= Ta.map(_.text)
    traceFilter(INFO,text,s"\n**** ${text} nested info: ${Ta.size}, ${nestedTf},terms:: ${nestTerm.mkString(",")},gram: ${Ta.mkString(",")}    *****\n")
    this
  }

  // calculate the cValue. Since log(1)==0, we should add 1 to the value put into log()
  def getCValue() = {
    this.cvalue = if (this.nestTerm.size == 0) {
      log2(this.n+1) * this.tfAll
    }else{
      log2(this.n+1) * (this.tfAll - (this nestedTf)/this.nestTerm.size)
    }

    /**
     *  c-value is negative, this is possible, b/c shorter term may be filter out by syntax filter, but the longer term will not.
     *  That means tfAll may be less than nestedTf, so get a negative c-value.
     *  In this case, it means the shorter term are less possible be a independent term(and, c-value=0 has this meaning too.)..
     */
    if (this.cvalue < 0)
      this.cvalue = 0
    this
  }

  override def toString(): String = {
    toString(if (text!="fat")false else true)
  }
  def toString(detail: Boolean): String = {
    f"[${n}]${text}%-12s|tfdf(${tfdf}%.2f,${tfAll}%2d,${df}%2d),cvalue(${cvalue}%.2f,${this.nestTerm.size}%2d,${nestedTf}%2d),umls(${umlsScore._1}%.2f,${umlsScore._2}%.2f)" +
     f"pt:(${posString}:${bool2Str(isPosNN)},${bool2Str(isPosAN)},${bool2Str(isPosANAN)}) " +
      {if (detail) f"blogs:${hBlogId.size}:${hBlogId.mkString(",")}" else ""}
  }
}

/**
 * context of a Ngram. such as window, syntax tree
 */
class Context extends java.io.Serializable{
  var window = null
  var syntex = null
}

class Stat(var blogId: Int = 0, var sentId:Int = 0) extends java.io.Serializable {
  //sentId: Only one sentenid will keep, for debug
  var tf = 0    // term frequency

  override def toString(): String = {
    s"tf:${tf}"
  }
}
class TokenState extends java.io.Serializable {
  var delimiter = false
  override def toString(): String = {
    s"${if(delimiter)"d" else ""}"
  }
}
class Sentence extends java.io.Serializable {
  var blogId = 0
  var sentId = 0
  var words: Array[String] = null      // original words in the sentence. nothing is filtered.
  var tokens: Array[String] = null     // Token from openNlp
  var tokenSt: Array[TokenState] = null // the special status of the token. e.g. if it is a delimiter
  var Pos :Array[String] = null        // see http://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
  //var chunk:Array[opennlp.tools.util.Span] = null
  //var parser: Parse = null


  override def toString (): String = {
    val parseStr = new StringBuffer()
    //if (parser != null)parser.show(parseStr)
    s"${blogId}, ${sentId}\t" + "\n" +
      "sentence: " + words.mkString(" ") + "\n" +
      "words: " + words.mkString(" $ ") + "\n" +
      "tokens: " +   tokens.mkString(" $ ") + "\n" +
      "token-State: " + tokenSt.mkString(" $ ") + "\n" +
      "POS: " + Pos.mkString(" $ ") + "\n" +
      //"chunk: " + chunk.mkString(" $ ") + "\n" +
      "syntax: " + parseStr
  }

}

case class KV[K,V](val k:K, val v:V) {
  override def equals(that: Any): Boolean = {
    if (that.isInstanceOf[KV[K,V]]) {
      val other = that.asInstanceOf[KV[K,V]]
      other.k.equals(this.k)
    } else {
      false
    }
  }
  override def hashCode(): Int = {
    k.hashCode()
  }
}

object Ngram {
  final val WinLen = 10
  final val N = 5
  final val Delimiter = Pattern.compile("[,;\\:\\(\\)\\[\\]\\{\\}\"]+")   // the char using as delimiter of a Ngram of token, may be ,//;/:/"/!/?
  val idCnt = new AtomicInteger()

  @transient val  hSents = new mutable.LinkedHashMap[(Int,Int),Sentence]()  //(blogId, sentId)
  @transient val hNgrams = new mutable.LinkedHashMap[String,Ngram]()       // Ngram.text
  /**
   * get a Ngram from the hashtable, add a new one if not exists
   * @param gram
   * @return
   */
  def getNgram (gram: String, hNgrams: mutable.LinkedHashMap[String,Ngram]): Ngram = {
    hNgrams.getOrElseUpdate(gram, {
      if (gram.equals("diabet"))println(s"create Ngram ${gram}")
      new Ngram(gram)
    })
  }

  /**
   * gram is sentence.tokens[start, end)
   * pos tag see: http://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
   * 6.	  IN	Preposition or subordinating conjunction
   * 7.	  JJ	Adjective
   * 8.	  JJR	Adjective, comparative
   * 9.	  JJS	Adjective, superlative
   * 12.	NN	Noun, singular or mass
   * 13.	NNS	Noun, plural
   * 14.	NNP	Proper noun, singular
   * 15.	NNPS	Proper noun, plural
   *
   * @param gram
   * @param sentence
   * @param start [start, end)
   * @param end [start, end)
   * @return
   */
  def checkNgram(gram: String, sentence: Sentence, start: Int, end: Int): Boolean = {
    var ret = true
    // check the stop word. if the gram contains any stop word
    if (Nlp.checkStopword(gram) || (gram.contains(" ") && gram.split(" ").filter(Nlp.checkStopword(_)).size > 0))
      ret &&= false

    // check if the gram contain at least one noun
    var containNoun = false
    val nounPos = Conf.posInclusive.split(" ")
    val gramPos = Array() ++ sentence.Pos.slice(start,end)
    traceFilter(INFO,gram,s"${gram} 's Pos is ${gramPos.mkString(",")}, ${sentence}")
    gramPos.foreach( p => {
      if (nounPos.contains(p)) containNoun = true
    })
    ret &&= containNoun
    ret
  }

  def procTfdf(docNum: Long): Unit = {
    hNgrams.foreach(kv => {
      val gram = kv._2
      gram.procTfdf(docNum)
    })
  }

  def updateAfterReduce(itr: Iterator[Ngram], docNum: Long) = {
    val tagger = new UmlsTagger2("http://localhost:8983/solr", Conf.rootDir)
    val s = itr.toSeq
    println(s"size itr ${s.size}")
    s.foreach(gram => {
      gram.procTfdf(docNum)
      gram.getCValue()
      gram.umlsScore = tagger.getUmlsScore(gram.text)
      //println("update " +gram)
      gram
    })
    s.iterator
  }
  def main (argv: Array[String]): Unit = {
  }
}