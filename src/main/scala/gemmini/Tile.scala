// See README.md for license details.

package gemmini

import chisel3._
import chisel3.util._

/**
  * A Tile is a purely combinational 2D array of passThrough PEs.
  * a, b, s, and in_propag are broadcast across the entire array and are passed through to the Tile's outputs
  * @param width The data width of each PE in bits
  * @param rows Number of PEs on each row
  * @param columns Number of PEs on each column
  */
class Tile[T <: Data : Arithmetic](inputType: T, outputType: T, accType: T, df: Dataflow.Value, pe_latency: Int, val rows: Int, val columns: Int) extends Module {
  val io = IO(new Bundle {
    val in_a        = Input(Vec(rows, inputType))
    val in_b        = Input(Vec(columns, outputType)) // This is the output of the tile next to it
    val in_d        = Input(Vec(columns, outputType))
    val in_control  = Input(Vec(columns, new PEControl(accType)))
    val out_a       = Output(Vec(rows, inputType))
    val out_c       = Output(Vec(columns, outputType))
    val out_b       = Output(Vec(columns, outputType))
    val out_control = Output(Vec(columns, new PEControl(accType)))

    val in_a_zero = Input(Vec(rows, Bool()))
    val in_b_zero = Input(Vec(columns, Bool()))
    val in_d_zero = Input(Vec(columns, Bool()))

    val out_a_zero = Output(Vec(rows, Bool()))
    val out_b_zero = Output(Vec(columns, Bool()))
    val out_c_zero = Output(Vec(columns, Bool()))

    val in_valid = Input(Vec(columns, Bool()))
    val out_valid = Output(Vec(columns, Bool()))

    val bad_dataflow = Output(Bool())
  })

  val tile = Seq.fill(rows, columns)(Module(new PE(inputType, outputType, accType, df, pe_latency)))
  val tileT = tile.transpose

  // TODO: abstract hori/vert broadcast, all these connections look the same
  // Broadcast 'a' horizontally across the Tile
  for (r <- 0 until rows) {
    tile(r).foldLeft(io.in_a(r)) {
      case (in_a, pe) =>
        pe.io.in_a := in_a
        pe.io.out_a
    }
  }

  // Broadcast 'b' vertically across the Tile
  for (c <- 0 until columns) {
    tileT(c).foldLeft(io.in_b(c)) {
      case (in_b, pe) =>
        pe.io.in_b := in_b
        pe.io.out_b
    }
  }

  // Broadcast 'd' vertically across the Tile
  for (c <- 0 until columns) {
    tileT(c).foldLeft(io.in_d(c)) {
      case (in_d, pe) =>
        pe.io.in_d := in_d
        pe.io.out_c
    }
  }

  // Broadcast 'control' vertically across the Tile
  for (c <- 0 until columns) {
    tileT(c).foldLeft(io.in_control(c)) {
      case (in_ctrl, pe) =>
        pe.io.in_control := in_ctrl
        pe.io.out_control
    }
  }

  // Broadcast 'garbage' vertically across the Tile
  for (c <- 0 until columns) {
    tileT(c).foldLeft(io.in_valid(c)) {
      case (v, pe) =>
        pe.io.in_valid := v
        pe.io.out_valid
    }
  }

  // Broadcast 'a_zero' horizontally across the Tile
  for (r <- 0 until columns) {
    tile(r).foldLeft(io.in_a_zero(r)) {
      case (z, pe) =>
        pe.io.in_a_zero := z
        pe.io.out_a_zero
    }
  }

  // Broadcast 'b_zero' vertically across the Tile
  for (c <- 0 until columns) {
    tileT(c).foldLeft(io.in_b_zero(c)) {
      case (z, pe) =>
        pe.io.in_b_zero := z
        pe.io.out_b_zero
    }
  }

  // Broadcast 'd_zero' vertically across the Tile
  for (c <- 0 until columns) {
    tileT(c).foldLeft(io.in_d_zero(c)) {
      case (z, pe) =>
        pe.io.in_d_zero := z
        pe.io.out_c_zero
    }
  }

  // Drive the Tile's bottom IO
  for (c <- 0 until columns) {
    io.out_c(c) := tile(rows-1)(c).io.out_c
    io.out_b(c) := tile(rows-1)(c).io.out_b
    io.out_control(c) := tile(rows-1)(c).io.out_control
    io.out_valid(c) := tile(rows-1)(c).io.out_valid

    io.out_b_zero(c) := tile(rows-1)(c).io.out_b_zero
    io.out_c_zero(c) := tile(rows-1)(c).io.out_c_zero
  }
  io.bad_dataflow := tile.map(_.map(_.io.bad_dataflow).reduce(_||_)).reduce(_||_)

  // Drive the Tile's right IO
  for (r <- 0 until rows) {
    io.out_a(r) := tile(r)(columns-1).io.out_a
    io.out_a_zero(r) := tile(r)(columns-1).io.out_a_zero
  }
}
