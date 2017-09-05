package ch.epfl.scala

import ch.epfl.scala.profilers.ProfilingImpl
import ch.epfl.scala.profilers.tools.Logger

import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}

class ProfilingPlugin(val global: Global) extends Plugin {
  val name = "scalac-profiling"
  val description = "Adds instrumentation to keep an eye on Scalac performance."
  val components = List[PluginComponent](NewTypeComponent)

  private final val LogCallSite = "log-macro-call-site"
  case class PluginConfig(logCallSite: Boolean)
  private final val config = PluginConfig(super.options.contains(LogCallSite))
  private final val logger = new Logger(global)

  private def pad20(option: String): String = option + (" " * (20 - option.length))
  override def init(ops: List[String], e: (String) => Unit): Boolean = true
  override val optionsHelp: Option[String] = Some(s"""
       |-P:$name:${pad20(LogCallSite)} Logs macro information for every call-site.
    """.stripMargin)

  // Make it not `lazy` and it will slay the compiler :)
  lazy val implementation = new ProfilingImpl(ProfilingPlugin.this.global, logger)
  implementation.registerProfilers()

  private object NewTypeComponent extends PluginComponent {
    override val global: implementation.global.type = implementation.global
    override val phaseName: String = "compile-newtype"
    override val runsAfter: List[String] = List("jvm")
    override val runsBefore: List[String] = List("terminal")

    def reportStatistics(): Unit = {
      // Make sure we get type information after typer to avoid crashing the compiler
      global.exitingTyper {
        val macroProfiler = implementation.getMacroProfiler
        if (config.logCallSite)
          logger.info("Macro data per call-site", macroProfiler.perCallSite)
        logger.info("Macro data per file", macroProfiler.perFile)
        logger.info("Macro data in total", macroProfiler.inTotal)
        val expansions =
          macroProfiler.repeatedExpansions.map(kv => global.showCode(kv._1) -> kv._2)
        logger.info("Macro repeated expansions", expansions)
        import global.statistics.{implicitSearchesByType, implicitSearchesByPos}
        logger.info(
          "Implicit searches by type",
          implicitSearchesByType.map(kv => kv._1.toString -> kv._2)
        )
        logger.info("Implicit searches by position", implicitSearchesByPos)
      }
    }

    override def newPhase(prev: Phase): Phase = {
      new StdPhase(prev) {
        override def apply(unit: global.CompilationUnit): Unit = ()
        override def run(): Unit = {
          super.run()
          reportStatistics()
        }
      }
    }
  }
}
