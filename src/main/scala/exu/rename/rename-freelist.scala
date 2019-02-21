//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------
// Author: Christopher Celio
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
// Rename FreeList
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import boom.common._
import boom.util._

/**
 * IO bundle for accessing the free list
 *
 * @param num_pregs number of physical registers
 * @param pl_width pipeline width (dispatch group size)
 */
class FreeListIo(
   val num_pregs: Int,
   val pl_width: Int)
(implicit p: Parameters) extends BoomBundle()(p)
{
   private val preg_sz = log2Ceil(num_pregs)

   val req_preg_vals = Input(Vec(pl_width, Bool()))
   val req_pregs     = Output(Vec(pl_width, UInt(preg_sz.W))) // TODO: Confirm functionality

   // committed and newly freed register
   val enq_vals      = Input(Vec(pl_width, Bool()))
   val enq_pregs     = Input(Vec(pl_width, UInt(preg_sz.W)))

   // do we have space to service incoming requests? (per inst granularity)
   val can_allocate  = Output(Vec(pl_width, Bool()))

   // handle branches (save copy of freelist on branch, merge on mispredict)
   val ren_br_vals   = Input(Vec(pl_width, Bool()))
   val ren_br_tags   = Input(Vec(pl_width, UInt(BR_TAG_SZ.W)))

   // handle mispredicts
   val br_mispredict_val = Input(Bool())
   val br_mispredict_tag = Input(UInt(BR_TAG_SZ.W))

   // rollback (on exceptions)
   val rollback_wens  = Input(Vec(pl_width, Bool()))
   val rollback_pdsts = Input(Vec(pl_width, UInt(preg_sz.W)))

   // or...
   // TODO there are TWO free-list IOs now, based on constants. What is the best way to handle these two designs?
   // perhaps freelist.scala, and instantiate which-ever one I want?
   // TODO naming is inconsistent
   // TODO combine with rollback, whatever?
   val flush_pipeline = Input(Bool())
   val com_wens       = Input(Vec(pl_width, Bool()))
   val com_uops       = Input(Vec(pl_width, new MicroOp()))

   val debug = Output(new DebugFreeListIO(num_pregs))
}

class DebugFreeListIO(val num_pregs: Int) extends Bundle
{
   val freelist = Bits(num_pregs.W)
   val isprlist = Bits(num_pregs.W)
}

/**
 * Provide a fixed set of renamed destination registers
 * i.e., it doesn't matter if a previous UOP needs a pdst or not
 * this prevents a dependency chain from existing between UOPs when trying to
 * compute a pdst to give away (as well as computing if an available free
 * register exists.
 * NOTE: we never give out p0 -- that is the "unitialized" state of the map-table,
 * and the pipeline will give any reader of p0 0x0 as read data.
 *
 * @param num_pregs number of physical registers
 * @param pl_width pipeline width (dispatch group size)
 */
class RenameFreeListHelper(
   num_pregs: Int,
   pl_width: Int)
   (implicit p: Parameters) extends BoomModule()(p)
{
   val io = IO(new FreeListIo(num_pregs, pl_width))

   // ** FREE LIST TABLE ** //
   val free_list = RegInit(~(1.U(num_pregs.W)))

   // track all allocations that have occurred since branch passed by
   // can quickly reset pipeline on branch mispredict
   val allocation_lists = Reg(Vec(MAX_BR_COUNT, Bits(num_pregs.W)))

   // TODO why is this a Vec? can I do this all on one bit-vector?
   val enq_mask = Wire(Vec(pl_width, Bits(num_pregs.W)))

   // ------------------------------------------
   // find new,free physical registers

   val requested_pregs = Wire(Vec(pl_width, UInt(log2Ceil(num_pregs).W)))
   val requested_preg_vals = Wire(Vec(pl_width, Bool()))
   val requested_preg_rdys = Wire(Vec(pl_width, Bool()))

   val selected_pregs_oh_array = Array.fill(pl_width,num_pregs){false.B}
   val selected_pregs_oh       = Wire(Vec(pl_width, Bits(num_pregs.W)))
   val selected_pregs          = Wire(Vec(pl_width, UInt(log2Ceil(num_pregs).W)))
   var selected                = Wire(Vec(pl_width, Bool())) // Were we able to select a free register in each pipeline slot?

   // init
   for (w <- 0 until pl_width)
   {
      selected(w) := false.B
   }


   // don't give out p0
   for (i <- 1 until num_pregs)
   {
      val next_selected = Wire(Vec(pl_width, Bool()))
      var can_select = free_list(i)

      for (w <- 0 until pl_width)
      {
         selected_pregs_oh_array(w)(i) = can_select && !selected(w)

         next_selected(w) := can_select | selected(w)
         can_select = can_select && selected(w)
      }

      selected = next_selected
   }

   for (w <- 0 until pl_width)
   {
      selected_pregs_oh(w) := VecInit(selected_pregs_oh_array(w)).asUInt
      selected_pregs(w) := PriorityEncoder(selected_pregs_oh(w))
   }

   if (enablePipelinedAllocation) {
      for (w <- 0 until pl_width) {
         val valid = RegInit(false.B)
         val ready = !valid || io.req_preg_vals(w)
         valid := !ready || selected(w)
         val fire = ready && selected(w)

         requested_pregs(w) := RegEnable(selected_pregs(w), fire)
         requested_preg_rdys(w) := ready
         requested_preg_vals(w) := valid

         if (ENABLE_COMMIT_MAP_TABLE)
         {
            when (io.flush_pipeline)
            {
               valid := false.B
            }
         }
      }
   } else {
      requested_pregs := selected_pregs
      requested_preg_vals := selected
      requested_preg_rdys := io.req_preg_vals
   }

   // ------------------------------------------
   // Calculate next Free List
   val req_free_list = Wire(Bits(num_pregs.W))
   val enq_free_list = Wire(Bits(num_pregs.W))
   req_free_list := free_list
   enq_free_list := free_list

   // ** Set requested PREG to "Not Free" ** //

   // bit vector of newly allocated physical registers
   var just_selected_mask = 0.U(num_pregs.W)
   var just_allocated_mask = 0.U(num_pregs.W)

   // track which allocation_lists just got cleared out by a branch,
   // to enforce a write priority to allocation_lists()
   val br_cleared = Wire(Vec(MAX_BR_COUNT, Bool()))
   for (i <- 0 until MAX_BR_COUNT) { br_cleared(i) := false.B }

   for (w <- pl_width-1 to 0 by -1)
   {
      // When branching, start a fresh copy of the allocation_list
      // but don't forget to bypass in the allocations within our bundle
      when (io.ren_br_vals(w))
      {
         allocation_lists(io.ren_br_tags(w)) := just_allocated_mask
         br_cleared(io.ren_br_tags(w)) := true.B
      }

      // check that we both request a register and were able to allocate a register
      just_selected_mask = Mux(requested_preg_rdys(w) && selected(w), selected_pregs_oh(w) | just_selected_mask, just_selected_mask)
      if (enablePipelinedAllocation)
         just_allocated_mask = Mux(io.req_preg_vals(w) && requested_preg_vals(w), UIntToOH(requested_pregs(w)) | just_allocated_mask, just_allocated_mask)
      else
         just_allocated_mask = just_selected_mask
   }

   for (i <- 0 until MAX_BR_COUNT)
   {
      when (!br_cleared(i))
      {
         allocation_lists(i) := allocation_lists(i) | just_allocated_mask
      }
   }


   // ** Set enqueued PREG to "Free" ** //
   for (w <- 0 until pl_width)
   {
      enq_mask(w) := 0.U(num_pregs.W)
      when (io.enq_vals(w))
      {
         enq_mask(w) := 1.U << io.enq_pregs(w)
      }
      .elsewhen (io.rollback_wens(w))
      {
         enq_mask(w) := 1.U << io.rollback_pdsts(w)
      }
   }


   // Update the Free List
   when (!io.br_mispredict_val)
   {
      free_list := (free_list & ~(just_selected_mask)) | (enq_mask.reduce(_|_))
   }

   // Handle Misprediction
   //merge misspeculated allocation_list with free_list
   val allocation_list = Wire(Bits(num_pregs.W))
   allocation_list := allocation_lists(io.br_mispredict_tag)

   when (io.br_mispredict_val)
   {
      // include newly freed register as well!
      free_list := allocation_list | free_list | (enq_mask.reduce(_|_))

      // set other branch allocation_lists to zero where allocation_list(j) == 1...
      for (i <- 0 until MAX_BR_COUNT)
      {
         allocation_lists(i) := allocation_lists(i) & ~allocation_list
      }
   }


   // OPTIONALLY: handle single-cycle resets
   // Committed Free List tracks what the free list is at the commit point,
   // allowing for a single-cycle reset of the rename state on a pipeline flush.
   if (ENABLE_COMMIT_MAP_TABLE)
   {
      val committed_free_list = RegInit(~(1.U(num_pregs.W)))

      val com_mask = Wire(Vec(pl_width,   Bits(num_pregs.W)))
      val stale_mask = Wire(Vec(pl_width, Bits(num_pregs.W)))
      for (w <- 0 until pl_width)
      {
         com_mask(w) :=   0.U(num_pregs.W)
         stale_mask(w) := 0.U(num_pregs.W)
         when (io.com_wens(w))
         {
            com_mask(w) := 1.U << io.com_uops(w).pdst
            stale_mask(w) := 1.U << io.com_uops(w).stale_pdst
         }
      }

      committed_free_list := (committed_free_list & ~(com_mask.reduce(_|_))) | stale_mask.reduce(_|_)

      when (io.flush_pipeline)
      {
         free_list := committed_free_list
      }
      io.debug.isprlist := committed_free_list
   }

   // ** SET OUTPUTS ** //
   io.req_pregs := requested_pregs

   io.can_allocate := requested_preg_vals

   if (enablePipelinedAllocation)
      io.debug.freelist := (requested_pregs zip requested_preg_vals).map{case (preg, valid) => UIntToOH(preg) & Fill(num_pregs, valid.asUInt)}.fold(free_list)(_|_)
   else
      io.debug.freelist := free_list
}

/**
 * Rename free list that keeps track of what registers are free in the physical
 * register file
 *
 * @param pl_width pipeline width (dispatch group size)
 * @param rtype type of register the free list is operating on
 * @param num_pregs number of physical registers
 */
class RenameFreeList(
   pl_width: Int,
   rtype: BigInt,
   num_pregs: Int)
   (implicit p: Parameters) extends BoomModule()(p)
{
   private val preg_sz = log2Ceil(num_pregs)

   val io = IO(new Bundle
   {
      // Inputs
      val brinfo           = Input(new BrResolutionInfo())
      val kill             = Input(Bool())

      val ren_will_fire    = Input(Vec(pl_width, Bool()))
      val ren_uops         = Input(Vec(pl_width, new MicroOp()))
      val ren_br_vals      = Input(Vec(pl_width, Bool()))


      val com_valids       = Input(Vec(pl_width, Bool()))
      val com_uops         = Input(Vec(pl_width, new MicroOp()))
      val com_rbk_valids   = Input(Vec(pl_width, Bool()))


      val flush_pipeline   = Input(Bool())

      // Outputs
      val can_allocate     = Output(Vec(pl_width, Bool()))
      val req_pregs        = Output(Vec(pl_width, UInt(preg_sz.W)))

      val debug            = Output(new DebugFreeListIO(num_pregs))
      val debug_rob_empty  = Input(Bool())
   })

   val freelist = Module(new RenameFreeListHelper(
      num_pregs,
      pl_width))

   freelist.io.br_mispredict_val := io.brinfo.mispredict
   freelist.io.br_mispredict_tag := io.brinfo.tag
   freelist.io.flush_pipeline    := io.flush_pipeline

   for (w <- 0 until pl_width)
   {
      freelist.io.req_preg_vals(w)  := !io.kill &&
                                       io.ren_will_fire(w) &&
                                       io.ren_uops(w).ldst_val &&
                                       io.ren_uops(w).dst_rtype === rtype.U

      freelist.io.enq_vals(w)       := io.com_valids(w) &&
                                       io.com_uops(w).dst_rtype === rtype.U &&
                                       (io.com_uops(w).stale_pdst =/= 0.U || rtype.U === RT_FLT)
      freelist.io.enq_pregs(w)      := io.com_uops(w).stale_pdst

      freelist.io.ren_br_vals(w)    := io.ren_br_vals(w)
      freelist.io.ren_br_tags(w)    := io.ren_uops(w).br_tag


      freelist.io.rollback_wens(w)  := io.com_rbk_valids(w) &&
                                       (io.com_uops(w).pdst =/= 0.U || rtype.U === RT_FLT) &&
                                       io.com_uops(w).dst_rtype === rtype.U
      freelist.io.rollback_pdsts(w) := io.com_uops(w).pdst

      freelist.io.com_wens(w)       := io.com_valids(w) &&
                                       (io.com_uops(w).pdst =/= 0.U || rtype.U === RT_FLT) &&
                                       io.com_uops(w).dst_rtype === rtype.U
      freelist.io.com_uops(w)       := io.com_uops(w)

      if (rtype == RT_FIX.litValue) {
         // x0 is a special-case and should not be renamed
         io.req_pregs(w) := Mux(io.ren_uops(w).ldst === 0.U, 0.U, freelist.io.req_pregs(w))
      } else {
         io.req_pregs(w) := freelist.io.req_pregs(w)
      }
   }

   io.can_allocate := freelist.io.can_allocate
   io.debug := freelist.io.debug

   when (io.debug_rob_empty) {
      assert (PopCount(freelist.io.debug.freelist) >= (num_pregs - 32).U,
         "[freelist] We're leaking physical registers")
   }
}
