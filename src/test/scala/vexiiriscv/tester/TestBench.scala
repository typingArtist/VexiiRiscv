package vexiiriscv.tester

import rvls.spinal.{FileBackend, RvlsBackend}
import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.Elf
import spinal.lib.misc.test.DualSimTracer
import spinal.lib.sim.{FlowDriver, SparseMemory, StreamMonitor, StreamReadyRandomizer}
import vexiiriscv._
import vexiiriscv.fetch.PcService
import vexiiriscv.misc.VexiiRiscvProbe
import vexiiriscv.riscv.Riscv

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import vexiiriscv.misc.konata


class TestOptions{
  var dualSim = false // Double simulation, one ahead of the other which will trigger wave capture of the second simulation when it fail
  var traceIt = false
  var withProbe = true
  var withRvls = new File("ext/rvls/build/apps/rvls.so").exists()
  var withRvlsCheck = withRvls
//  var withKonata = true
  var failAfter, passAfter = Option.empty[Long]
  var startSymbol = Option.empty[String]
  var startSymbolOffset = 0l
  val bins = ArrayBuffer[(Long, File)]()
  val elfs = ArrayBuffer[File]()
  var testName = Option.empty[String]

  def getTestName() = testName.getOrElse("test")

  if(!withRvls) SpinalWarning("RVLS not detected")

  def addElf(f : File) : this.type = { elfs += f; this }
  def setFailAfter(time : Long) : this.type = { failAfter = Some(time); this }


  def addOptions(parser : scopt.OptionParser[Unit]): Unit = {
    import parser._
    opt[Unit]("dual-sim") action { (v, c) => dualSim = true }
    opt[Unit]("trace") action { (v, c) => traceIt = true }
    opt[Unit]("no-probe") action { (v, c) => withProbe = false; }
    opt[Unit]("no-rvls-check") action { (v, c) => withRvlsCheck = false;  }
    opt[Long]("failAfter") action { (v, c) => failAfter = Some(v) }
    opt[Long]("passAfter") action { (v, c) => passAfter = Some(v) }
    opt[Seq[String]]("load-bin") unbounded() action { (v, c) => bins += java.lang.Long.parseLong(v(0), 16) -> new File(v(1)) }
    opt[String]("load-elf") unbounded() action { (v, c) => elfs += new File(v) }
  }

  def test(compiled : SimCompiled[VexiiRiscv]): Unit = {
    dualSim match {
      case true => DualSimTracer.withCb(compiled, window = 50000 * 10, seed = 2)(test)
      case false => compiled.doSimUntilVoid(name = getTestName(), seed = 2) { dut => disableSimWave(); test(dut, f => if (traceIt) f) }
    }
  }

  def test(dut : VexiiRiscv, onTrace : (=> Unit) => Unit = cb => {}) : Unit = {
    val cd = dut.clockDomain
    cd.forkStimulus(10)

    failAfter.map(delayed(_)(simFailure("Reached Timeout")))
    passAfter.map(delayed(_)(simSuccess()))

    val xlen = dut.database(Riscv.XLEN)

    // Rvls will check that the CPUs are doing things right
    val rvls = withRvlsCheck generate new RvlsBackend(new File(currentTestPath))
    if (withRvlsCheck) {
      rvls.spinalSimFlusher(10 * 10000)
      rvls.spinalSimTime(10000)
    }

    val konataBackend = new konata.Backend(new File(currentTestPath, "konata.log"))
    delayed(1)(konataBackend.spinalSimFlusher(10 * 10000)) // Delayed to ensure this is registred last

    // Collect traces from the CPUs behaviour
    val probe = new VexiiRiscvProbe(dut, konataBackend, withRvls)
    if (withRvlsCheck) probe.add(rvls)
    probe.enabled = withProbe

    // Things to enable when we want to collect traces
    val tracerFile = new FileBackend(new File(currentTestPath, "tracer.log"))
    onTrace {
      enableSimWave()
      if (withRvlsCheck) rvls.debug()

      tracerFile.spinalSimFlusher(10 * 10000)
      tracerFile.spinalSimTime(10000)
      probe.add(tracerFile)
      val r = probe.backends.reverse
      probe.backends.clear()
      probe.backends ++= r
    }

    probe.backends.foreach { b =>
      b.addRegion(0, 0, 0x20000000l, 0xE0000000l) // mem
      b.addRegion(0, 1, 0x10000000l, 0x10000000l) // io
    }

    val mem = SparseMemory(seed = 0)
    // Load the binaries
    for ((offset, file) <- bins) {
      mem.loadBin(offset - 0x80000000l, file)
      if (withRvlsCheck) rvls.loadBin(offset, file)
      tracerFile.loadBin(0, file)
    }

    // load elfs
    for (file <- elfs) {
      val elf = new Elf(file, xlen)
      elf.load(mem, 0)
      if (withRvlsCheck) rvls.loadElf(0, elf.f)
      tracerFile.loadElf(0, elf.f)

      startSymbol.foreach(symbol => fork{
        val pc = elf.getSymbolAddress(symbol) + startSymbolOffset

        waitUntil(cd.resetSim.toBoolean == false); sleep(1)
        println(f"set harts pc to 0x$pc%x")
        dut.host[PcService].simSetPc(pc)
        for(hartId <- probe.hartsIds) probe.backends.foreach(_.setPc(hartId, pc))
      })

      if (elf.getELFSymbol("pass") != null && elf.getELFSymbol("fail") != null) {
        val passSymbol = elf.getSymbolAddress("pass")
        val failSymbol = elf.getSymbolAddress("fail")
        probe.commitsCallbacks += { (hartId, pc) =>
          if (pc == passSymbol) delayed(1) {
            simSuccess()
          }
          if (pc == failSymbol) delayed(1)(simFailure("Software reach the fail symbole :("))
        }
      }
    }

    val fclp = dut.host.get[fetch.CachelessPlugin].map { p =>
      val bus = p.logic.bus
      val cmdReady = StreamReadyRandomizer(bus.cmd, cd)

      case class Cmd(address: Long, id: Int)
      val pending = mutable.ArrayBuffer[Cmd]()

      val cmdMonitor = StreamMonitor(bus.cmd, cd) { p =>
        pending += Cmd(p.address.toLong, p.id.toInt)
      }
      val rspDriver = FlowDriver(bus.rsp, cd) { p =>
        val doIt = pending.nonEmpty
        if (doIt) {
          val cmd = pending.randomPop()
          p.word #= mem.readBytes(cmd.address, p.p.dataWidth / 8)
          p.id #= cmd.id
          p.error #= false
        }
        doIt
      }

      cmdReady.setFactor(2.0f)
      rspDriver.setFactor(2.0f)
    }

    val lsclp = dut.host.get[execute.LsuCachelessPlugin].map { p =>
      val bus = p.logic.bus
      val cmdReady = StreamReadyRandomizer(bus.cmd, cd)

      case class Cmd(write : Boolean, address: Long, data : Array[Byte], bytes : Int)
      val pending = mutable.ArrayBuffer[Cmd]()

      val cmdMonitor = StreamMonitor(bus.cmd, cd) { p =>
        val bytes = 1 << bus.cmd.size.toInt
        pending += Cmd(p.write.toBoolean, p.address.toLong, p.data.toBytes.take(bytes), bytes)
      }
      val rspDriver = FlowDriver(bus.rsp, cd) { p =>
        val doIt = pending.nonEmpty
        if (doIt) {
          val cmd = pending.randomPop()
          cmd.write match {
            case true =>{
              mem.write(cmd.address, cmd.data)
              p.data.randomize()
            }
            case false => {
              val bytes = new Array[Byte](p.p.dataWidth/8)
              simRandom.nextBytes(bytes)
              mem.readBytes(cmd.address, cmd.bytes, bytes, cmd.address.toInt & (p.p.dataWidth/8-1))
              p.data #= bytes
            }
          }
          p.error #= false
        }
        doIt
      }

      cmdReady.setFactor(2.0f)
      rspDriver.setFactor(2.0f)
    }
  }
}

object TestBench extends App{
  val testOpt = new TestOptions()

  val simConfig = SpinalSimConfig()
  simConfig.withFstWave
  simConfig.withTestFolder

  val param = new ParamSimple()
  assert(new scopt.OptionParser[Unit]("VexiiRiscv") {
    help("help").text("prints this usage text")
    testOpt.addOptions(this)
  }.parse(args, Unit).nonEmpty)

  val compiled = simConfig.compile(VexiiRiscv(param.plugins()))
  testOpt.test(compiled)
  Thread.sleep(100)
}
