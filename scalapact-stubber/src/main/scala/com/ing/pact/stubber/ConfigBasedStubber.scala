package com.ing.pact.stubber

import java.io.{File, FileFilter}
import java.util.ResourceBundle
import java.util.concurrent.{ExecutorService, Executors}

import com.itv.scalapact.shared._
import com.itv.scalapact.shared.http.PactStubService._
import com.itv.scalapactcore.common.PactReaderWriter._
import com.itv.scalapactcore.stubber.InteractionManager
import com.typesafe.config.{Config, ConfigFactory}
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder

import scala.concurrent.duration._
import scala.io.Source

case class ServerSpec(name: String, providerName: Option[String], port: Int, host: String, strict: Boolean, sslContextData: Option[SSLContextData], pacts: Seq[File], errorsAbort: Boolean, clientAuth: Boolean) {
  def singleContextMap: SslContextMap = sslContextData.fold(SslContextMap.defaultEmptyContextMap)(data => new SslContextMap(Map(name -> data)))

}


object ServerSpec extends Pimpers {
  val jsonFileNameFilter: FileFilter {
    def accept(pathname: File): Boolean
  } = new FileFilter() {
    override def accept(pathname: File): Boolean = pathname.getName.endsWith(".json")
  }
  def makePactFiles(directoryName: String): Seq[File] = new File(directoryName).listFilesInDirectory(jsonFileNameFilter)

  def forHttpValidation(name: String, port: Int, directoryName: String, provider: String, strict: Boolean): ServerSpec = ServerSpec(name, Some(provider), port, "localhost", strict, None, makePactFiles(directoryName), true, false)
  def forHttpValidation(name: String, port: Int, directoryName: String,  strict: Boolean): ServerSpec = ServerSpec(name, None, port, "localhost", strict, None, makePactFiles(directoryName), true, false)

  def forHttpsValidation(name: String, port: Int, directoryName: String, provider: String, strict: Boolean, sslContext: SSLContextData, clientAuth: Boolean): ServerSpec = ServerSpec(name, Some(provider), port, "localhost", strict, Some(sslContext), makePactFiles(directoryName), true, clientAuth)
  def forHttpsValidation(name: String, port: Int, directoryName: String, strict: Boolean, sslContext: SSLContextData, clientAuth: Boolean): ServerSpec = ServerSpec(name, None, port, "localhost", strict, Some(sslContext), makePactFiles(directoryName), true, clientAuth)


  implicit object FromConfigForServerSpec extends FromConfigWithKey[ServerSpec] {

    override def apply(name: String, config: Config): ServerSpec = {
      ServerSpec(name,
        port = config.getInt("port"),
        host = if (config.hasPath("host")) config.getString("host") else "localhost",
        strict = false,
        sslContextData = config.getOptionalObject[SSLContextData]("ssl-context"),
        pacts = config.getFiles("directory")(jsonFileNameFilter),
        errorsAbort = config.getBoolean("errorsAbort"),
        providerName = config.getOption[String]("provider"),
        clientAuth = config.hasPath("client-auth") && config.getBoolean("client-auth")
      )
    }

  }

  def interactionManager(pacts: Seq[Pact], providerName: Option[String], interactionManager: InteractionManager): InteractionManager = {
    def canUse(t: Pact) = providerName.fold(true)(pn => pn == t.provider.name)
    pacts.foldLeft(interactionManager) { case (im, t) if canUse(t) => t.interactions ==> im.addInteractions; im; case (im, _) => im }
  }


  implicit class ServerSpecPimper(spec: ServerSpec)(implicit executorService: ExecutorService) {
    def toBlaizeServer(pacts: Seq[Pact]): Seq[Server] = {
      pacts match {
        case Nil =>
          Seq()

        case _ =>
          Seq({
            implicit val sslContextMap: SslContextMap = spec.singleContextMap
            val service = ServiceMaker.service(interactionManager(pacts, spec.providerName, new InteractionManager), spec.strict)
            val result = BlazeBuilder
              .bindHttp(spec.port, spec.host).
              withServiceExecutor(executorService)
              .withIdleTimeout(60.seconds)
              .withOptionalSsl(spec.sslContextData.map(_ => ContextNameAndClientAuth(spec.name, spec.clientAuth)))
              .withConnectorPoolSize(10)
              .mountService(service, "/")
              .run
            result
          })
      }
    }
  }

  implicit object MessageFormatDataForServerSpec extends MessageFormatData[ServerSpec] {
    override def apply(spec: ServerSpec): Seq[String] = Seq(spec.name, spec.port.toString, spec.sslContextData.map(_.toString).getOrElse("none"))
  }

  def loadPacts: ServerSpec => Seq[Either[String, Pact]] = { serverSpec: ServerSpec => serverSpec.pacts.map(Source.fromFile(_, "UTF-8").mkString).map(pactReader.jsonStringToPact) }

  def apply(configFile: File): Seq[ServerSpec] = {
    if (!configFile.exists())
      throw new IllegalArgumentException(s"File: ${configFile.getAbsolutePath} doesn't exist")

    configFile ==> ConfigFactory.parseFile ==> makeListFromConfig[ServerSpec](key = "servers")
  }
}

case class ServerSpecAndPacts(spec: ServerSpec, issuesAndPacts: List[Either[String, Pact]])


object ServerSpecAndPacts extends Pimpers {

  implicit val showable: Showable[Either[String, Pact]] =
    Showable.create {
      case Left(l) => l
      case Right(p) => p.renderAsString
    }

  def printIssuesAndReturnPacts(title: String)(serverSpecAndPacts: ServerSpecAndPacts)(implicit resourceBundle: ResourceBundle): Seq[Pact] = {
    serverSpecAndPacts.issuesAndPacts.printWithTitle(title, ())
    serverSpecAndPacts.issuesAndPacts.values
  }

}

object ConfigBasedStubber {
  def apply(configfile: File)(implicit resources: ResourceBundle, executorService: ExecutorService): ConfigBasedStubber = new ConfigBasedStubber(ServerSpec(configfile))
  def apply(serverSpec: ServerSpec)(implicit resources: ResourceBundle, executorService: ExecutorService): ConfigBasedStubber = new ConfigBasedStubber(Seq(serverSpec))
  def apply(serverSpec1: ServerSpec, serverSpec2: ServerSpec)(implicit resources: ResourceBundle, executorService: ExecutorService): ConfigBasedStubber = new ConfigBasedStubber(Seq(serverSpec1, serverSpec2))
  def apply(serverSpec1: ServerSpec, serverSpec2: ServerSpec, serverSpec3: ServerSpec)(implicit resources: ResourceBundle, executorService: ExecutorService): ConfigBasedStubber = new ConfigBasedStubber(Seq(serverSpec1, serverSpec2, serverSpec3))
}


class ConfigBasedStubber(specs: Seq[ServerSpec])(implicit val resources: ResourceBundle, executorService: ExecutorService) extends Pimpers {
  "header.running".printlnFromBundle(specs.mkString("\n"))


  implicit def errorStrategy[L, R](implicit spec: ServerSpec, showable: Showable[L]): ErrorStrategy[L, R] =
    if(spec.errorsAbort) ErrorStrategy.printErrorsAndAbort("error.loading.server", spec.name)
    else ErrorStrategy.printErrorsAndUseGood("error.loading.server", spec.name)

  implicit object MessageFormatDataForRequest extends MessageFormatData[InteractionRequest] {
    override def apply(ir: InteractionRequest): Seq[String] = MessageFormatData((ir.method, ir.path))
  }

  def handleErrors(seq: Seq[Either[String, Pact]])(implicit spec: ServerSpec): Seq[Pact] = seq.handleErrors
  def printServerStarting(pacts: Seq[Pact])(implicit spec: ServerSpec): Seq[Pact] = pacts.ifNotEmpty("message.loading.server".printlnFromBundle((spec, pacts))) //.foreach { pact => pact.interactions.foreach { i => "message.pact.summary".printlnFromBundle(i.request) } }

  val servers: Seq[Server] = (specs mapWith { implicit spec => ServerSpec.loadPacts ===> handleErrors =^> printServerStarting ===> spec.toBlaizeServer }).flatten

  def shutdown(): Unit = servers.foreach(_.shutdownNow())

  def waitForever(): Unit = {
    while (true)
      Thread.sleep(10000000)
  }
}

object Stubber extends App with Pimpers {
  implicit val resources: ResourceBundle = ResourceBundle.getBundle("messages")
  implicit val executorService: ExecutorService = Executors.newFixedThreadPool(10)

  val fileName: String = args match {
    case Array() => "stubber.cfg"
    case Array(name) => name
    case a => "error.usage".printlnFromBundle(()); System.exit(2); throw new RuntimeException()
  }

  ConfigBasedStubber(new File(fileName)).waitForever()

}
