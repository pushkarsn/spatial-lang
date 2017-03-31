package templates

import chisel3._
import chisel3.util
import scala.collection.mutable.HashMap


class flatW(val w: Int) extends Bundle {
  val addr = UInt(w.W)
  val data = UInt(w.W)
  val en = Bool()

  override def cloneType = (new flatW(w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class flatR(val w: Int) extends Bundle {
  val addr = UInt(w.W)
  val en = Bool()

  override def cloneType = (new flatR(w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class multidimW(val N: Int, val w: Int) extends Bundle {
  val addr = Vec(N, UInt(w.W))
  val data = UInt(w.W)
  val en = Bool()

  override def cloneType = (new multidimW(N, w)).asInstanceOf[this.type] // See chisel3 bug 358
}
class multidimR(val N: Int, val w: Int) extends Bundle {
  val addr = Vec(N, UInt(w.W))
  val en = Bool()
  
  override def cloneType = (new multidimR(N, w)).asInstanceOf[this.type] // See chisel3 bug 358
}

class Mem1D(val size: Int, val isFifo: Boolean, bitWidth: Int = 32) extends Module { // Unbanked, inner 1D mem
  def this(size: Int) = this(size, true)

  val io = IO( new Bundle {
    val w = Input(new flatW(bitWidth))
    val r = Input(new flatR(bitWidth))
    val output = new Bundle {
      val data  = Output(UInt(bitWidth.W))
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val error = Output(Bool())
      // val addrProbe = Output(UInt(bitWidth.W))
    }
  })

  // We can do better than MaxJ by forcing mems to be single-ported since
  //   we know how to properly schedule reads and writes
  val m = Mem(size, UInt(bitWidth.W) /*, seqRead = true deprecated? */)
  val wInBound = io.w.addr < (size).U
  val rInBound = io.r.addr < (size).U

  if (isFifo) { // Fifos need to be dual port to avoid strangeness
    when (io.w.en & wInBound) {m(io.w.addr) := io.w.data}
    io.output.data := m(io.r.addr)
  } else {
    val reg_rAddr = Reg(UInt())
    when (io.w.en & wInBound) {m(io.w.addr) := io.w.data}
    .elsewhen (io.r.en & rInBound) {reg_rAddr := io.r.addr}
    io.output.data := m(reg_rAddr)
  }

  io.debug.invalidRAddr := ~rInBound
  io.debug.invalidWAddr := ~wInBound
  io.debug.rwOn := io.w.en & io.r.en
  io.debug.error := ~rInBound | ~wInBound | (io.w.en & io.r.en)
  // io.debug.addrProbe := m(0.U)

}


// Last dimension is the leading-dim
class MemND(val dims: List[Int], bitWidth: Int = 32) extends Module { 
  val depth = dims.reduce{_*_} // Size of memory
  val N = dims.length // Number of dimensions

  val io = IO( new Bundle {
    val w = Input(new multidimW(N, bitWidth))
    val wMask = Input(Bool()) // Mask passed by SRAM for when a multidimW comes through without "really" being selected
    val r = Input(new multidimR(N, bitWidth))
    val rMask = Input(Bool()) // Mask passed by SRAM for when a multidimR comes through without "really" being selected
    val output = new Bundle {
      val data  = Output(UInt(bitWidth.W))
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val error = Output(Bool())
    }
  })

  // Init masks
  val wMask = Wire(true.B)
  wMask := io.wMask
  val rMask = Wire(true.B)
  rMask := io.rMask

  // Instantiate 1D mem
  val m = Module(new Mem1D(depth))

  // Address flattening
  m.io.w.addr := io.w.addr.zipWithIndex.map{ case (addr, i) =>
    addr * (dims.drop(i).reduce{_*_}/dims(i)).U
  }.reduce{_+_}
  m.io.r.addr := io.r.addr.zipWithIndex.map{ case (addr, i) =>
    addr * (dims.drop(i).reduce{_*_}/dims(i)).U
  }.reduce{_+_}

  // Check if read/write is in bounds
  val rInBound = io.r.addr.zip(dims).map { case (addr, bound) => addr < bound.U }.reduce{_&_}
  val wInBound = io.w.addr.zip(dims).map { case (addr, bound) => addr < bound.U }.reduce{_&_}

  // Connect the other ports
  m.io.w.data := io.w.data
  m.io.w.en := io.w.en & wMask
  m.io.r.en := io.r.en & rMask
  io.output.data := m.io.output.data
  io.debug.invalidWAddr := ~wInBound
  io.debug.invalidRAddr := ~rInBound
  io.debug.rwOn := io.w.en & wMask & io.r.en & rMask
  io.debug.error := ~wInBound | ~rInBound | (io.w.en & io.r.en)
}


/*
                            
                                                           __________             ___SRAM__
         _        _           _______                     |          |--bundleND-|   MemND |               
        | |------| |---------|       |                    |          |           |_________|                        
   IO(Vec(bundleSRAM))-------| Mux1H |-----bundleSRAM-----|   VAT    |--bundleND-|   MemND |    
        |_|------|_|---------|_______|                    |          |           |_________|                        
                               | | |                      |__________|--bundleND-|   MemND |               
                             stageEnables                                        |_________|
                                                                        
                                                                    
*/
class SRAM(val logicalDims: List[Int], val w: Int, 
           val banks: List[Int], val strides: List[Int], val numWriters: Int, val numReaders: Int,
           val wPar: Int, val rPar: Int, val bankingMode: String, val bitWidth: Int = 32) extends Module { 

  // Overloaded construters
  // Tuple unpacker
  def this(tuple: (List[Int], Int, List[Int], List[Int], Int, Int,
           Int, Int, String)) = this(tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7,tuple._8,tuple._9)
  // Bankmode-less
  def this(logicalDims: List[Int], w: Int, 
           banks: List[Int], strides: List[Int], numWriters: Int, numReaders: Int,
           wPar: Int, rPar: Int) = this(logicalDims, w, banks, strides, numWriters, numReaders, wPar, rPar, "strided")
  // If 1D, spatial will make banks and strides scalars instead of lists
  def this(logicalDims: List[Int], w: Int, 
           banks: Int, strides: Int, numWriters: Int, numReaders: Int,
           wPar: Int, rPar: Int) = this(logicalDims, w, List(banks), List(strides), numWriters, numReaders, wPar, rPar, "strided")

  val depth = logicalDims.reduce{_*_} // Size of memory
  val N = logicalDims.length // Number of dimensions

  val io = IO( new Bundle {
    // TODO: w bundle gets forcefully generated as output in verilog
    //       so the only way to make it an input seems to flatten the
    //       Vec(numWriters, Vec(wPar, _)) to a 1D vector and then reconstruct it
    val w = Vec(numWriters*wPar, Input(new multidimW(N, bitWidth)))
    val globalWEn = Vec(numWriters, Input(Bool()))
    val wSel = Vec(numWriters, Input(Bool())) // Selects between multiple write bundles
    val r = Vec(numReaders*rPar,Input(new multidimR(N, bitWidth))) // TODO: Spatial allows only one reader per mem
    val rSel = Vec(numReaders, Input(Bool()))
    val output = new Bundle {
      val data  = Vec(rPar, Output(UInt(bitWidth.W)))
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val readCollision = Output(Bool())
      val writeCollision = Output(Bool())
      val error = Output(Bool())
    }
  })

  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val physicalDims = logicalDims.zip(banks).map { case (dim, b) => dim/b}
  val numMems = banks.reduce{_*_}

  // Create physical mems
  val m = (0 until numMems).map{ i => Module(new MemND(physicalDims))}

  // Reconstruct io.w as 2d vector
  val reconstructedW = (0 until numWriters).map{ i => 
    Vec((0 until wPar).map { j => io.w(i*wPar + j) })
  }
  val reconstructedR = (0 until numReaders).map{ i => 
    Vec((0 until rPar).map { j => io.r(i*rPar + j) })
  }

  // Mux wrideBundles and connect to internal
  val selectedWVec = chisel3.util.Mux1H(io.wSel, reconstructedW)
  val selectedRVec = chisel3.util.Mux1H(io.rSel, reconstructedR)
  val selectedGlobalWen = chisel3.util.Mux1H(io.wSel, io.globalWEn)

  // TODO: Should connect multidimW's directly to their banks rather than all-to-all connections
  // Convert selectedWVec to translated physical addresses
  val wConversions = selectedWVec.map{ wbundle => 
    // Writer conversion
    val convertedW = Wire(new multidimW(N,w))
    val physicalAddrsW = wbundle.addr.zip(banks).map{ case (logical, b) => logical / b.U }
    physicalAddrsW.zipWithIndex.foreach { case (calculatedAddr, i) => convertedW.addr(i) := calculatedAddr}
    convertedW.data := wbundle.data
    convertedW.en := wbundle.en & selectedGlobalWen
    val bankCoordsW = wbundle.addr.zip(banks).map{ case (logical, b) => logical % b.U }
    val bankCoordW = bankCoordsW.zipWithIndex.map{ case (c, i) => c*(banks.drop(i).reduce{_*_}/banks(i)).U }.reduce{_+_}

    (convertedW, bankCoordW)
  }
  val convertedWVec = wConversions.map{_._1}
  val bankIdW = wConversions.map{_._2}

  val rConversions = selectedRVec.map{ rbundle => 
    // Reader conversion
    val convertedR = Wire(new multidimR(N,w))
    val physicalAddrs = rbundle.addr.zip(banks).map{ case (logical, b) => logical / b.U }
    physicalAddrs.zipWithIndex.foreach { case (calculatedAddr, i) => convertedR.addr(i) := calculatedAddr}
    convertedR.en := rbundle.en
    val bankCoordsR = rbundle.addr.zip(banks).map{ case (logical, b) => logical % b.U }
    val bankCoordR = bankCoordsR.zipWithIndex.map{ case (c, i) => c*(banks.drop(i).reduce{_*_}/banks(i)).U }.reduce{_+_}

    (convertedR, bankCoordR)
  }
  val convertedRVec = rConversions.map{_._1}
  val bankIdR = rConversions.map{_._2}

  // TODO: Doing inefficient thing here of all-to-all connection between bundlesNDs and MemNDs
  // Convert bankCoords for each bundle to a bit vector
  // TODO: Probably need to have a dummy multidimW port to default to for unused banks so we don't overwrite anything
  m.zipWithIndex.foreach{ case (mem, i) => 
    val bundleSelect = bankIdW.map{ b => b === i.U }
    mem.io.wMask := bundleSelect.reduce{_|_}
    mem.io.w := chisel3.util.PriorityMux(bundleSelect, convertedWVec)
  }

  // TODO: Doing inefficient thing here of all-to-all connection between bundlesNDs and MemNDs
  // Convert bankCoords for each bundle to a bit vector
  m.zipWithIndex.foreach{ case (mem, i) => 
    val bundleSelect = bankIdR.map{ b => (b === i.U) }
    mem.io.rMask := bundleSelect.reduce{_|_}
    mem.io.r := chisel3.util.PriorityMux(bundleSelect, convertedRVec)
  }

  // Connect read data to output
  io.output.data.zip(bankIdR).foreach { case (wire, id) => 
    val sel = (0 until numMems).map{ i => (id === i.U)}
    val datas = m.map{ _.io.output.data }
    val d = chisel3.util.PriorityMux(sel, datas)
    wire := d
  }

  var wId = 0
  def connectWPort(wBundle: Vec[multidimW], en: Bool, ports: List[Int]) {
    val port = ports(0) // Should never have more than 1 for SRAM
    (0 until wBundle.length).foreach{ i => 
      io.w(i + wId*wPar) := wBundle(i) 
    }
    io.globalWEn(wId) := en 
    io.wSel(wId) := en & ( wBundle.map{ _.en}.reduce{_||_} )
    wId = wId + 1
  }
  var rId = 0
  def connectRPort(rBundle: Vec[multidimR], en: Bool, port: Int) {
    (0 until rBundle.length).foreach{ i => 
      io.r(i + rId*rPar) := rBundle(i) 
    }
    rId = rId + 1
  }


  // Connect debug signals
  val wInBound = selectedWVec.map{ v => v.addr.zip(logicalDims).map { case (addr, bound) => addr < bound.U }.reduce{_&_}}.reduce{_&_}
  val rInBound = selectedRVec.map{ v => v.addr.zip(logicalDims).map { case (addr, bound) => addr < bound.U }.reduce{_&_}}.reduce{_&_}
  val writeOn = selectedWVec.map{ v => v.en }
  val readOn = selectedRVec.map{ v => v.en }
  val rwOn = writeOn.zip(readOn).map{ case(a,b) => a&b}.reduce{_|_} & selectedGlobalWen
  val rCollide = bankIdR.zip( readOn).map{ case(id1,en1) => bankIdR.zip( readOn).map{ case(id2,en2) => Mux((id1 === id2) & en1 & en2, 1.U, 0.U)}.reduce{_+_} }.reduce{_+_} !=  readOn.map{Mux(_, 1.U, 0.U)}.reduce{_+_}
  val wCollide = bankIdW.zip(writeOn).map{ case(id1,en1) => bankIdW.zip(writeOn).map{ case(id2,en2) => Mux((id1 === id2) & en1 & en2, 1.U, 0.U)}.reduce{_+_} }.reduce{_+_} != writeOn.map{Mux(_, 1.U, 0.U)}.reduce{_+_}
  io.debug.invalidWAddr := ~wInBound
  io.debug.invalidRAddr := ~rInBound
  io.debug.rwOn := rwOn
  io.debug.readCollision := rCollide
  io.debug.writeCollision := wCollide
  io.debug.error := ~wInBound | ~rInBound | rwOn | rCollide | wCollide

}


class NBufSRAM(val logicalDims: List[Int], val numBufs: Int, val w: Int, /*TODO: width, get rid of this!*/
           val banks: List[Int], val strides: List[Int], val numWriters: Int, val numReaders: Int,
           val wPar: Int, val rPar: Int, val bankingMode: String, val bitWidth: Int = 32) extends Module { 

  // Overloaded construters
  // Tuple unpacker
  def this(tuple: (List[Int], Int, Int, List[Int], List[Int], Int, Int,
           Int, Int, String)) = this(tuple._1,tuple._2,tuple._3,tuple._4,tuple._5,tuple._6,tuple._7,tuple._8,tuple._9,tuple._10)
  // Bankmode-less
  def this(logicalDims: List[Int], numBufs: Int, w: Int, 
           banks: List[Int], strides: List[Int], numWriters: Int, numReaders: Int,
           wPar: Int, rPar: Int) = this(logicalDims, numBufs, w, banks, strides, numWriters, numReaders, wPar, rPar, "strided")
  // If 1D, spatial will make banks and strides scalars instead of lists
  def this(logicalDims: List[Int], numBufs: Int, w: Int, 
           banks: Int, strides: Int, numWriters: Int, numReaders: Int,
           wPar: Int, rPar: Int) = this(logicalDims, numBufs, w, List(banks), List(strides), numWriters, numReaders, wPar, rPar, "strided")

  val depth = logicalDims.reduce{_*_} // Size of memory
  val N = logicalDims.length // Number of dimensions

  val io = IO( new Bundle {
    val sEn = Vec(numBufs, Input(Bool()))
    val sDone = Vec(numBufs, Input(Bool()))
    val w = Vec(numWriters*wPar, Input(new multidimW(N, bitWidth)))
    val broadcast = Vec(numWriters*wPar, Input(new multidimW(N, bitWidth)))
    val broadcastEn = Input(Bool())
    val writerStage = Input(UInt(5.W)) // TODO: Not implemented anywhere, not sure if needed
    val globalWEn = Vec(numWriters, Input(Bool())) // Bit wen for entire multidimW
    val wSel = Vec(numWriters, Input(Bool())) // Selects between multiple write bundles
    val r = Vec(numBufs* numReaders*rPar,Input(new multidimR(N, bitWidth))) // TODO: Spatial allows only one reader per mem
    val rSel = Vec(numBufs* numReaders, Input(Bool())) // TODO: Implement this for multiple readers on a buffer
    val output = new Bundle {
      val data  = Vec(numBufs* rPar, Output(UInt(bitWidth.W)))  
    }
    val debug = new Bundle {
      val invalidRAddr = Output(Bool())
      val invalidWAddr = Output(Bool())
      val rwOn = Output(Bool())
      val readCollision = Output(Bool())
      val writeCollision = Output(Bool())
      val error = Output(Bool())
    }
  })

  // Reconstruct input signals as 3d vectors
  val reconstructedW = io.w
  val reconstructedWSel = io.wSel
  val reconstructedGlobalWEn = io.globalWEn
  val reconstructedR = (0 until numBufs).map{ h => 
    Vec((0 until numReaders*rPar).map { 
      j => io.r(h*rPar*numReaders + j) 
    })
  }
  // // Chisel3 broke this on 3/24/2017...
  // val reconstructedOut = (0 until numBufs).map{ h =>
  //   Vec((0 until rPar).map {
  //     j => io.output.data(h*rPar + j)
  //   })
  // }

  // Get info on physical dims
  // TODO: Upcast dims to evenly bank
  val physicalDims = logicalDims.zip(banks).map { case (dim, b) => dim/b}
  val numMems = banks.reduce{_*_}

  // Create physical mems
  val srams = (0 until numBufs).map{ i => Module(
    new SRAM(logicalDims,
            w, banks, strides, 
            numWriters, numReaders, wPar, rPar, bankingMode)
  )}

  val sEn_latch = (0 until numBufs).map{i => Module(new SRFF())}
  val sDone_latch = (0 until numBufs).map{i => Module(new SRFF())}

  val swap = Wire(Bool())

  // Latch whether each buffer's stage is enabled and when they are done
  (0 until numBufs).foreach{ i => 
    sEn_latch(i).io.input.set := io.sEn(i)
    sEn_latch(i).io.input.reset := swap
    sEn_latch(i).io.input.asyn_reset := reset
    sDone_latch(i).io.input.set := io.sDone(i)
    sDone_latch(i).io.input.reset := swap
    sDone_latch(i).io.input.asyn_reset := reset
  }
  val anyEnabled = sEn_latch.map{ en => en.io.output.data }.reduce{_|_}
  swap := sEn_latch.zip(sDone_latch).map{ case (en, done) => en.io.output.data === done.io.output.data }.reduce{_&_} & anyEnabled

  val stateIn = Module(new NBufCtr())
  stateIn.io.input.start := 0.U
  stateIn.io.input.max := numBufs.U
  stateIn.io.input.enable := swap
  stateIn.io.input.countUp := false.B
  val statesInR = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr())
    c.io.input.start := i.U 
    c.io.input.max := numBufs.U
    c.io.input.enable := swap
    c.io.input.countUp := true.B
    c
  }

  val statesOut = (0 until numBufs).map{  i => 
    val c = Module(new NBufCtr())
    c.io.input.start := i.U 
    c.io.input.max := numBufs.U
    c.io.input.enable := swap
    c.io.input.countUp := false.B
    c
  }

  val broadcastDummyEn = Vec((0 until numWriters).map { j =>
    if (j==0) true.B else false.B
  }) // Shouldn't have multiple broadcasters anyway?
  srams.zipWithIndex.foreach{ case (f,i) => 
    val sel = (0 until numBufs).map{ q => statesInR(i).io.output.count === q.U }
    val wMask = stateIn.io.output.count === i.U
    val globalWEn = Wire(Vec(numWriters, Bool()))
    globalWEn.zip(reconstructedGlobalWEn).foreach{ case(g,x) => g := x & wMask}
    f.io.w := Mux(io.broadcastEn, io.broadcast,reconstructedW)
    f.io.wSel := Mux(io.broadcastEn, broadcastDummyEn, reconstructedWSel)
    f.io.globalWEn := Mux(io.broadcastEn, broadcastDummyEn, globalWEn)
    f.io.r := chisel3.util.Mux1H(sel, reconstructedR)
  }

  (0 until numBufs).zip(statesOut).foreach{ case (i, s) => 
    val sel = (0 until numBufs).map{ j => s.io.output.count === j.U }
    (0 until rPar).foreach{ j => 
      io.output.data(i*rPar + j) := chisel3.util.Mux1H(sel, srams.map{f => f.io.output.data(j)})
    }
  }

  var wIdMap = (0 until numBufs).map{ i => (i -> 0) }.toMap
  def connectWPort(wBundle: Vec[multidimW], en: Bool, ports: List[Int]) {
    if (ports.length == 1) {
      val port = ports(0)
      val wId = wIdMap(port)
      (0 until wBundle.length).foreach{ i => 
        io.w(i + numWriters*wPar*port + wId*wPar) := wBundle(i) 
      }
      io.writerStage := port.U
      io.globalWEn(port * numWriters + wId) := en 
      io.wSel(wId) := en & ( wBundle.map{ _.en}.reduce{_||_} )
      val next = wIdMap(port) + 1
      wIdMap += (port -> next)
    } else { // broadcast
      (0 until wPar).foreach{ i => 
        io.broadcast(i) := wBundle(i)
      }
      io.broadcastEn := en 
    }
  }
  var rId = 0
  def connectRPort(rBundle: Vec[multidimR], en: Bool, port: Int) {
    (0 until rBundle.length).foreach{ i => 
      io.r(i + rId*rPar + port*numReaders*rPar) := rBundle(i) 
    }
    (0 until numReaders).foreach {i => 
      io.rSel(port * numReaders + i) := en
    }

    rId = rId + 1
  }

  def connectStageCtrl(done: Bool, en: Bool, ports: List[Int]) {
    ports.foreach{ port => 
      io.sEn(port) := en
      io.sDone(port) := done
    }
  }

  def connectUnwrittenPorts(ports: List[Int]) { // TODO: Remnant from maxj?
    // ports.foreach{ port => 
    //   io.input(port).enable := false.B
    // }
  }
 
  def readTieDown(port: Int) { 
    (0 until numReaders).foreach {i => 
      io.rSel(port * numReaders + i) := false.B
    }
  }

  def connectUntouchedPorts(ports: List[Int]) {
    ports.foreach{ port => 
      io.sEn(port) := false.B
      io.sDone(port) := false.B
    }
  }

  def connectDummyBroadcast() {
    io.broadcastEn := false.B
  }



}
